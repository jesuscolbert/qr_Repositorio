#!/bin/bash
set -euo pipefail
mkdir -p ios/build/validation
xcodebuild -project ios/ComedorControl.xcodeproj -scheme ComedorControl -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO build-for-testing > ios/build/validation/build.log 2>&1 || { tail -n 160 ios/build/validation/build.log; exit 1; }
SDK_MAJOR=$(xcrun --sdk iphonesimulator --show-sdk-version | cut -d. -f1)
DEVICE=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; ds=json.load(sys.stdin)["devices"]; print(next(d["udid"] for runtime,items in ds.items() if "iOS-"+sys.argv[1]+"-" in runtime for d in items if d["name"].startswith("iPhone")))' "$SDK_MAJOR")
echo "Simulator SDK major: $SDK_MAJOR; device: $DEVICE"
xcrun simctl boot "$DEVICE" || true
xcrun simctl bootstatus "$DEVICE" -b
set +e
python3 ios/run_ui_tests.py "$DEVICE"
RESULT=$?
set -e
xcrun xcresulttool export attachments --path ios/build/validation/UITests.xcresult --output-path ios/build/validation/screenshots || true
CONTAINER=$(xcrun simctl get_app_container "$DEVICE" bo.colbert.comedor.control data) || true
if [ -n "$CONTAINER" ] && [ -d "$CONTAINER/Documents/validation" ]; then cp -R "$CONTAINER/Documents/validation" ios/build/validation/native-exports; fi
if [ "$RESULT" -ne 0 ]; then tail -n 140 ios/build/validation/simulator.log; exit "$RESULT"; fi
ditto -c -k --keepParent ios/build/DerivedData/Build/Products/Debug-iphonesimulator/ComedorControl.app ios/build/Colbert-Control-iOS-Simulador.zip
