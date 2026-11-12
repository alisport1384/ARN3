#!/usr/bin/env bash
# Stages the JNI libraries that this repository does NOT compile into
# app/src/main/jniLibs/<abi>/.
#
# Why this exists
# ---------------
# scripts/build-natives.sh builds libhev-socks5-tunnel.so, libaethertun.so and
# libaether.so; scripts/build-xray.sh builds libxray.so. Four more libraries ship in
# every APK and none of them is produced by a script:
#
#   libpsiphon.so                    the Psiphon client (a Go binary)
#   libc++_shared.so                 the C++ runtime the JNI libraries link against
#   libandroidx.graphics.path.so     AndroidX native piece
#   libdatastore_shared_counter.so   DataStore native piece
#
# Before this script the workflow never copied them, so every CI run died at
# :app:verifyAetherEngineAssets with "Missing Psiphon client for arm64-v8a" - the build
# was right, the *workflow* was incomplete. All four come from the public upstream
# release APK, so this needs no secret and no private repository. (An earlier version of
# this script also read our own private release for the extras; the GitHub Actions token
# is scoped to this repository only and could not read it. The upstream APK carries all
# of them, so that dependency is gone.)
#
# Rules:
#   * every step is checked - a missing asset ABORTS instead of shipping a hole
#   * never overwrite a library another script produced in this run (build output wins)
#   * the Psiphon client must be the Go 1.26 build: a Go 1.27+ client panics inside
#     Psiphon-Labs/psiphon-tls' init() on every start ("struct field count mismatch")
#
# Usage:  scripts/stage-prebuilt-natives.sh [arm64-v8a] [armeabi-v7a]
#         WORK=/path/to/cache scripts/stage-prebuilt-natives.sh

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-${REPO}/../work/up}"
GOOD_GO_MARKER="go1.26"

UPSTREAM_BASE="${UPSTREAM_BASE:-https://github.com/QW-AI-Code/Aether/releases/download/v1.6.0-build.25}"
UPSTREAM_APK="Aether-1.6.0-%s.apk"

# Libraries taken from the upstream APK. libaether.so is NOT in this list: it is built
# here from source, and only used as an asset fallback further down.
PREBUILT_LIBS=(
    libpsiphon.so
    "libc++_shared.so"
    libandroidx.graphics.path.so
    libdatastore_shared_counter.so
)

if [ "$#" -gt 0 ]; then
    ABIS=("$@")
else
    ABIS=("arm64-v8a" "armeabi-v7a")
fi

mkdir -p "$WORK"

# Resolving a release asset through the API rather than through
# github.com/<owner>/<repo>/releases/download/<tag>/<file>: both forms work for a public
# repository, but the API redirect keeps the signature when the response is re-routed,
# and it lets a private repository work when a token is available.
asset_id_for() { # asset_id_for <owner> <repo> <tag> <file>
    local owner="$1" repo="$2" tag="$3" file="$4"
    curl -fsSL ${GITHUB_TOKEN:+-H "Authorization: Bearer ${GITHUB_TOKEN}"} \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${owner}/${repo}/releases/tags/${tag}" \
        | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((a['id'] for a in d.get('assets',[]) if a['name']==sys.argv[1]),''))" "$file"
}

fetch() { # fetch <url> <file>
    local url="$1" out="$2"
    if [ -s "$out" ]; then return 0; fi
    echo "stage-prebuilt-natives: downloading $(basename "$out")"

    local api_url="" auth=()
    if [[ "$url" =~ ^https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+)$ ]]; then
        local owner="${BASH_REMATCH[1]}" repo="${BASH_REMATCH[2]}"
        local tag="${BASH_REMATCH[3]}" file="${BASH_REMATCH[4]}"
        local id
        id="$(asset_id_for "$owner" "$repo" "$tag" "$file" 2>/dev/null || true)"
        if [ -n "$id" ]; then
            api_url="https://api.github.com/repos/${owner}/${repo}/releases/assets/${id}"
            if [ -n "${GITHUB_TOKEN:-}" ]; then
                auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
            fi
            auth+=(-H "Accept: application/octet-stream")
        fi
    fi

    local src="${api_url:-$url}"
    curl -fL --retry 3 --retry-delay 2 "${auth[@]+"${auth[@]}"}" -o "$out.part" "$src" \
        || { rm -f "$out.part"; return 1; }
    mv "$out.part" "$out"
    [ -s "$out" ] || return 1
}

STAGE="$WORK/stage-prebuilt"
rm -rf "$STAGE"
mkdir -p "$STAGE"

for abi in "${ABIS[@]}"; do
    echo "==> $abi"
    up="$WORK/up-$abi.apk"
    fetch "$(printf "$UPSTREAM_BASE/$UPSTREAM_APK" "$abi")" "$up" \
        || { echo "stage-prebuilt-natives: cannot download the upstream $abi APK" >&2; exit 1; }

    jni="${REPO}/app/src/main/jniLibs/$abi"
    mkdir -p "$jni" "${REPO}/app/src/main/assets/aether/$abi"

    # --- 1. the prebuilt libraries ------------------------------------------------
    rm -rf "$STAGE/up-$abi"
    unzip -o -q "$up" "lib/$abi/*" -d "$STAGE/up-$abi" \
        || { echo "stage-prebuilt-natives: upstream $abi APK has no lib/$abi/" >&2; exit 1; }
    for name in "${PREBUILT_LIBS[@]}"; do
        src="$STAGE/up-$abi/lib/$abi/$name"
        [ -s "$src" ] || { echo "stage-prebuilt-natives: upstream $abi APK has no $name" >&2; exit 1; }
        if [ -s "$jni/$name" ]; then
            echo "    keep  $name (already present - produced by an earlier step)"
        else
            cp "$src" "$jni/$name"
            echo "    add   $name"
        fi
    done

    # --- 2. the Psiphon client must be a Go 1.26 build -----------------------------
    # `strings ... | grep -q` is a trap under pipefail: grep exits at the first match,
    # strings dies of SIGPIPE and the pipeline reports 141 - which looks exactly like
    # "marker missing". Dump once, then grep the file.
    psiphon="$jni/libpsiphon.so"
    strings -a "$psiphon" > "$STAGE/strings-$abi.txt"
    if ! grep -qF "$GOOD_GO_MARKER" "$STAGE/strings-$abi.txt"; then
        seen="$(grep -oE "^go1\.[0-9]+(\.[0-9]+)?" "$STAGE/strings-$abi.txt" | sort -u | tr '\n' ' ')"
        echo "stage-prebuilt-natives: $abi client was built with [$seen], expected $GOOD_GO_MARKER.x." >&2
        echo "stage-prebuilt-natives: a Go 1.27+ client panics in psiphon-tls' init() on every start." >&2
        exit 1
    fi
    echo "    ok    libpsiphon.so ($(grep -oE "^go1\.[0-9]+\.[0-9]+" "$STAGE/strings-$abi.txt" | sort -u | head -1))"

    # --- 3. engine binary as an asset + server list --------------------------------
    # The engine is also carried as an asset because the app materialises it itself. If
    # build-natives.sh already ran, that (freshly compiled) binary is used; otherwise the
    # upstream one is staged so the asset directory is never empty.
    for candidate in "$jni/libaether.so" "$STAGE/up-$abi/lib/$abi/libaether.so"; do
        if [ -s "$candidate" ] && [ ! -s "${REPO}/app/src/main/assets/aether/$abi/libaether.so" ]; then
            cp "$candidate" "${REPO}/app/src/main/assets/aether/$abi/libaether.so"
            echo "    add   assets/aether/$abi/libaether.so"
            break
        fi
    done

    entries="${REPO}/app/src/main/assets/server_entries.txt"
    if [ ! -s "$entries" ]; then
        unzip -p "$up" "assets/server_entries.txt" > "$entries.tmp" 2>/dev/null || true
        if [ -s "$entries.tmp" ]; then
            mv "$entries.tmp" "$entries"
            echo "    add   assets/server_entries.txt"
        else
            rm -f "$entries.tmp"
            echo "stage-prebuilt-natives: assets/server_entries.txt missing from the $abi APK" >&2
            exit 1
        fi
    fi
done

rm -rf "$STAGE"
echo "stage-prebuilt-natives: OK"
