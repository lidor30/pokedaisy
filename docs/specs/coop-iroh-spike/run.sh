#!/bin/bash
# run.sh PEERLOG MODE [ADDRS_OVERRIDE] - dials the peer in PEERLOG from the harness on $DEVICE, prints its log
A="adb -s ${DEVICE:?set DEVICE to an adb serial}"
read -r _ ID RELAY ADDRS <<< "$(grep ^PEER "$1")"
[ -n "${3+x}" ] && ADDRS="$3"
$A logcat -c
$A shell am force-stop spike.iroh
$A shell am start -n spike.iroh/spike.MainActivity --es id "$ID" --es relay "$RELAY" --es addrs "${ADDRS:-,}" --es mode "$2" --ei count 200 >/dev/null
for i in $(seq 1 60); do $A logcat -d -s irohspike | grep -q "DONE\|FAIL" && break; sleep 2; done
$A logcat -d -s irohspike | grep irohspike | sed 's/^.*irohspike: //'
