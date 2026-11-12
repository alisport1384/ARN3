#!/usr/bin/env bash
#
# Runs the far side of BigRocket's address tunnel (BridgeServer.kt) on a server.
#
#   ./run-bridge.sh                       # UDP 5300 + TCP 5301, random cookie
#   ./run-bridge.sh --udp 5300 --any-cookie
#
# Needs a JDK 17+ and downloads the Kotlin compiler (~80 MB, once) into ./.kotlin if kotlinc is
# not already on PATH. Everything stays inside this directory; nothing is installed system-wide.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
KOTLIN_DIR="${HERE}/.kotlin"
JAR="${HERE}/bridge.jar"

if ! command -v java >/dev/null 2>&1; then
  echo "error: a JDK 17+ is required (java not found on PATH)" >&2
  exit 1
fi

# Rebuild the jar when the source is newer than it.
if [ ! -f "${JAR}" ] || [ "${HERE}/BridgeServer.kt" -nt "${JAR}" ]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KC="kotlinc"
  else
    if [ ! -x "${KOTLIN_DIR}/kotlinc/bin/kotlinc" ]; then
      echo "==> fetching the Kotlin compiler ${KOTLIN_VERSION} (one time)"
      mkdir -p "${KOTLIN_DIR}"
      curl -fL --retry 3 -o "${KOTLIN_DIR}/kotlin.zip" \
        "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
      ( cd "${KOTLIN_DIR}" && unzip -q -o kotlin.zip && rm -f kotlin.zip )
    fi
    KC="${KOTLIN_DIR}/kotlinc/bin/kotlinc"
  fi
  echo "==> building bridge.jar"
  "${KC}" "${HERE}/BridgeServer.kt" -include-runtime -d "${JAR}"
fi

echo "==> starting the bridge"
exec java -jar "${JAR}" "$@"
