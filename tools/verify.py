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
import shutil
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TC = os.environ.get("ANDROID_TC", os.path.expanduser("~/.android-toolchain"))
JAVA_SRC = os.path.join(REPO, "app", "src", "main", "java", "com", "lgmediabridge")
VERIFY_SRC = os.path.join(REPO, "tools", "verify")
HARNESS = os.path.join(VERIFY_SRC, "MediaBridgeTests.java")

# Classes under test: pure Java by design (see the file headers), plus the small
# set of Android stubs in tools/verify/stubs that keep reference types resolvable.
SOURCES = [
    "core/LogEntry.java",
    "core/LogBus.java",
    "core/Json.java",
    "core/Xml.java",
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
    "control/TvDevice.java",
    "control/SoapClient.java",
    "stream/StreamSession.java",
]

STUBS = [
    "android/util/Log.java",
    "android/util/LruCache.java",
]


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
    compile_result = subprocess.run(
        [java, "-jar", ecj, "-source", "11", "-target", "11", "-proc:none", "-warn:none",
         "-d", out, f"@{source_list}"],
        capture_output=True, text=True)
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
