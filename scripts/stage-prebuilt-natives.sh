#!/usr/bin/env bash
# Stages the prebuilt JNI libraries - the ones we do NOT compile ourselves - into
# app/src/main/jniLibs/<abi>/.
#
# Why this exists
# ---------------
# scripts/build-natives.sh builds libhev-socks5-tunnel.so and libaether.so, and
# scripts/build-xray.sh builds libxray.so. Everything else in jniLibs comes from an
# upstream release APK, and before this script the CI workflow simply never copied
# those files. The result was a red pipeline on every run:
#
#   Execution failed for task ':app:verifyAetherEngineAssets'
#   > Missing Psiphon client for arm64-v8a: .../jniLibs/arm64-v8a/libpsiphon.so
#
# i.e. the build was correct and the *workflow* was incomplete. This script closes
# that gap, for every ABI, and aborts loudly if a source APK is missing a library
# rather than shipping a build with a hole in it.
#
# Same rules as prepare-natives.sh:
#   * every step is checked; a missing download or a failed copy ABORTS
#   * the Psiphon client must be the Go 1.26 build (a Go 1.27+ client panics inside
#     Psiphon-Labs/psiphon-tls' init() on every start: "struct field count mismatch")
#   * never overwrite a library that a build script produced in this same run - the
#     locally compiled ones win, because they match the source tree we are building
#
# Usage:  scripts/stage-prebuilt-natives.sh [arm64-v8a] [armeabi-v7a]
#         WORK=/path/to/cache scripts/stage-prebuilt-natives.sh

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-${REPO}/../work/up}"
GOOD_GO_MARKER="go1.26"

# Pinned release assets. Both are public; override with environment variables if a
# release moves.
UPSTREAM_BASE="${UPSTREAM_BASE:-https://github.com/QW-AI-Code/Aether/releases/download/v1.6.0-build.25}"
OURS_BASE="${OURS_BASE:-https://github.com/alisport1384/ARN/releases/download/v1.0.0-staticproxy.9}"

# libpsiphon.so comes from upstream (Go 1.26 client). The .so files our own older
# release shipped also include the Python/Kotlin runtime pieces that are not part of
# any build script, so that release is the source for those.
UPSTREAM_APK_FOR_PSIPHON="Aether-1.6.0-%s.apk"
OURS_APK="BigRocket-%s.apk"

if [ "$#" -gt 0 ]; then
    ABIS=("$@")
else
    ABIS=("arm64-v8a" "armeabi-v7a")
fi

mkdir -p "$WORK"

# Resolving a release asset through the API instead of through
# github.com/<owner>/<repo>/releases/download/<tag>/<file>:
# the browser URL of a *private* repository's asset redirects to a signed CDN URL, and
# that redirect loses the Authorization header, so it answers 404. The API form
# (Accept: application/octet-stream) redirects with the right signature and works for
# public repositories too, so one code path covers both.
asset_id_for() { # asset_id_for <owner> <repo> <tag> <file>
    local owner="$1" repo="$2" tag="$3" file="$4"
    [ -n "${GITHUB_TOKEN:-}" ] || return 1
    curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${owner}/${repo}/releases/tags/${tag}" \
        | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((a['id'] for a in d.get('assets',[]) if a['name']==sys.argv[1]),''))" "$file"
}

fetch() { # fetch <url> <file>
    local url="$1" out="$2"
    if [ -s "$out" ]; then return 0; fi
    echo "stage-prebuilt-natives: downloading $(basename "$out")"

    local auth=()
    local api_url=""
    if [[ "$url" =~ ^https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+)$ ]]; then
        local owner="${BASH_REMATCH[1]}" repo="${BASH_REMATCH[2]}"
        local tag="${BASH_REMATCH[3]}" file="${BASH_REMATCH[4]}"
        local id
        id="$(asset_id_for "$owner" "$repo" "$tag" "$file" 2>/dev/null || true)"
        if [ -n "$id" ]; then
            api_url="https://api.github.com/repos/${owner}/${repo}/releases/assets/${id}"
            auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/octet-stream")
        fi
    fi

    if [ -n "$api_url" ]; then
        curl -fL --retry 3 --retry-delay 2 "${auth[@]}" -o "$out.part" "$api_url" \
            || { rm -f "$out.part"; return 1; }
    else
        curl -fL --retry 3 --retry-delay 2 -o "$out.part" "$url" \
            || { rm -f "$out.part"; return 1; }
    fi
    mv "$out.part" "$out"
    [ -s "$out" ] || return 1
}

STAGE="$WORK/stage-prebuilt"
rm -rf "$STAGE"
mkdir -p "$STAGE"

for abi in "${ABIS[@]}"; do
    echo "==> $abi"
    up="$WORK/up-$abi.apk"
    ours="$WORK/ours-$abi.apk"
    fetch "$(printf "$UPSTREAM_BASE/$UPSTREAM_APK_FOR_PSIPHON" "$abi")" "$up" \
        || { echo "stage-prebuilt-natives: cannot download the upstream $abi APK" >&2; exit 1; }
    fetch "$(printf "$OURS_BASE/$OURS_APK" "$abi")" "$ours" \
        || { echo "stage-prebuilt-natives: cannot download our $abi APK" >&2; exit 1; }

    jni="${REPO}/app/src/main/jniLibs/$abi"
    mkdir -p "$jni" "${REPO}/app/src/main/assets/aether/$abi"

    # --- 1. every library our own release shipped (it is a superset: psiphon, aether,
    #        xray, hev, plus the runtime pieces) -------------------------------------
    rm -rf "$STAGE/ours-$abi"
    unzip -o -q "$ours" "lib/$abi/*" -d "$STAGE/ours-$abi" \
        || { echo "stage-prebuilt-natives: our $abi APK has no lib/$abi/" >&2; exit 1; }
    copied=0
    for src in "$STAGE/ours-$abi/lib/$abi"/*.so; do
        [ -s "$src" ] || continue
        name="$(basename "$src")"
        # The Psiphon client is the one library that must NEVER be taken from our own
        # release: ours were built with Go 1.27 (see the header). It is staged from
        # upstream in step 4, unconditionally.
        if [ "$name" = "libpsiphon.so" ]; then
            echo "    skip  libpsiphon.so (always taken from upstream below)"
            continue
        fi
        if [ -s "$jni/$name" ]; then
            echo "    keep  $name (already present - built here or staged earlier)"
        else
            cp "$src" "$jni/$name"
            echo "    add   $name"
            copied=$((copied + 1))
        fi
    done
    [ "$copied" -gt 0 ] || echo "    (nothing new from our release)"

    # --- 2. the engine binary must also exist as an asset (verifyAetherEngineAssets) ---
    if [ -s "$jni/libaether.so" ] && [ ! -s "${REPO}/app/src/main/assets/aether/$abi/libaether.so" ]; then
        cp "$jni/libaether.so" "${REPO}/app/src/main/assets/aether/$abi/"
        echo "    add   assets/aether/$abi/libaether.so"
    fi

    # --- 3. server_entries.txt (one copy, ABI independent) -----------------------------
    entries="${REPO}/app/src/main/assets/server_entries.txt"
    if [ ! -s "$entries" ]; then
        unzip -p "$ours" "assets/server_entries.txt" > "$entries.tmp" 2>/dev/null || true
        if [ -s "$entries.tmp" ]; then
            mv "$entries.tmp" "$entries"
            echo "    add   assets/server_entries.txt"
        else
            rm -f "$entries.tmp"
            echo "stage-prebuilt-natives: assets/server_entries.txt missing from the $abi APK" >&2
            exit 1
        fi
    fi

    # --- 4. the Psiphon client: unconditionally the upstream Go 1.26 build --------------
    rm -rf "$STAGE/up-$abi"
    unzip -o -q "$up" "lib/$abi/libpsiphon.so" -d "$STAGE/up-$abi" \
        || { echo "stage-prebuilt-natives: upstream $abi APK has no libpsiphon.so" >&2; exit 1; }
    psiphon_src="$STAGE/up-$abi/lib/$abi/libpsiphon.so"
    [ -s "$psiphon_src" ] || { echo "stage-prebuilt-natives: extracted $abi client is empty" >&2; exit 1; }
    # `strings ... | grep -q` is a trap under pipefail: grep exits at the first match,
    # strings dies of SIGPIPE and the pipeline reports 141 - which looks exactly like
    # "marker missing". Dump once, then grep the file.
    strings -a "$psiphon_src" > "$STAGE/strings-$abi.txt"
    if ! grep -qF "$GOOD_GO_MARKER" "$STAGE/strings-$abi.txt"; then
        seen="$(grep -oE "^go1\.[0-9]+(\.[0-9]+)?" "$STAGE/strings-$abi.txt" | sort -u | tr '\n' ' ')"
        echo "stage-prebuilt-natives: the upstream $abi client was built with [$seen], expected $GOOD_GO_MARKER.x." >&2
        echo "stage-prebuilt-natives: a Go 1.27+ client panics in psiphon-tls' init() on every start." >&2
        exit 1
    fi
    cp "$psiphon_src" "$jni/libpsiphon.so"
    echo "    add   libpsiphon.so ($(grep -oE "^go1\.[0-9]+\.[0-9]+" "$STAGE/strings-$abi.txt" | sort -u | head -1))"

    # --- 5. what the Gradle verifier will insist on -------------------------------
    for required in libaether.so libxray.so libpsiphon.so; do
        [ -s "$jni/$required" ] || { echo "stage-prebuilt-natives: $abi is missing $required" >&2; exit 1; }
    done
    [ -s "${REPO}/app/src/main/assets/aether/$abi/libaether.so" ] \
        || { echo "stage-prebuilt-natives: $abi is missing the aether engine asset" >&2; exit 1; }
done

rm -rf "$STAGE"
echo "stage-prebuilt-natives: OK"
