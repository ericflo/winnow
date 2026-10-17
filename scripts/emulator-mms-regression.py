#!/usr/bin/env python3
"""MMS protocol/provider regression checks; requires a rooted emulator with debug Winnow installed.

Uses fictional messages and .invalid URLs. Does not exercise a live carrier's APN/MMSC.
Run after scripts/emulator-smoke.sh. Restores the carrier flag and deletes its own fixtures.
"""
import os
import json
import shlex
import shutil
import subprocess
import time
import uuid

adb = os.environ.get("ADB") or shutil.which("adb") or os.path.expanduser("~/Android/Sdk/platform-tools/adb")
package = "com.ericflo.winnow"
prefix = "mms-regression-" + uuid.uuid4().hex
location = "http://mmsc.invalid/get?message="


def command(*args):
    return subprocess.check_output([adb, *args], text=True).strip()


def shell(*args):
    return command("shell", shlex.join(str(arg) for arg in args))


def rows():
    # The provider deliberately hides NotificationInd rows from the shell's restricted view.
    # Read the emulator's DB directly; all writes still go through the provider/app.
    output = shell("sqlite3", "-cmd", ".timeout 5000", "-json", "/data/user/0/com.android.providers.telephony/databases/mmssms.db",
                   f"SELECT _id,tr_id,ct_l,exp,date,st,sub_id,thread_id FROM pdu WHERE tr_id LIKE '{prefix}%'",
                   )
    return {row["tr_id"]: {k: "NULL" if v is None else str(v) for k, v in row.items()}
            for row in json.loads(output or "[]")}


def eventually(check, description):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if check():
            print("PASS:", description, flush=True)
            return
        time.sleep(0.5)
    raise AssertionError(description + "\n" + repr(rows()))


def inject(suffix):
    shell("am", "broadcast", "-n", package + "/.debug.DebugMmsReceiver", "--es", "mode", "push",
          "--es", "id", prefix + suffix, "--es", "location", location, "--el", "expiry", "86400",
          "--es", "from", "+14155550191")


def insert(suffix, status, receipt):
    args = ["am", "broadcast", "-n", package + "/.debug.DebugMmsReceiver", "--es", "mode", "legacy",
            "--es", "id", prefix + suffix, "--es", "location", location, "--el", "expiry", "86400",
            "--el", "received", str(receipt)]
    if status is not None:
        args += ["--ei", "status", str(status)]
    shell(*args)
    eventually(lambda: prefix + suffix in rows(), "legacy fixture stored: " + suffix)


devices = [line.split()[0] for line in command("devices").splitlines()[1:] if "\tdevice" in line]
assert len(devices) == 1 and devices[0].startswith("emulator-"), "Attach exactly one emulator."
assert shell("getprop", "ro.kernel.qemu") == "1", "Refusing to use a real phone."
assert shell("id", "-u") == "0", "Run adb root on the emulator first."
assert shell("cmd", "role", "get-role-holders", "android.app.role.SMS") == package, "Make debug Winnow the emulator's SMS app first."
original = shell("cmd", "phone", "cc", "get-value", "enabledTransID").split()[-1]
assert original in ("true", "false"), original

try:
    shell("cmd", "phone", "cc", "set-value", "enabledTransID", "true")
    # CarrierConfig changes propagate to the MMS service asynchronously.
    time.sleep(2)
    inject("-a")
    inject("-b")
    eventually(lambda: all(prefix + suffix in rows() for suffix in ("-a", "-b")), "same incomplete URL yields two separate messages")
    for suffix in ("-a", "-b"):
        row = rows()[prefix + suffix]
        assert row["ct_l"] == location + prefix + suffix, row
        assert int(row["exp"]) == int(row["date"]) + 86400, row
    print("PASS: per-SIM enabledTransID and absolute expiry reach the system provider", flush=True)

    inject("-a")
    time.sleep(1)
    assert len(rows()) == 2, rows()
    first = rows()[prefix + "-a"]
    shell("am", "broadcast", "-n", package + "/.debug.DebugMmsReceiver", "--es", "mode", "retry", "--el", "message", first["_id"])
    assert rows()[prefix + "-a"]["ct_l"] == location + prefix + "-a"
    print("PASS: repeated push and retry preserve message identity", flush=True)

    receipt = int(time.time()) - 3600
    insert("-legacy", 135, receipt)
    insert("-deferred", 131, receipt)
    insert("-orphan", None, receipt)
    shell("am", "force-stop", package)
    shell("am", "start", "-n", package + "/.ui.MainActivity")
    eventually(lambda: all(rows()[prefix + suffix]["ct_l"] == location + prefix + suffix
                           for suffix in ("-legacy", "-deferred", "-orphan")), "startup repairs old incomplete URLs")
    for suffix in ("-legacy", "-deferred", "-orphan"):
        assert int(rows()[prefix + suffix]["exp"]) == receipt + 86400
    assert rows()[prefix + "-deferred"]["st"] == "131"
    print("PASS: legacy expiry uses original receipt time; deferred messages stay deferred", flush=True)
    eventually(lambda: rows()[prefix + "-orphan"]["st"] == "135", "orphaned download leaves the spinner and enters retry queue")

    shell("cmd", "phone", "cc", "set-value", "enabledTransID", "false")
    time.sleep(2)
    inject("-other-carrier")
    eventually(lambda: prefix + "-other-carrier" in rows(), "receive with carrier rule disabled")
    assert rows()[prefix + "-other-carrier"]["ct_l"] == location
    print("PASS: other carriers keep the announced URL", flush=True)
finally:
    shell("cmd", "phone", "cc", "set-value", "enabledTransID", original)
    shell("am", "broadcast", "-n", package + "/.debug.DebugMmsReceiver", "--es", "mode", "cleanup", "--es", "id", prefix)
