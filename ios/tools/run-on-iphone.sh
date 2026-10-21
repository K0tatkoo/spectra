#!/bin/bash
# Builds Spectra (release) and installs it on the iPhone plugged into this Mac,
# signed by the Apple ID signed in to Xcode (Settings → Accounts). A free
# Personal Team's install stops launching after 7 days; run this again to renew.
#
#   ios/tools/run-on-iphone.sh            the one iPhone that is connected
#   SPECTRA_DEVICE=<udid> SPECTRA_TEAM=<team id> ios/tools/run-on-iphone.sh
#
# The phone needs Developer Mode on (Settings → Privacy & Security). The team ID
# is read from Xcode at run time, so it never lands in the repository.
set -euo pipefail
cd "$(dirname "$0")/.."

json=$(mktemp -t spectra-devices)
trap 'rm -f "$json"' EXIT
xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1

device=${SPECTRA_DEVICE:-$(python3 - "$json" <<'EOF'
import json, sys
devices = json.load(open(sys.argv[1]))["result"]["devices"]
phones = [d for d in devices
          if d["hardwareProperties"].get("reality") == "physical"
          and d["hardwareProperties"].get("platform") == "iOS"
          and d["connectionProperties"].get("tunnelState") != "unavailable"]
if len(phones) != 1:
    sys.exit(f"Expected one connected iPhone, found {len(phones)}. Set SPECTRA_DEVICE to its UDID.")
d = phones[0]
if d["deviceProperties"].get("developerModeStatus") != "enabled":
    sys.exit(f"{d['deviceProperties']['name']}: turn on Developer Mode (Settings → Privacy & Security), then run this again.")
print(d["hardwareProperties"]["udid"])
EOF
)}

# Xcode 27 keeps the team list to itself, so the team is the one named in the
# Apple Development certificates (their OU). With none yet: Xcode → Settings →
# Apple Accounts → the team → Manage Certificates… → + → Apple Development.
signing_team() {
    local ids
    ids=$(security find-certificate -a -c "Apple Development" -p 2>/dev/null |
        awk '/BEGIN CERT/{c=""} {c=c $0 "\n"} /END CERT/{printf "%s", c | "openssl x509 -noout -subject -nameopt multiline"; close("openssl x509 -noout -subject -nameopt multiline")}' |
        awk '/organizationalUnitName/{print $3}' | sort -u)
    if [ -z "$ids" ]; then
        echo "No Apple Development certificate: sign in to Xcode → Settings → Apple Accounts and add one." >&2
        return 1
    elif [ "$(echo "$ids" | wc -l)" -gt 1 ]; then
        echo "More than one team: set SPECTRA_TEAM to one of" $ids >&2
        return 1
    fi
    echo "$ids"
}
team=${SPECTRA_TEAM:-$(signing_team)}

log=build/device/run-on-iphone.log
mkdir -p build/device
if ! xcodebuild -project Spectra.xcodeproj -scheme Spectra -configuration Release \
    -destination "id=$device" -derivedDataPath build/device \
    -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
    DEVELOPMENT_TEAM="$team" build >"$log" 2>&1; then
    grep -E ' error: ' "$log" | sort -u >&2
    echo "The build failed; the whole log is $log" >&2
    exit 1
fi
app=build/device/Build/Products/Release-iphoneos/Spectra.app

xcrun devicectl device install app --device "$device" "$app"
xcrun devicectl device process launch --device "$device" com.n3d.spectra
