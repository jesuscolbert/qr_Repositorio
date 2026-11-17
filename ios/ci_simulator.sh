#!/bin/bash
set -euo pipefail
mkdir -p ios/build/validation
DEVICE=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; ds=json.load(sys.stdin)["devices"]; print(next(d["udid"] for runtime,items in ds.items() if "iOS" in runtime for d in items if d["name"].startswith("iPhone")))')
xcrun simctl boot "$DEVICE" || true
xcrun simctl bootstatus "$DEVICE" -b
xcodebuild -project ios/ComedorControl.xcodeproj -scheme ComedorControl -configuration Debug -destination "platform=iOS Simulator,id=$DEVICE" -derivedDataPath ios/build/DerivedData -resultBundlePath ios/build/validation/UITests.xcresult CODE_SIGNING_ALLOWED=NO test > ios/build/validation/simulator.log 2>&1 || { tail -n 140 ios/build/validation/simulator.log; exit 1; }
xcrun xcresulttool export attachments --path ios/build/validation/UITests.xcresult --output-path ios/build/validation/screenshots || true
ditto -c -k --keepParent ios/build/DerivedData/Build/Products/Debug-iphonesimulator/ComedorControl.app ios/build/Colbert-Control-iOS-Simulador.zip
