#!/usr/bin/env bash
# Prepares app/src/main/{jniLibs,assets} from release artifacts.
#
# HARD RULE (learned the hard way, twice):
#   * Every step below is checked. A missing download or a failed copy ABORTS the
#     script instead of leaving a stale file in place - a silently shipped old
#     binary is exactly how build .12 ended up refusing to start.
#   * libpsiphon.so is NOT taken from our own older release: releases <= .9 ship a
#     client built with Go 1.27, whose crypto/tls.ConnectionState has one field more
#     than Psiphon-Labs/psiphon-tls' fork expects, so psiphon-tls' init() panics
#     ("struct field count mismatch: 18 vs 17") on every start. The upstream project
#     ships the same client built with Go 1.26.3. We take that one and verify it.
set -euo pipefail

BASE_OURS="https://github.com/alisport1384/ARN/releases/download/v1.0.0-staticproxy.9"
BASE_UPSTREAM="https://github.com/QW-AI-Code/Aether/releases/download/v1.6.0-build.25"
WORK="${WORK:-/home/user/up}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOOD_GO_MARKER="go1.26"   # fixed string, matched with grep -F

mkdir -p "$WORK"

fetch() { # fetch <url> <file>
    local url="$1" out="$2"
    if [ -s "$out" ]; then return 0; fi
    echo "prepare-natives: downloading $(basename "$out")"
    curl -fL --retry 3 -o "$out.part" "$url" || { rm -f "$out.part"; return 1; }
    mv "$out.part" "$out"
    [ -s "$out" ] || return 1
}

fetch "$BASE_OURS/BigRocket-arm64-v8a.apk"  "$WORK/a64.apk"
fetch "$BASE_OURS/BigRocket-armeabi-v7a.apk" "$WORK/a32.apk"
fetch "$BASE_UPSTREAM/Aether-1.6.0-arm64-v8a.apk"  "$WORK/up-arm64-v8a.apk"
fetch "$BASE_UPSTREAM/Aether-1.6.0-armeabi-v7a.apk" "$WORK/up-armeabi-v7a.apk"

STAGE="$WORK/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/jniLibs/arm64-v8a" "$STAGE/jniLibs/armeabi-v7a" \
         "$STAGE/assets/aether/arm64-v8a" "$STAGE/assets/aether/armeabi-v7a"

unzip -o -q "$WORK/a64.apk" "lib/arm64-v8a/*"       -d "$STAGE/eng"
unzip -o -q "$WORK/a32.apk" "lib/armeabi-v7a/*"     -d "$STAGE/eng"
unzip -p  "$WORK/a64.apk"   "assets/server_entries.txt" > "$STAGE/assets/server_entries.txt"

cp "$STAGE"/eng/lib/arm64-v8a/*   "$STAGE/jniLibs/arm64-v8a/"
cp "$STAGE"/eng/lib/armeabi-v7a/* "$STAGE/jniLibs/armeabi-v7a/"

# The engine binary is also carried as an asset (the app materialises it itself).
cp "$STAGE/jniLibs/arm64-v8a/libaether.so"   "$STAGE/assets/aether/arm64-v8a/"
cp "$STAGE/jniLibs/armeabi-v7a/libaether.so" "$STAGE/assets/aether/armeabi-v7a/"

# ---- Psiphon client: the Go 1.26 build only ----
for abi in arm64-v8a armeabi-v7a; do
    unzip -o -q "$WORK/up-$abi.apk" "lib/$abi/libpsiphon.so" -d "$STAGE/up" \
        || { echo "prepare-natives: upstream $abi APK has no libpsiphon.so" >&2; exit 1; }
    src="$STAGE/up/lib/$abi/libpsiphon.so"
    [ -s "$src" ] || { echo "prepare-natives: extracted $abi client is empty" >&2; exit 1; }
    # NOTE: `strings ... | grep -q` is a trap under `set -o pipefail`: grep exits at
    # the first match, strings dies of SIGPIPE, and the pipeline then reports 141 -
    # which looks exactly like "the marker is missing". Dump once, grep the file.
    strings -a "$src" > "$STAGE/strings.txt"
    if ! grep -qF "$GOOD_GO_MARKER" "$STAGE/strings.txt"; then
        seen="$(grep -oE "^go1\.[0-9]+(\.[0-9]+)?" "$STAGE/strings.txt" | sort -u | tr '\n' ' ')"
        echo "prepare-natives: $abi client was built with [$seen], expected a $GOOD_GO_MARKER.x build." >&2
        echo "prepare-natives: that client panics in psiphon-tls' init() (struct field count mismatch)." >&2
        exit 1
    fi
    cp "$src" "$STAGE/jniLibs/$abi/libpsiphon.so"
done

# ---- Install into the project, then re-verify what actually landed there ----
mkdir -p "$REPO/app/src/main"
cp -r "$STAGE/jniLibs" "$REPO/app/src/main/"
cp -r "$STAGE/assets"  "$REPO/app/src/main/"
for abi in arm64-v8a armeabi-v7a; do
    final="$REPO/app/src/main/jniLibs/$abi/libpsiphon.so"
    [ -s "$final" ] || { echo "prepare-natives: $final missing after copy" >&2; exit 1; }
    strings -a "$final" > "$STAGE/strings.txt"
    grep -qF "$GOOD_GO_MARKER" "$STAGE/strings.txt" \
        || { echo "prepare-natives: $final is NOT a $GOOD_GO_MARKER build" >&2; exit 1; }
done

rm -rf "$STAGE/eng" "$STAGE/up" "$STAGE/strings.txt"
echo "prepare-natives: OK (client: $(strings -a "$REPO/app/src/main/jniLibs/arm64-v8a/libpsiphon.so" | grep -oE "^go1\.[0-9]+\.[0-9]+" | sort -u | head -1))"
