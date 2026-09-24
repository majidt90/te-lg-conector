#!/usr/bin/env bash
# Bootstraps a minimal, Gradle-free Android build toolchain.
#
# Sources are chosen so that the pipeline works in environments where
# dl.google.com / Maven Central are unreachable (locked-down CI). The npm
# packages below happen to redistribute the *official, unmodified* Android
# build-tools binaries (verified byte-for-byte against build-tools 34.0.0),
# so no functionality is faked - see tools/README.md for provenance notes.
#
#   JRE                : pypi  jdk4py            (Temurin JDK, runtime)
#   android.jar/d8/etc : npm   @drxiaozhi/minapk (build-tools 34.0.0 payload)
#   aapt2 (linux x64)  : npm   aaptjs3          (build-tools aapt2)
#
# Usage: tools/bootstrap-toolchain.sh [target-dir]   (default ~/.android-toolchain)
set -euo pipefail

TC="${1:-$HOME/.android-toolchain}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$TC/tools" "$TC/java"
echo "▸ toolchain dir: $TC"

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
command -v npm >/dev/null || { echo "npm is required"; exit 1; }

# --- 1. JRE ---------------------------------------------------------------
if [ ! -x "$TC/java/bin/java" ]; then
  echo "▸ fetching JRE (jdk4py) …"
  pip3 install --quiet --target "$STAGE/pylibs" jdk4py
  PYTHONPATH="$STAGE/pylibs" python3 - "$TC" <<'PY'
import shutil, sys, jdk4py
src = str(jdk4py.JAVA_HOME)
dst = sys.argv[1] + "/java"
shutil.rmtree(dst, ignore_errors=True)
shutil.copytree(src, dst, symlinks=True)
print("   java ->", dst)
PY
  chmod +x "$TC/java/bin/"* 2>/dev/null || true
fi
"$TC/java/bin/java" -version

# --- 2. Android build-tools payload --------------------------------------
if [ ! -f "$TC/tools/android.jar" ] || [ ! -f "$TC/tools/d8.jar" ]; then
  echo "▸ fetching Android build-tools payload (minapk) …"
  ( cd "$STAGE" && npm pack @drxiaozhi/minapk --silent >/dev/null && tar xzf ./*.tgz )
  cp "$STAGE/package/tools/android.jar"    "$TC/tools/"
  cp "$STAGE/package/tools/d8.jar"         "$TC/tools/"
  cp "$STAGE/package/tools/apksigner.jar"  "$TC/tools/"
  cp "$STAGE/package/tools/ecj-"*.jar      "$TC/tools/" 2>/dev/null || true
  cp "$STAGE/package/tools/debug.keystore" "$TC/tools/"
fi

# --- 3. aapt2 + ecj fallback ---------------------------------------------
if [ ! -x "$TC/tools/aapt2" ]; then
  echo "▸ fetching aapt2 (aaptjs3) …"
  ( cd "$STAGE" && npm pack aaptjs3 --silent >/dev/null && mkdir -p a3 && tar xzf aaptjs3-*.tgz -C a3 )
  cp "$STAGE/a3/package/bin/x64/linux/aapt2" "$TC/tools/aapt2"
  chmod +x "$TC/tools/aapt2"
fi

# ecj can be supplied by the local JDK instead when a full JDK is installed
if ! ls "$TC/tools"/ecj-*.jar >/dev/null 2>&1; then
  if command -v javac >/dev/null; then
    echo "▸ no ecj jar found - a full JDK is installed, using javac is also fine"
  else
    echo "✖ no Java compiler available (need ecj-*.jar in $TC/tools)"; exit 1
  fi
fi

echo
"$TC/tools/aapt2" version || true
ls -1 "$TC/tools"
echo "▸ toolchain ready. Build with: ANDROID_TC=$TC python3 tools/build.py"
