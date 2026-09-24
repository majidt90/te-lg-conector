#!/usr/bin/env python3
"""
Gradle-free Android build pipeline for MediaBridge (LG).

Why this exists
---------------
The Android app in this repository has **zero third-party dependencies** (pure
framework APIs), so it can be compiled without Gradle/AGP. This is useful for
headless/CI environments where the Android SDK and Maven repositories are not
reachable. Android Studio / Gradle remains the recommended path for day to day
development (see README.md) - both paths compile the exact same sources.

Pipeline
--------
  aapt2 compile  ->  aapt2 link  ->  ecj (compile .java)  ->  d8 (dex)
      ->  repack APK with 4-byte aligned resources.arsc  ->  apksigner

Toolchain requirements (see tools/bootstrap-toolchain.sh):
  * a JRE (JDK 17+)                                    -> $TC/java/bin/java
  * aapt2 (Android SDK build-tools)                     -> $TC/tools/aapt2
  * android.jar (SDK platform, API 34)                  -> $TC/tools/android.jar
  * d8.jar, apksigner.jar                               -> $TC/tools/
  * ecj-*.jar (Eclipse compiler, works with a JRE)      -> $TC/tools/
  * a debug keystore                                     -> $TC/tools/debug.keystore
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TC = os.environ.get("ANDROID_TC", os.path.expanduser("~/.android-toolchain"))

MIN_SDK = 26
TARGET_SDK = 34
NAMESPACE = "com.lgmediabridge"


def log(msg: str) -> None:
    print(f"\033[36m▸\033[0m {msg}", flush=True)


def die(msg: str) -> None:
    print(f"\033[31m✖ {msg}\033[0m", flush=True)
    sys.exit(1)


def run(cmd: list[str], quiet: bool = False) -> str:
    if not quiet:
        shown = " ".join(cmd)
        print(f"  $ {shown if len(shown) < 160 else shown[:157] + '...'}", flush=True)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stdout[-6000:])
        print(proc.stderr[-6000:], file=sys.stderr)
        die(f"command failed: {cmd[0]}")
    return proc.stdout + proc.stderr


class Toolchain:
    def __init__(self, tc: str):
        self.tc = tc
        self.tools = os.path.join(tc, "tools")
        self.java = self._find_java()
        for name in ("aapt2", "android.jar", "d8.jar", "apksigner.jar", "debug.keystore"):
            if not os.path.exists(os.path.join(self.tools, name)):
                die(f"missing toolchain file: {self.tools}/{name}\n"
                    f"       run tools/bootstrap-toolchain.sh first (see README.md)")
        self.ecj = self._find_ecj()

    def _find_java(self) -> str:
        """Locates a JRE: toolchain copy, JAVA_HOME, PATH, then a pip-installed one.

        The searches below only look at a handful of shallow locations on
        purpose: a recursive scan of the filesystem to find a JDK would make this
        script look hung on machines that do not have one.
        """
        candidates = [os.path.join(self.tc, "java", "bin", "java")]
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidates.append(os.path.join(java_home, "bin", "java"))
        found = shutil.which("java")
        if found:
            candidates.append(found)

        # A JRE installed for this project (tools/bootstrap-toolchain.sh):
        #   pip install --target .pylibs jdk4py   ->  .pylibs/jdk4py/java-runtime/bin/java
        try:  # pragma: no cover - depends on the local environment
            import jdk4py  # type: ignore
            candidates.append(str(jdk4py.JAVA))
        except Exception:
            pass
        roots = [os.environ.get("PYTHONPATH", ""), os.path.expanduser("~/.pylibs"),
                 os.path.expanduser("~/.local/lib"), "/usr/local/lib",
                 "/usr/lib/jvm", os.path.join(self.tc, "jre")]
        for root in roots:
            for entry in root.split(os.pathsep):
                if not entry:
                    continue
                candidates.append(os.path.join(entry, "bin", "java"))
                candidates.append(os.path.join(entry, "jdk4py", "java-runtime", "bin", "java"))
                try:
                    for child in sorted(os.listdir(entry)):
                        for depth in ("bin/java", "jre/bin/java", "lib/jvm/bin/java"):
                            candidates.append(os.path.join(entry, child, *depth.split("/")))
                        # jdk4pyN.N.N / javaproperties style layout
                        try:
                            for grandchild in sorted(os.listdir(os.path.join(entry, child)))[:20]:
                                candidates.append(os.path.join(entry, child, grandchild,
                                                               "bin", "java"))
                        except OSError:
                            pass
                except OSError:
                    continue
        for candidate in candidates:
            if candidate and os.path.exists(candidate):
                return candidate
        die("no Java runtime found: set JAVA_HOME or run tools/bootstrap-toolchain.sh\n"
            "       (a JRE is enough - the app is compiled with the Eclipse compiler)")

    def _find_ecj(self) -> str:
        for fn in os.listdir(self.tools):
            if fn.startswith("ecj") and fn.endswith(".jar"):
                return os.path.join(self.tools, fn)
        die("missing ecj compiler jar in toolchain (tools/bootstrap-toolchain.sh)")
        return ""

    def compile_classpath(self) -> str:
        """
        android.jar contains stub copies of java.* too, which collide with the
        modular JDK ("package java.util is accessible from more than one
        module"). AGP avoids this with --system; here we strip java.*/javax.*
        from a copy so the platform's own java.base supplies them, while d8
        still gets the complete jar as its library.
        """
        target = os.path.join(self.tools, "android-compile.jar")
        source = self.path("android.jar")
        if os.path.exists(target) and os.path.getmtime(target) >= os.path.getmtime(source):
            return target
        log("preparing compile-only android.jar (java.*/javax.* stripped)")
        with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                name = item.filename
                if name.startswith(("java/", "javax/", "sun/", "com/sun/")) or name.endswith("module-info.class"):
                    continue
                zout.writestr(item, zin.read(name))
        return target

    def path(self, name: str) -> str:
        return os.path.join(self.tools, name)


def zip_info(name: str, store: bool, extra_pad: int = 0) -> zipfile.ZipInfo:
    zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    zi.create_system = 3
    if extra_pad:
        # Raw zero padding in the extra field is exactly what zipalign does.
        zi.extra = b"\x00" * extra_pad
    return zi


def repack(base_apk: str, dex_files: list[str], out_apk: str, align: int = 4) -> None:
    """Build the final APK: keep aapt2 output, append classes*.dex, align STORED entries."""
    src = zipfile.ZipFile(base_apk)
    names = [i.filename for i in src.infolist()]
    with zipfile.ZipFile(out_apk, "w") as out:
        for name in names:
            data = src.read(name)
            store = name.endswith("resources.arsc") or name.endswith(".so")
            zi = zip_info(name, store)
            if store:
                base_len = 30 + len(name.encode("utf-8"))
                pad = (align - (base_len % align)) % align
                zi.extra = b"\x00" * pad
            out.writestr(zi, data)
        for dex in sorted(dex_files):
            out.write(dex, os.path.basename(dex), compress_type=zipfile.ZIP_DEFLATED)
    src.close()


def verify_alignment(apk: str, align: int = 4) -> None:
    data = open(apk, "rb").read()
    off = 0
    checked = 0
    while off + 30 <= len(data) and data[off:off + 4] == b"PK\x03\x04":
        method = struct.unpack("<H", data[off + 8:off + 10])[0]
        csize = struct.unpack("<I", data[off + 18:off + 22])[0]
        nlen, xlen = struct.unpack("<HH", data[off + 26:off + 30])
        name = data[off + 30:off + 30 + nlen].decode("utf-8", "replace")
        data_off = off + 30 + nlen + xlen
        if method == zipfile.ZIP_STORED:
            checked += 1
            if data_off % align != 0:
                die(f"{name} stored at {data_off} is not {align}-byte aligned")
        off = data_off + csize
    if checked:
        log(f"alignment check passed ({checked} stored entries)")


def main() -> None:
    ap = argparse.ArgumentParser(description="Build MediaBridge APK without Gradle")
    ap.add_argument("--toolchain", default=DEFAULT_TC)
    ap.add_argument("--variant", default="debug", choices=["debug", "release"])
    ap.add_argument("--out", default=os.path.join(REPO, "dist"))
    ap.add_argument("--keystore", default=None)
    ap.add_argument("--ks-pass", default="android")
    ap.add_argument("--key-alias", default="androiddebugkey")
    ap.add_argument("--key-pass", default="android")
    ap.add_argument("--clean", action="store_true")
    args = ap.parse_args()

    started = time.time()
    tc = Toolchain(args.toolchain)
    src = os.path.join(REPO, "app", "src", "main")
    res, manifest = os.path.join(src, "res"), os.path.join(src, "AndroidManifest.xml")
    java_src = os.path.join(src, "java")
    for required in (res, manifest, java_src):
        if not os.path.exists(required):
            die(f"missing {required}")

    build = os.path.join(REPO, ".build")
    if args.clean:
        shutil.rmtree(build, ignore_errors=True)
    for sub in ("gen", "classes", "dex", "res"):
        os.makedirs(os.path.join(build, sub), exist_ok=True)
    os.makedirs(args.out, exist_ok=True)

    # 1. resources ---------------------------------------------------------
    log("aapt2: compiling resources")
    res_zip = os.path.join(build, "res.zip")
    if os.path.exists(res_zip):
        os.remove(res_zip)
    run([tc.path("aapt2"), "compile", "--dir", res, "-o", res_zip])

    # 2. link --------------------------------------------------------------
    # The Gradle build gets the package from `namespace`, but aapt2 itself still
    # requires a package attribute in the manifest. A copy with the attribute
    # injected is used here so the source manifest stays AGP-clean.
    log("aapt2: linking resources + manifest")
    base_apk = os.path.join(build, "base.apk")
    gen = os.path.join(build, "gen")
    manifest_for_aapt = os.path.join(build, "AndroidManifest.xml")
    with open(manifest, encoding="utf-8") as fh:
        manifest_text = fh.read()
    if 'package=' not in manifest_text.split(">", 1)[0]:
        manifest_text = manifest_text.replace(
            "<manifest ", f'<manifest package="{NAMESPACE}" ', 1)
    with open(manifest_for_aapt, "w", encoding="utf-8") as fh:
        fh.write(manifest_text)
    run([tc.path("aapt2"), "link", "-o", base_apk, "-I", tc.path("android.jar"),
         "--manifest", manifest_for_aapt, "-R", res_zip, "--java", gen,
         "--custom-package", NAMESPACE,
         "--min-sdk-version", str(MIN_SDK), "--target-sdk-version", str(TARGET_SDK),
         "--version-code", "1", "--version-name", "1.0.0", "--auto-add-overlay"])

    # 3. java --------------------------------------------------------------
    sources: list[str] = []
    for root_dir in (java_src, gen):
        for dirpath, _, files in os.walk(root_dir):
            sources += [os.path.join(dirpath, f) for f in files if f.endswith(".java")]
    if not sources:
        die("no java sources found")
    log(f"ecj: compiling {len(sources)} java files")
    src_list = os.path.join(build, "sources.txt")
    with open(src_list, "w") as fh:
        fh.write("\n".join(sources))
    classes = os.path.join(build, "classes")
    run([tc.java, "-jar", tc.ecj, "-source", "11", "-target", "11", "-proc:none",
         "-warn:none", "-classpath", tc.compile_classpath(), "-d", classes, "@" + src_list])

    # 4. dex ---------------------------------------------------------------
    log("d8: dexing")
    class_files = []
    for dirpath, _, files in os.walk(classes):
        class_files += [os.path.join(dirpath, f) for f in files if f.endswith(".class")]
    if not class_files:
        die("no class files produced")
    dex_dir = os.path.join(build, "dex")
    shutil.rmtree(dex_dir, ignore_errors=True)
    os.makedirs(dex_dir)
    run([tc.java, "-Xmx2g", "-cp", tc.path("d8.jar"), "com.android.tools.r8.D8",
         "--min-api", str(MIN_SDK), "--lib", tc.path("android.jar"),
         "--output", dex_dir] + class_files)

    # 5. package -----------------------------------------------------------
    log("packaging APK")
    dex_files = [os.path.join(dex_dir, f) for f in os.listdir(dex_dir) if f.endswith(".dex")]
    unsigned = os.path.join(build, "unsigned.apk")
    repack(base_apk, dex_files, unsigned)
    verify_alignment(unsigned)

    # 6. sign --------------------------------------------------------------
    log("apksigner: signing")
    ks = args.keystore or tc.path("debug.keystore")
    alias = args.key_alias
    kspass, keypass = args.ks_pass, args.key_pass
    if args.variant == "release" and args.keystore is None:
        die("release builds require --keystore/--ks-pass/--key-alias/--key-pass")
    out_apk = os.path.join(args.out, f"MediaBridge-{args.variant}.apk")
    if os.path.exists(out_apk):
        os.remove(out_apk)
    sign_args = [tc.java, "-jar", tc.path("apksigner.jar"), "sign",
                 "--ks", ks, "--ks-pass", f"pass:{kspass}",
                 "--ks-key-alias", alias, "--key-pass", f"pass:{keypass}",
                 "--v1-signing-enabled", "false" if MIN_SDK >= 24 else "true",
                 "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                 "--out", out_apk, unsigned]
    run(sign_args)

    info = run([tc.java, "-jar", tc.path("apksigner.jar"), "verify", "--verbose", out_apk], quiet=True)
    for line in info.splitlines():
        if line.startswith("Verified using"):
            print("   " + line)

    badging = run([tc.path("aapt2"), "dump", "badging", out_apk], quiet=True)
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    perms = len(re.findall(r"uses-permission", badging))
    size = os.path.getsize(out_apk)
    log(f"built {os.path.relpath(out_apk, REPO)}  "
        f"({size / 1024:.0f} KiB, {pkg.group(1) if pkg else '?'} v{pkg.group(2) if pkg else '?'}, "
        f"{perms} permissions, {time.time() - started:.1f}s)")
    print(f"\n  install:  adb install -r {os.path.relpath(out_apk, REPO)}")


if __name__ == "__main__":
    main()
