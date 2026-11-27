#!/usr/bin/env bash
# Usage: scripts/run.sh pinned|patched|upstream-main
# Clones Mobile Wallet Adapter, drops the gauntlet tests into walletlib's own Robolectric suite,
# optionally applies the fixes, runs the tests and checks the outcome against EXPECTED.json.
set -euo pipefail
MODE="${1:?usage: run.sh pinned|patched|upstream-main}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/upstream.env"
WORK="${WORK:-$HERE/work}"
rm -rf "$WORK" && mkdir -p "$WORK"

git clone --quiet --filter=blob:none "$MWA_REPO" "$WORK/mwa"
if [ "$MODE" != "upstream-main" ]; then
  git -C "$WORK/mwa" checkout --quiet "$MWA_REF"
fi
echo "Mobile Wallet Adapter at $(git -C "$WORK/mwa" rev-parse HEAD)"

DEST="$WORK/mwa/android/walletlib/src/test/java/com/solana/mobilewalletadapter/walletlib/scenario"
cp "$HERE"/tests/walletlib/*.java "$DEST/"

if [ "$MODE" = "patched" ]; then
  git -C "$WORK/mwa" apply --verbose "$HERE/patches/public-fixes.patch"
fi

cd "$WORK/mwa/android"
chmod +x gradlew
TASKS=(:walletlib:testDebugUnitTest)
if [ "$MODE" = "patched" ]; then
  # With the fixes, the whole existing suite of every touched module must still pass.
  TASKS=(:common:testDebugUnitTest :walletlib:testDebugUnitTest :clientlib:testDebugUnitTest)
fi
set +e
./gradlew --no-daemon --continue "${TASKS[@]}"
GRADLE_RC=$?
set -e
echo "gradle exit code: $GRADLE_RC"

python3 "$HERE/scripts/check.py" "$MODE" "$WORK/mwa/android" "$HERE/EXPECTED.json"
