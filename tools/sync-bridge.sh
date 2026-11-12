#!/usr/bin/env bash
# Copies the deployed far side (far-side/BridgeServer.kt) into the unit-test source set.
#
# Why a copy at all: far-side/ is a stand-alone single-file program (run with the JDK on a
# server), while the tests need it compiled into the app's test classpath. Keeping ONE source of
# truth and copying it here means "the bridge passed the tests" and "the bridge the user runs"
# can never drift - and BridgeSingleSourceTest proves byte-for-byte equality on every run.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$here/far-side/BridgeServer.kt"
dst="$here/app/src/test/java/bridgesrc/BridgeServer.kt"
if [ ! -f "$src" ]; then
  echo "missing $src" >&2
  exit 1
fi
mkdir -p "$(dirname "$dst")"
{
  # Two edits, both made HERE and only here:
  #  - a package declaration, so the tests can import the bridge like a normal class (the
  #    deployed file stays a standalone unpackaged program);
  #  - the `#!/usr/bin/env kotlin` shebang dropped, for the same reason: Kotlin only accepts it as
  #    the very first line of an unpackaged file.
  # BridgeSingleSourceTest re-applies those two edits to the deployed file and compares.
  echo "package bridgesrc"
  echo
  grep -v '^#!/usr/bin/env kotlin' "$src"
} > "$dst"
echo "synced $src -> $dst ($(wc -c < "$dst") bytes)"
