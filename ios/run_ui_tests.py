"""Keep CI test-runner startup bounded and preserve its diagnostic output."""
import os
from pathlib import Path
import signal
import subprocess
import sys

command = [
    "xcodebuild", "-project", "ios/ComedorControl.xcodeproj",
    "-scheme", "ComedorControl", "-configuration", "Debug",
    "-destination", f"platform=iOS Simulator,arch=arm64,id={sys.argv[1]}",
    "-destination-timeout", "60", "-parallel-testing-enabled", "NO",
    "-disableAutomaticPackageResolution", "-skipPackageUpdates",
    "-test-timeouts-enabled", "YES",
    "-maximum-test-execution-time-allowance", "120",
    "-derivedDataPath", "ios/build/DerivedData",
    "-resultBundlePath", "ios/build/validation/UITests.xcresult",
    "CODE_SIGNING_ALLOWED=NO", "test-without-building",
]
with Path("ios/build/validation/simulator.log").open("w") as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        result = process.wait(timeout=420)
    except subprocess.TimeoutExpired:
        print("The simulator test runner exceeded 7 minutes; see simulator.log.", flush=True)
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        result = 124
sys.exit(result)
