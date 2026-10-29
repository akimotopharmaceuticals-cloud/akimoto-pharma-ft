#!/usr/bin/env python3
"""
Akimoto Pharma FT · emulator QA

Drives the real APK on an Android emulator the way the android-emulator-qa skill does:
uiautomator XML dump -> pick the node -> tap the centre of its bounds -> screenshot -> check crash logs.
Every screen and the main MR flow is exercised. The run fails if the app crashes, freezes (ANR)
or a required step does not reach the expected screen.

Usage:  python3 qa/emulator_qa.py --apk path/to/app.apk --out qa-output
Needs:  adb on PATH and one booted emulator.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.akimoto.fieldtrack"
LAT, LNG = 26.8467, 80.9462          # Hazratganj, Lucknow

OUT = "qa-output"
RESULTS = []                          # (step, status, detail)  status: PASS / WARN / FAIL
_shot = 0
_screen = (1080, 2400)


class Warn(Exception):
    """A check that could not be completed because of an emulator limitation, not an app fault."""


# ---------------------------------------------------------------- adb helpers

def adb(*args, timeout=60, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def screenshot(name):
    global _shot
    _shot += 1
    safe = re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-").lower()
    path = os.path.join(OUT, "screens", f"{_shot:02d}-{safe}.png")
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
    if data:
        with open(path, "wb") as f:
            f.write(data)
    return path


def parse_dump(raw):
    i, j = raw.find("<?xml"), raw.rfind("</hierarchy>")
    if i < 0 or j < 0:
        return None
    try:
        return ET.fromstring(raw[i:j + len("</hierarchy>")])
    except ET.ParseError:
        return None


def dump():
    for _ in range(4):
        root = parse_dump(adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=40))
        if root is not None:
            return root
        time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def nodes(root):
    return list(root.iter("node"))


def full_id(rid):
    return rid if ":" in rid else f"{PKG}:id/{rid}"


def find(root, rid=None, text=None, contains=None):
    """Exact resource-id, exact text/content-desc (case-insensitive), or text containing a phrase."""
    for n in nodes(root):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if rid and n.get("resource-id") == full_id(rid):
            return n
        if text and (t.lower() == text.lower() or d.lower() == text.lower()):
            return n
        if contains and contains.lower() in t.lower():
            return n
    return None


def center(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    sh(f"input tap {x} {y}")
    time.sleep(1.2)


def text_of(root, rid):
    n = find(root, rid=rid)
    return (n.get("text") or "") if n is not None else ""


def keyboard_shown():
    return "mInputShown=true" in sh("dumpsys input_method")


def hide_keyboard():
    if keyboard_shown():
        sh("input keyevent 4")
        time.sleep(0.8)


def swipe(up=True):
    w, h = _screen
    x = w // 2
    if up:   # move content up = reveal what is below
        sh(f"input swipe {x} {int(h * 0.72)} {x} {int(h * 0.32)} 350")
    else:
        sh(f"input swipe {x} {int(h * 0.32)} {x} {int(h * 0.72)} 350")
    time.sleep(0.9)


def wait_for(rid=None, text=None, contains=None, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        n = find(root, rid, text, contains)
        if n is not None:
            return root, n
        time.sleep(1)
    return None, None


def find_scrolling(rid=None, text=None, contains=None):
    """Look on screen, then scroll down, then back up — ScrollView children off screen are not in the dump."""
    hide_keyboard()
    root = dump()
    n = find(root, rid, text, contains)
    if n is not None:
        return root, n
    for up in (True, False):
        for _ in range(6):
            swipe(up)
            root = dump()
            n = find(root, rid, text, contains)
            if n is not None:
                return root, n
    return None, None


def tap_scrolling(rid=None, text=None, contains=None, what=""):
    _, n = find_scrolling(rid, text, contains)
    if n is None:
        raise AssertionError(f"could not find {what or rid or text or contains}")
    tap(n)
    return n


def open_screen(button_rid, expect_rid, what):
    """Tap a home-screen button and wait for the new screen; tap once more if the first tap was lost
    (e.g. it landed while the page was still scrolling or a notification banner was showing)."""
    for attempt in range(2):
        time.sleep(1)                                    # let any scroll fling settle
        tap_scrolling(rid=button_rid, what=what)
        _, n = wait_for(rid=expect_rid, timeout=12)
        if n is not None:
            return n
    raise AssertionError(f"{what} screen did not open")


def type_into(rid, value):
    _, n = find_scrolling(rid=rid)
    if n is None:
        raise AssertionError(f"field {rid} not found")
    tap(n)
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(len(n.get("text") or "") + 2):
        sh("input keyevent KEYCODE_DEL")
    sh("input text " + value.replace(" ", "%s"))
    time.sleep(0.6)


def focus():
    out = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    return out


def app_alive():
    return sh(f"pidof {PKG}").strip() != ""


def crash_text():
    """Crashes and freezes of OUR app only (other apps, e.g. the camera, are ignored)."""
    found = []
    block = []
    for line in adb("logcat", "-b", "crash", "-d").splitlines():
        if "FATAL EXCEPTION" in line:
            if any(f"Process: {PKG}" in b for b in block):
                found += block
            block = [line]
        elif block:
            block.append(line)
    if any(f"Process: {PKG}" in b for b in block):
        found += block
    for line in adb("logcat", "-d", "-s", "ActivityManager:E").splitlines():
        if f"ANR in {PKG}" in line:
            found.append(line)
    return "\n".join(found[:60])


def screen_texts():
    """What is visible right now, for failure reports: window in focus + visible texts and ids."""
    root = dump()
    bits = []
    for n in nodes(root):
        t = (n.get("text") or "").strip().replace("\n", " / ")
        rid = (n.get("resource-id") or "").split("/")[-1]
        if t or rid.startswith(("bt", "et", "tv", "button")):
            bits.append(f"{rid}={t[:60]!r}" if rid else repr(t[:60]))
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]+)\}", focus())
    return f"[focus {m.group(1) if m else '?'}] " + ", ".join(bits[:30])


def annotate(level, title, msg):
    """GitHub annotation: readable on the run page and through the API without downloading logs."""
    msg = msg.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::{level} title={title}::{msg}", flush=True)


def tracking_service_running():
    return "TrackingService" in sh(f"dumpsys activity services {PKG}")


# ---------------------------------------------------------------- step runner

def step(name, fn, required=True):
    print(f"\n▶ {name}", flush=True)
    status, detail = "PASS", ""
    try:
        detail = fn() or ""
    except Warn as w:
        status, detail = "WARN", str(w)
    except Exception as e:  # noqa: BLE001 – any failure is a QA failure
        status, detail = ("FAIL" if required else "WARN"), f"{type(e).__name__}: {e}"
    crashes = crash_text()
    if crashes:
        status, detail = "FAIL", (detail + " | APP CRASH:\n" + crashes[-1500:]).strip(" |")
    if status != "PASS":
        detail += " | ON SCREEN: " + screen_texts()
    shot = screenshot(name)
    RESULTS.append((name, status, detail, shot))
    print(f"  {status} {detail}", flush=True)
    return status != "FAIL"


# ---------------------------------------------------------------- the QA flow

def prepare(apk):
    adb("wait-for-device", timeout=300)
    for _ in range(120):
        if sh("getprop sys.boot_completed").strip() == "1":
            break
        time.sleep(2)
    global _screen
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    if m:
        _screen = (int(m.group(1)), int(m.group(2)))
    sh("input keyevent 82")                                  # unlock
    sh("settings put system screen_off_timeout 1800000")
    sh("svc power stayon true")
    sh("cmd location set-location-enabled true")
    adb("emu", "geo", "fix", str(LNG), str(LAT))
    sh(f"pm uninstall {PKG}")
    out = adb("install", "-r", "-g", apk, timeout=240)
    if "Success" not in out:
        raise AssertionError("install failed: " + out.strip()[-400:])
    adb("logcat", "-b", "all", "-c")
    return f"installed on {_screen[0]}x{_screen[1]} emulator"


def launch():
    sh(f"am start -W -n {PKG}/.MainActivity", timeout=90)
    _, n = wait_for(rid="etName", timeout=25)
    if n is None:
        raise AssertionError("setup screen did not appear")
    return "setup screen shown on first launch"


def setup():
    type_into("etName", "QA Tester")
    type_into("etCode", "qa-001")
    tap_scrolling(rid="cbConsent", what="consent checkbox")
    tap_scrolling(rid="btnSaveSetup", what="Save and continue")
    _, n = wait_for(rid="tvName", timeout=15)
    if n is None:
        raise AssertionError("home screen did not open after setup")
    if "QA-001" not in (n.get("text") or ""):
        raise AssertionError(f"home header shows '{n.get('text')}', expected the employee code QA-001")
    return n.get("text")


def ours_on_screen():
    return any(n.get("package") == PKG for n in nodes(dump()))


def rotate_check(rid):
    sh("settings put system accelerometer_rotation 0")
    sh("settings put system user_rotation 1")
    time.sleep(3)
    ok_landscape = app_alive() and ours_on_screen()
    sh("settings put system user_rotation 0")
    time.sleep(2.5)
    _, n = find_scrolling(rid=rid)
    if not ok_landscape or n is None:
        raise AssertionError("screen did not survive rotation")
    return "survives rotation"


def scroll_top():
    hide_keyboard()
    for _ in range(4):
        swipe(up=False)


def start_day():
    tap_scrolling(rid="btnDay", what="Start day")
    saw_camera = False
    end = time.time() + 60
    while time.time() < end:
        adb("emu", "geo", "fix", str(LNG), str(LAT))
        root = dump()
        status = text_of(root, "tvStatus")
        if "tracking ON" in status:
            # The battery dialog appears right after the day opens; dismiss it if present.
            if find(root, contains="Keep tracking reliable") is not None:
                b = find(root, rid="android:id/button2")
                if b is not None:
                    tap(b)
            break
        btn = find(root, rid="android:id/button2")
        if btn is not None and (btn.get("text") or "").lower().startswith("start without"):
            tap(btn)                                       # no selfie in the emulator: start without
            continue
        if btn is not None and find(root, contains="Keep tracking reliable") is not None:
            tap(btn)
            continue
        if PKG not in focus():
            saw_camera = True                              # camera app is open: go back without a photo
            time.sleep(2)
            sh("input keyevent 4")
            time.sleep(1.5)
            continue
        time.sleep(1.5)
    root, n = wait_for(contains="tracking ON", timeout=20)
    if n is None:
        raise AssertionError("day did not start (status never showed 'tracking ON')")
    # dismiss battery dialog if it came late
    root = dump()
    if find(root, contains="Keep tracking reliable") is not None:
        b = find(root, rid="android:id/button2")
        if b is not None:
            tap(b)
    time.sleep(2)
    if not tracking_service_running():
        raise AssertionError("day started but the tracking service is not running")
    return "day started, tracking service running" + (" (camera opened, skipped selfie)" if saw_camera else "")


def doctor_visit():
    open_screen("btnVisit", "etDoctor", "Check-in")
    got_fix = False
    for _ in range(30):
        adb("emu", "geo", "fix", str(LNG), str(LAT))
        root = dump()
        b = find(root, rid="btnSaveVisit")
        if b is not None and b.get("enabled") == "true":
            got_fix = True
            break
        r = find(root, rid="btnRetryGps")
        if r is not None and "No GPS fix" in text_of(root, "tvGps"):
            tap(r)
        time.sleep(2)
    if not got_fix:
        sh("input keyevent 4")
        raise Warn("emulator gave no GPS fix within 60 s; check-in screen opened but could not be saved")
    type_into("etDoctor", "Dr QA Sharma")
    type_into("etSpeciality", "Radiation Oncology")
    type_into("etArea", "Hazratganj")
    type_into("etProducts", "Motodol Mouthwash")
    type_into("etRx", "5")
    tap_scrolling(rid="btnSaveVisit", what="Check in and save report")
    _, n = wait_for(rid="tvName", timeout=10)
    if n is None:
        raise AssertionError("did not return to home after saving the call")
    _, v = find_scrolling(contains="Dr QA Sharma")
    if v is None:
        raise AssertionError("saved call does not appear under 'Calls reported today'")
    return "call saved and listed"


def location_off_warning():
    scroll_top()
    sh("cmd location set-location-enabled false")
    try:
        _, n = wait_for(contains="Location is OFF", timeout=25)
        if n is None:
            raise Warn("location-off banner did not appear within 25 s")
        notif = "Location is OFF" in sh("dumpsys notification --noredact")
        return "banner shown" + (" and alert notification posted" if notif else "")
    finally:
        sh("cmd location set-location-enabled true")
        adb("emu", "geo", "fix", str(LNG), str(LAT))
        time.sleep(2)


def tour_plan():
    open_screen("btnTour", "tvTourStatus", "Tour plan")
    _, day = find_scrolling(contains="tap to plan")
    detail = "screen opened"
    if day is not None:
        tap(day)
        _, item = wait_for(text="Meeting at HQ", timeout=6)
        if item is None:
            raise AssertionError("area picker did not open when tapping a day")
        tap(item)
        _, saved = find_scrolling(contains="Meeting at HQ")
        if saved is None:
            raise AssertionError("chosen area not shown on the day")
        detail = "area picked and saved for a day"
    rotate_check("tvTourStatus")
    sh("input keyevent 4")
    _, home = wait_for(rid="tvName", timeout=8)
    if home is None:
        raise AssertionError("Back did not return to home")
    return detail + "; survives rotation"


def expenses_while_open():
    open_screen("btnExpenses", "tvNoDays", "Expenses")
    sh("input keyevent 4")
    wait_for(rid="tvName", timeout=8)
    return "expenses screen opened"


def settings_screen():
    n = open_screen("btnSettings", "etName", "Settings")
    if "QA Tester" not in (n.get("text") or ""):
        raise AssertionError("settings did not keep the saved name")
    _, v = find_scrolling(rid="tvVersion")
    ver = (v.get("text") if v is not None else "")
    sh("input keyevent 4")
    wait_for(rid="tvName", timeout=8)
    return ver or "settings opened"


def end_day():
    tap_scrolling(rid="btnDay", what="End day")
    _, ok = wait_for(rid="android:id/button1", timeout=8)
    if ok is None:
        raise AssertionError("end-day confirmation did not appear")
    tap(ok)
    scroll_top()
    _, n = wait_for(contains="tracking OFF", timeout=12)
    if n is None:
        raise AssertionError("day did not end")
    time.sleep(3)
    if tracking_service_running():
        raise AssertionError("tracking service still running after End day")
    return "day ended, tracking stopped"


def claim_expense():
    open_screen("btnExpenses", "spDay", "Expenses")
    _, b = find_scrolling(rid="btnSubmitClaim")
    if b is None:
        raise AssertionError("finished day not offered for a claim")
    type_into("etOther", "40")
    type_into("etOtherNote", "Parking")
    tap_scrolling(rid="btnSubmitClaim", what="Submit claim")
    _, ok = wait_for(rid="android:id/button1", timeout=8)
    if ok is None:
        raise AssertionError("claim confirmation did not appear")
    tap(ok)
    _, p = find_scrolling(contains="Pending")
    if p is None:
        raise AssertionError("submitted claim not listed as Pending")
    sh("input keyevent 4")
    wait_for(rid="tvName", timeout=8)
    return "claim submitted and listed as Pending"


def export_csv():
    tap_scrolling(rid="btnExport", what="Export CSV")
    time.sleep(3)
    f = focus()
    sh("input keyevent 4")
    time.sleep(1.5)
    if "Chooser" not in f and "Resolver" not in f and "intentresolver" not in f.lower():
        raise Warn("share sheet not detected (focus: " + f.strip().replace("\n", " ")[:150] + ")")
    return "share sheet opened with the day's CSV"


def relaunch_keeps_data():
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    sh(f"am start -W -n {PKG}/.MainActivity", timeout=90)
    _, n = wait_for(rid="tvName", timeout=20)
    if n is None:
        raise AssertionError("after restart the app did not open the home screen (data lost?)")
    _, v = find_scrolling(contains="Claims waiting: 1")
    return "data kept after force-stop" + ("" if v is not None else " (claim count line not found)")


def write_report():
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", timeout=60))
    with open(os.path.join(OUT, "crash.txt"), "w") as f:
        f.write(adb("logcat", "-b", "crash", "-d"))
    icon = {"PASS": "✅", "WARN": "⚠️", "FAIL": "❌"}
    lines = ["# Akimoto Pharma FT · emulator QA", "",
             "| # | Step | Result | Detail |", "|---|---|---|---|"]
    for i, (name, status, detail, _shot_path) in enumerate(RESULTS, 1):
        d = detail.replace("\n", " ").replace("|", "/")[:300]
        lines.append(f"| {i} | {name} | {icon[status]} {status} | {d} |")
    fails = sum(1 for r in RESULTS if r[1] == "FAIL")
    warns = sum(1 for r in RESULTS if r[1] == "WARN")
    lines += ["", f"**{len(RESULTS) - fails - warns} passed, {warns} warnings, {fails} failed.** "
              "Screenshots, full logcat and crash buffer are in the `qa-screenshots-and-logs` artifact."]
    text = "\n".join(lines) + "\n"
    with open(os.path.join(OUT, "summary.md"), "w") as f:
        f.write(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(text)
    print("\n" + text)
    annotate("notice", "QA summary", text)
    for name, status, detail, _ in RESULTS:
        if status == "FAIL":
            annotate("error", f"QA failed: {name}", detail[:3000])
        elif status == "WARN":
            annotate("warning", f"QA warning: {name}", detail[:1500])
    return fails


def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="qa-output")
    a = ap.parse_args()
    OUT = a.out
    os.makedirs(os.path.join(OUT, "screens"), exist_ok=True)

    ok = step("Install APK on emulator", lambda: prepare(a.apk))
    if ok:
        ok = step("First launch shows setup", launch)
    if ok:
        ok = step("Setup and consent", setup)
    if ok:
        step("Home screen survives rotation", lambda: rotate_check("tvName"))
        ok = step("Start day (selfie skipped, tracking on)", start_day)
    if ok:
        step("Doctor check-in and call report", doctor_visit, required=False)
        step("Location-off warning", location_off_warning, required=False)
        step("Tour plan", tour_plan)
        step("Expenses screen during open day", expenses_while_open)
        step("Settings screen", settings_screen)
        ok = step("End day", end_day)
    if ok:
        step("Submit expense claim", claim_expense)
        step("Export CSV share sheet", export_csv, required=False)
        step("Restart keeps data", relaunch_keeps_data)
    if not app_alive():
        sh(f"am start -W -n {PKG}/.MainActivity", timeout=60)
    final = crash_text()
    RESULTS.append(("No crashes or freezes in the whole run", "FAIL" if final else "PASS",
                    final[-1500:] if final else "crash buffer empty, no ANR", ""))
    fails = write_report()
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
