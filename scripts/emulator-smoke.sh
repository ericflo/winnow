#!/usr/bin/env bash
# Installs Winnow on an Android emulator and feeds it sample traffic: SMS through the
# emulator's virtual modem and MMS through the debug-only DebugMmsReceiver. Every number is a
# fictional 555-01xx number. Nothing leaves the emulator.
#
# Refuses to run unless exactly one device is attached and it is an emulator.
set -euo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}
devices=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
count=$(printf '%s\n' "$devices" | grep -c . || true)
if [ "$count" != 1 ]; then
    echo "Attach exactly one emulator (found $count devices)." >&2
    exit 1
fi
if [ "$("$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
    echo "Refusing: $devices is not an emulator. This script never touches a real phone." >&2
    exit 1
fi

./gradlew :app:assembleDebug --console=plain -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell cmd role add-role-holder android.app.role.SMS com.ericflo.winnow 0
for permission in POST_NOTIFICATIONS READ_CONTACTS READ_PHONE_NUMBERS; do
    "$ADB" shell pm grant com.ericflo.winnow "android.permission.$permission"
done

# The emulator's own line, so group MMS can leave "me" out of the participants.
me=$("$ADB" shell dumpsys isub | grep -o 'number=+[0-9]*' | head -1 | cut -d= -f2)
me=${me:-+15551234567}

sms() { "$ADB" emu sms send "$1" "$2" >/dev/null; sleep 2; }
mms() { "$ADB" shell am broadcast -n com.ericflo.winnow/.debug.DebugMmsReceiver "$@" >/dev/null; sleep 2; }

sms 4155550101 "Are you still coming Sunday? Dad's making his chili"
sms 3185550182 "E-ZPass: Your toll balance of 4.35 USD is unpaid. Avoid a late fee, pay today: ezpass-tolls.top/pay"
sms 7715550190 "BREAKING: Senate vote collapses. We need 500 more signatures by midnight, donate now"
sms 72975 "Your Northwind verification code is 482913. It expires in 10 minutes."
sms 4155550101 'Loved "Are you still coming Sunday? Dad'"'"'s making his chili"'
mms --es from +14155550181 --es to "$me,+14155550182" --es text "'Lake house is booked for the 18th!'" --ez photo true
mms --es from +14155550182 --es to "$me,+14155550181" --es text "'Count me in'"
# A subject, a street address and a date: the bold subject line and smart links.
mms --es from +14155550181 --es to "$me,+14155550182" --es subject "'Lake house'" \
    --es text "'It is 12 Shoreline Dr, Tahoe City, CA 96145. Check-in is Friday October 16 at 4pm.'"
mms --es mode push --es from +14155550191
# A text Winnow never classified (as if from before it was the SMS app), from a stranger: the
# "Not in your contacts" card, and Filter sender giving it a verdict of its own.
"$ADB" shell am broadcast -n com.ericflo.winnow/.debug.DebugSeedReceiver \
    --es from +12065550142 --es text "'Your package is on hold, confirm your address here'" >/dev/null

"$ADB" shell am start -n com.ericflo.winnow/.ui.MainActivity >/dev/null
echo "Done. Winnow is open on the emulator with sample SMS, a tapback, group MMS (one with a subject and smart links),"
echo "a failed MMS download and an unclassified text from a stranger."
