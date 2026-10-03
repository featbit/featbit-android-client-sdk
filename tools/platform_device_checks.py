"""Installed-AAR platform checks against an isolated HTTP fixture (not a server conformance test).

Requires the Kotlin consumer APK built with -Pphase6Probe=true and installed on an emulator.
Ordinary builds do not register the probe components. Changes network/rotation/idle
settings temporarily and restores them in finally. Does not install, erase data or publish.
Reuses a matching reverse mapping, rejects conflicts, and removes only a mapping it created.
"""
import argparse
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This fixture changes emulator settings; physical devices require a separate controlled run.")
package = "co.featbit.consumer.kotlin"
polls, events = [], []
lock = threading.Lock()
delay_events = False


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with lock:
            if "/latest-all" in self.path:
                polls.append(body)
                response = {"messageType": "data-sync", "data": {"eventType": "full",
                    "userKeyId": body["keyId"], "featureFlags": [{"id": "phase6", "variation": "remote",
                    "variationType": "string", "timestamp": len(polls)}]}}
            else:
                events.append(body)
                response = {}
        if "/latest-all" not in self.path and delay_events:
            time.sleep(3)
        encoded = json.dumps(response).encode()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


def adb(*parts):
    result = subprocess.run([args.adb, "-s", args.serial, *parts], capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout


def command(action, **extras):
    parts = ["shell", "am", "broadcast", "--receiver-foreground", "--include-stopped-packages", "-n", package + "/.LifecycleProbeReceiver", "--es", "command", action]
    for key, value in extras.items():
        parts += ["--ez" if isinstance(value, bool) else "--es", key, str(value).lower() if isinstance(value, bool) else str(value)]
    output = adb(*parts)
    assert "Broadcast completed" in output, output


def logs():
    values = []
    for line in adb("logcat", "-d", "-s", "FeatBitPhase6:I", "*:S", "-v", "raw").splitlines():
        if line.startswith("{"):
            values.append(json.loads(line))
    return values


def until(check, description, seconds=12):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.2)
    raise AssertionError("Timed out: " + description)


def snapshot(**expected):
    token = str(time.monotonic_ns())
    command("snapshot", token=token)
    values = [v for v in logs() if v["kind"] == "snapshot" and v["detail"] == token]
    return next((v for v in values if all(v.get(k) == value for k, value in expected.items())), None)


def state(**expected):
    return until(lambda: snapshot(**expected), str(expected))


def foreground():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", package + "/.LifecycleProbeActivity")
    time.sleep(.8)  # settle process STOP/START debounce before asserting the visible state
    state(background=False)


def home():
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    state(background=True)


def reset(**options):
    adb("shell", "am", "force-stop", package)
    time.sleep(.4)
    adb("logcat", "-c")
    command("create", **options)
    until(lambda: any(v["kind"] == "created" and v["detail"] == "SUCCESS" for v in logs()), "client creation")


def quiet(seconds=1.5, include_events=True):
    time.sleep(.25)  # allow a physically admitted request to reach the fixture
    counts = (len(polls), len(events))
    time.sleep(seconds)
    assert len(polls) == counts[0], ("unexpected polling", counts, len(polls))
    if include_events:
        assert len(events) == counts[1], "unexpected event delivery"


def passed(text):
    print("PASS " + text, flush=True)


def reverse_target():
    for line in adb("reverse", "--list").splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[-2] == "tcp:5196":
            return fields[-1]
    return None


existing_reverse = reverse_target()
if existing_reverse not in (None, "tcp:5196"):
    raise RuntimeError("Port 5196 already has a different reverse mapping; leaving it unchanged")
rotation = adb("shell", "settings", "get", "system", "user_rotation").strip()
auto_rotation = adb("shell", "settings", "get", "system", "accelerometer_rotation").strip()
wifi = adb("shell", "settings", "get", "global", "wifi_on").strip()
mobile = adb("shell", "settings", "get", "global", "mobile_data").strip()
screen_timeout = adb("shell", "settings", "get", "system", "screen_off_timeout").strip()
server = ThreadingHTTPServer(("127.0.0.1", 5196), Fixture)
threading.Thread(target=server.serve_forever, daemon=True).start()
owned_reverse = False
settings_started = False
try:
    if existing_reverse is None:
        adb("reverse", "--no-rebind", "tcp:5196", "tcp:5196")
        owned_reverse = True
    settings_started = True
    adb("shell", "settings", "put", "system", "screen_off_timeout", "600000")
    adb("shell", "svc", "wifi", "enable")
    adb("shell", "svc", "data", "enable")
    reset()
    state(background=True, confirmed=False)
    quiet()
    command("await")
    until(lambda: any(v["kind"] == "await" and v["detail"] == "TIMED_OUT" for v in logs()), "background readiness deadline")
    foreground(); state(confirmed=True, value="remote")
    passed("background creation, elapsed readiness timeout, foreground polling")

    before = snapshot()
    activity_count = sum(v["kind"] == "activity-created" for v in logs())
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "0" if rotation == "1" else "1")
    time.sleep(1)
    after = state(background=False, confirmed=True)
    assert before["pid"] == after["pid"] and before["instance"] == after["instance"]
    until(lambda: sum(v["kind"] == "activity-created" for v in logs()) > activity_count, "Activity recreation on rotation")
    passed("Activity rotation retains process client")

    adb("shell", "svc", "wifi", "disable")
    state(networkPaused=False)
    count = len(polls)
    until(lambda: len(polls) > count, "polling after Wi-Fi to cellular switch")
    adb("shell", "svc", "data", "disable")
    state(networkPaused=True); quiet()
    adb("shell", "svc", "wifi", "enable")
    state(networkPaused=False)
    count = len(polls); until(lambda: len(polls) > count, "network recovery")
    passed("Wi-Fi/cellular handover, total loss and network recovery")

    command("track", name="before-background")
    home(); quiet()
    command("track", name="background")
    command("track", name="background")
    foreground(); command("track", name="background"); command("flush")
    until(lambda: any(v["kind"] == "flush" and v["detail"].startswith("SUCCESS:") for v in logs()), "foreground event flush")
    with lock:
        names = [event.get("eventName") for batch in events for entry in batch for event in entry.get("metrics", [])]
    assert names.count("background") == 2, names
    passed("background event pause, deduplication and foreground group boundary")

    command("offline"); state(offline=True)
    home(); foreground(); quiet()
    command("online"); state(offline=False)
    count = len(polls); until(lambda: len(polls) > count, "online recovery")
    command("close")
    until(lambda: any(v["kind"] == "closed" and v["detail"] == "SUCCESS:true" for v in logs()), "clean close")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    adb("shell", "am", "start", "-n", package + "/.LifecycleProbeActivity")
    quiet()
    state(status="CLOSED")
    passed("offline/close survive platform recovery; observer cleanup")

    old_pid = snapshot()["pid"]
    count = len(polls)
    reset(background=True, disabled=True)
    current = state(background=True, confirmed=True)
    assert current["pid"] != old_pid
    assert len(polls) == count + 1
    # This checks successful background synchronization, not a one-second latency SLA.
    # Keep the explicit Doze timeout below at one second.
    command("identify", waitMillis="5000")
    until(lambda: any(v["kind"] == "identify" and v["detail"] == "SUCCESS" for v in logs()), "background identify")
    event_count = len(events); command("track"); command("flush")
    until(lambda: any(v["kind"] == "flush" and v["detail"].startswith("DISABLED:") for v in logs()), "disabled flush")
    assert len(events) == event_count
    passed("process recreation, background polling/Identify and disabled events")

    adb("shell", "dumpsys", "battery", "unplug")
    idle_result = adb("shell", "dumpsys", "deviceidle", "force-idle")
    assert "idle" in idle_result.lower(), idle_result
    assert "mState=IDLE" in adb("shell", "dumpsys", "deviceidle")
    quiet()
    adb("logcat", "-c")
    command("identify")
    until(lambda: any(v["kind"] == "identify" and v["detail"] == "TIMED_OUT" for v in logs()), "Doze Identify deadline")
    adb("shell", "dumpsys", "deviceidle", "unforce")
    foreground(); state(confirmed=True)
    count = len(polls); until(lambda: len(polls) > count, "post-idle foreground polling")
    passed("forced emulator Doze and resumption (not physical deep-sleep proof)")

    reset(transition=True)
    foreground(); state(confirmed=True)
    delay_events = True
    command("track", name="transition")
    event_count = len(events)
    home()
    until(lambda: len(events) > event_count, "transition flush dispatch")
    time.sleep(3.5)
    quiet(include_events=True)
    delay_events = False
    foreground(); command("flush")
    until(lambda: any(v["kind"] == "flush" and v["detail"].startswith("SUCCESS:") for v in logs()), "retained transition retry")
    passed("transition cutoff, late response isolation and retained-event retry")

    reset(local=True)
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    foreground(); state(value="local", networkPaused=False)
    command("close")
    until(lambda: any(v["kind"] == "closed" and v["detail"] == "SUCCESS:true" for v in logs()), "local close")
    passed("TestData remains independent of physical connectivity")
finally:
    cleanup = [
        ("shell", "dumpsys", "deviceidle", "unforce"),
        ("shell", "dumpsys", "battery", "reset"),
        ("shell", "svc", "wifi", "enable" if wifi != "0" else "disable"),
        ("shell", "svc", "data", "enable" if mobile != "0" else "disable"),
        ("shell", "settings", "put", "system", "accelerometer_rotation", auto_rotation),
        ("shell", "settings", "put", "system", "user_rotation", rotation),
        ("shell", "settings", "put", "system", "screen_off_timeout", screen_timeout),
        ("shell", "input", "keyevent", "KEYCODE_WAKEUP"),
        ("shell", "am", "force-stop", package),
    ] if settings_started else []
    cleanup_failures = []
    for operation in cleanup:
        try:
            adb(*operation)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            print("CLEANUP_FAILED " + " ".join(operation) + ": " + str(error), flush=True)
            cleanup_failures.append(operation)
    if owned_reverse:
        try:
            target = reverse_target()
            if target == "tcp:5196":
                adb("reverse", "--remove", "tcp:5196")
            elif target is not None:
                raise RuntimeError("Port 5196 mapping changed during the run; leaving it unchanged")
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            print("CLEANUP_FAILED reverse tcp:5196: " + str(error), flush=True)
            cleanup_failures.append(("reverse", "tcp:5196"))
    server.shutdown()
    server.server_close()
    if cleanup_failures:
        raise RuntimeError("Emulator cleanup incomplete; inspect CLEANUP_FAILED output")
print("PHASE6_DEVICE_PASS", flush=True)
