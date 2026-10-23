#!/usr/bin/env bash
# Smoke-tests an `elci` executable on the examples. release.yml runs it on the native CLI it releases, and
# dist-build.yml on the native CLI of every platform and on the JVM distribution.
#
#   bash .github/scripts/cli-smoke.sh <elci executable>
#
# Environment: VERSION and JULC_VERSION, which `elci --version` must report. Run from the repository root. On
# Windows, Git Bash finds elci.exe from the name without the extension.
set -euo pipefail

: "${VERSION:?}" "${JULC_VERSION:?}"
elci=${1:?usage: cli-smoke.sh <elci executable>}

# Windows executables end lines with \r\n.
actual=$("$elci" --version | tr -d '\r')
if [[ "$actual" != "elci $VERSION / julc $JULC_VERSION" ]]; then
  echo "::error::elci --version printed '$actual', expected 'elci $VERSION / julc $JULC_VERSION'" >&2
  exit 1
fi
# `elci init` needs an empty directory; remove the projects of an earlier run.
rm -rf build/init-smoke build/init-named
"$elci" init build/init-smoke --purpose mint
"$elci" check build/init-smoke
"$elci" check examples/vesting
"$elci" build examples/vesting
test -s examples/vesting/build/validators/vesting/validator.plutus.json
"$elci" eval examples/elci-library | tr -d '\r' | tee build/smoke-eval.txt
grep -Fx '105' build/smoke-eval.txt
"$elci" build examples/treasury
test -s examples/treasury/build/validators/treasury/validator.plutus.json
test -s examples/treasury/build/validators/badge/abi.json
"$elci" check examples/treasury --validator badge
"$elci" apply examples/treasury --validator badge \
  --param 'admin={"bytes": "01000000000000000000000000000000000000000000000000000000"}'
test -s examples/treasury/build/validators/badge/applied.plutus.json
"$elci" init build/init-named --validator rewards --purpose withdraw
"$elci" build build/init-named
test -s build/init-named/build/validators/rewards/validator.plutus.json
"$elci" test build/init-named
"$elci" test examples/treasury
"$elci" test examples/vesting
