#!/usr/bin/env python3
"""
JVM verification harness for the parts of MediaBridge that talk to the TV.

Why this exists
---------------
The most failure-prone code in this project is not the UI: it is the wire format
the LG television parses (DIDL-Lite, SCPDs, SOAP envelopes, SSDP messages) and the
HTTP framing that decides whether seeking works. All of that is deliberately
written as pure Java with no Android types, so it can be compiled and executed on
a plain JVM - which is what this script does, with no emulator and no device.

It is a real test suite, not a smoke test: HTTP requests are parsed off a loopback
socket, HTTP responses are written to a byte buffer and inspected, and every XML
document the app can emit is validated with the platform's XML parser.

Usage
-----
    python3 tools/verify.py            # compile + run every check
    python3 tools/verify.py --verbose  # show each check as it runs

Requires only the toolchain from tools/bootstrap-toolchain.sh (an ecj jar and a
JRE), i.e. exactly what tools/build.py needs.
"""
from __future__ import annotations

import argparse
import os
import zipfile
import shutil
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TC = os.environ.get("ANDROID_TC", os.path.expanduser("~/.android-toolchain"))
JAVA_SRC = os.path.join(REPO, "app", "src", "main", "java", "com", "lgmediabridge")
VERIFY_SRC = os.path.join(REPO, "tools", "verify")
HARNESS = os.path.join(VERIFY_SRC, "MediaBridgeTests.java")

# Classes under test. Most are pure Java by design (see the file headers);
# MediaCompat is included because its codec tables - not its file probing - decide
# what the TV is offered, and they are plain data operations. It compiles against
# the platform android.jar and only ever touches the two stubbed classes at run
# time, which is what makes it testable without a device.
SOURCES = [
    "core/LogEntry.java",
    "core/LogBus.java",
    "core/Json.java",
    "core/Xml.java",
    "core/Formats.java",
    "net/HttpRange.java",
    "net/HttpRequest.java",
    "net/HttpResponse.java",
    "dlna/Dlna.java",
    "dlna/Soap.java",
    "dlna/DidlLite.java",
    "dlna/DeviceDescription.java",
    "dlna/SsdpMessages.java",
    "catalog/MediaItem.java",
    "compat/CompatResult.java",
    "compat/MediaCompat.java",
    "control/TvDevice.java",
    "control/SoapClient.java",
    "server/MediaPath.java",
    "stream/StreamSession.java",
    "stream/StreamRegistry.java",
]

STUBS = [
    "android/util/Log.java",
    "android/util/LruCache.java",
]


# Packages the platform jar duplicates from JDK modules. Keeping both on the
# classpath makes ecj report "the package X is accessible from more than one
# module", so the platform's copies are removed for this compile only.
SHADOWED_PREFIXES = ("java/", "javax/", "org/w3c/", "org/xml/")


def find_android_jar(tc: str, out: str) -> str | None:
    """
    A compile-only android.jar for classes that reference platform types.

    tools/build.py already strips java.*/javax.* from android.jar (the modular JDK
    supplies those); this strips the remaining duplicates as well, so the harness
    can import both Android types and, say, org.w3c.dom.Document.
    """
    source = None
    for candidate in (os.path.join(tc, "tools", "android-compile.jar"),
                      os.path.join(tc, "tools", "android.jar")):
        if os.path.exists(candidate):
            source = candidate
            break
    if source is None:
        return None
    target = os.path.join(out, "android-verify.jar")
    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w", zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            if item.filename.startswith(SHADOWED_PREFIXES):
                continue
            zout.writestr(item, zin.read(item.filename))
    return target


def log(msg: str) -> None:
    print(f"\033[36m▸\033[0m {msg}", flush=True)


def die(msg: str) -> None:
    print(f"\033[31m✖ {msg}\033[0m", flush=True)
    sys.exit(1)


def find_java(tc: str) -> str:
    candidates = [os.path.join(tc, "java", "bin", "java")]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(os.path.join(java_home, "bin", "java"))
    found = shutil.which("java")
    if found:
        candidates.append(found)
    try:  # a JRE installed with pip (tools/bootstrap-toolchain.sh)
        import jdk4py  # type: ignore
        candidates.append(str(jdk4py.JAVA))
    except Exception:
        pass
    candidates.append(os.path.expanduser("~/.pylibs/jdk4py/java-runtime/bin/java"))
    for candidate in candidates:
        if candidate and os.path.exists(candidate):
            return candidate
    die("no Java runtime found: set JAVA_HOME or run tools/bootstrap-toolchain.sh")


def find_ecj(tc: str) -> str:
    tools = os.path.join(tc, "tools")
    if os.path.isdir(tools):
        for name in sorted(os.listdir(tools)):
            if name.startswith("ecj") and name.endswith(".jar"):
                return os.path.join(tools, name)
    die("no ecj jar found in the toolchain: run tools/bootstrap-toolchain.sh")


def collect() -> list[str]:
    sources = []
    for relative in SOURCES:
        path = os.path.join(JAVA_SRC, relative)
        if not os.path.exists(path):
            die(f"missing source under test: {relative}")
        sources.append(path)
    for root, _, files in os.walk(os.path.join(VERIFY_SRC, "stubs")):
        sources += [os.path.join(root, f) for f in files if f.endswith(".java")]
    sources.append(HARNESS)
    return sources


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--toolchain", default=DEFAULT_TC)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    java = find_java(args.toolchain)
    ecj = find_ecj(args.toolchain)
    out = os.path.join(REPO, ".build", "verify")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)

    sources = collect()
    log(f"ecj: compiling {len(sources)} files (app classes + stubs + harness)")
    source_list = os.path.join(out, "sources.txt")
    with open(source_list, "w") as handle:
        handle.write("\n".join(sources))
    started = time.time()
    classpath = find_android_jar(args.toolchain, out)
    if classpath is None:
        log("note: android.jar not found, compiling pure-Java classes only")
    compile_command = [java, "-jar", ecj, "-source", "11", "-target", "11", "-proc:none",
                       "-warn:none", "-d", out]
    if classpath:
        compile_command += ["-classpath", classpath]
    compile_command += [f"@{source_list}"]
    compile_result = subprocess.run(compile_command, capture_output=True, text=True)
    if compile_result.returncode != 0:
        print(compile_result.stdout[-4000:])
        print(compile_result.stderr[-4000:], file=sys.stderr)
        die("the verification harness did not compile")

    log("running checks on the JVM")
    run = subprocess.run([java, "-cp", out, "MediaBridgeTests"]
                         + (["--verbose"] if args.verbose else []),
                         capture_output=True, text=True)
    print(run.stdout, end="")
    if run.stderr:
        print(run.stderr, file=sys.stderr, end="")
    if run.returncode == 0:
        log(f"verified in {time.time() - started:.1f}s "
            f"(harness output above; run with --verbose for every check)")
    sys.exit(run.returncode)


if __name__ == "__main__":
    main()
