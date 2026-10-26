#!/bin/sh
set -eu
repository=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
classes="$repository/tests/build/java"
mkdir -p "$classes"
javac -encoding UTF-8 -d "$classes" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/ComputerConnection.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/LocalBridgeServer.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/PairingGate.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/CaptureBatch.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/BluetoothWire.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/RequestAuth.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/PairingExchange.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/LanPolicy.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/ReconnectSchedule.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/RoutePolicy.java" \
  "$repository/android/app/src/main/java/com/ewan/wallpaperbridge/PermissionGuide.java" \
  "$repository/tests/ComputerConnectionTest.java" \
  "$repository/tests/CatDiaoEdgeTest.java" \
  "$repository/tests/BluetoothWireTest.java" \
  "$repository/tests/SecurityPolicyTest.java"
for check in CatDiaoEdgeTest ComputerConnectionTest BluetoothWireTest SecurityPolicyTest; do
  java -cp "$classes" "com.ewan.wallpaperbridge.$check"
done
export CATDIAO_TEST_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v java >/dev/null 2>&1; then
  echo "java is required" >&2
  exit 1
fi
export CATDIAO_TEST_JAVA="$(command -v java)"
export PYTHONIOENCODING=utf-8
cd "$repository"
python3 -m unittest discover -s tests -p 'test_*.py' -v
