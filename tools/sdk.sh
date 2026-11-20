#!/bin/sh
# The HMG-API mod SDK Car Sound builds with:
#   eval "$(sh tools/sdk.sh)"
# prints HMGAPI_SDK=<the unpacked SDK>: the release hmgapi-sdk.json pins ({"version", "sha256"}),
# downloaded from HMG-API's GitHub releases, checked against that SHA-256 and cached in .hmgapi/
# (not committed). An HMGAPI_SDK already set is kept (an SDK you built: ./build.sh sdk in HMG-API).
# HMGAPI_REPO: where HMG-API's releases are (Open-HMG/HMG-API).
set -e
cd "$(dirname "$0")/.."
REPO=${HMGAPI_REPO:-Open-HMG/HMG-API}
V=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["version"])')
SUM=$(python3 -c 'import json; print(json.load(open("hmgapi-sdk.json"))["sha256"])')
C=$PWD/.hmgapi/sdk-$V
if [ -z "${HMGAPI_SDK:-}" ]; then
    if [ ! -f "$C/sdk/lib/hmgapi-engine.jar" ]; then
        mkdir -p "$C"
        curl -fsSL -o "$C/sdk.zip" "https://github.com/$REPO/releases/download/v$V/hmgapi-mod-sdk-$V.zip" >&2
        python3 - "$C/sdk.zip" "$SUM" <<'PY' >&2
import hashlib, sys
got = hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest()
if got != sys.argv[2]:
    sys.exit("hmgapi-mod-sdk: sha256 %s, but hmgapi-sdk.json pins %s" % (got, sys.argv[2]))
PY
        rm -rf "$C/sdk" && mkdir -p "$C/sdk" && unzip -q "$C/sdk.zip" -d "$C/sdk"
    fi
    HMGAPI_SDK=$C/sdk
fi
echo "HMGAPI_SDK=$HMGAPI_SDK; export HMGAPI_SDK"
