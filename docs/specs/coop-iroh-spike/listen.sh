#!/bin/bash
# listen.sh MODE - the harness listens on $DEVICE; prints its PEER line
A="adb -s ${DEVICE:?set DEVICE to an adb serial}"
$A logcat -c; $A shell am force-stop spike.iroh
$A shell am start -n spike.iroh/spike.MainActivity --es mode "$1" >/dev/null
for i in $(seq 1 40); do $A logcat -d -s irohspike | grep -q "PEER\|FAIL" && break; sleep 1; done
$A logcat -d -s irohspike | grep irohspike | sed 's/^.*irohspike: //'
