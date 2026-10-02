"""Run existing AAR consumer APKs against the explicitly started Fake/None target service.

Use after phase7_acceptance.py --serial. Does not build, start the service, or publish.
Requires both PHASE4_PASS and PHASE5_PASS from each installed APK's new process.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--rows", nargs="+", help="Optional subset of already built rows")
    parser.add_argument("--phases", nargs="+", type=int, choices=(0, 4, 5), default=[4, 5], help="0 reruns local runtime; 4 synchronization; 5 events")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Requires an explicit emulator")
    evidence = args.evidence.resolve()
    report = json.loads((evidence / "report.json").read_text())
    if args.rows:
        report["rows"] = {row: report["rows"][row] for row in args.rows}
    if not report.get("rows") or any(row.get("build") != "passed" for row in report["rows"].values()):
        raise RuntimeError("Evidence must contain successfully built consumer rows")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    base = [str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")), "-s", args.serial]
    results = {"serial": args.serial, "rows": list(report["rows"]), "phases": args.phases, "checks": [], "result": "running"}
    output = evidence / "target-device-runs" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    print("Target evidence: " + str(output), flush=True)

    def adb(*parts):
        return subprocess.check_output([*base, *map(str, parts)], text=True, encoding="utf-8", errors="replace", timeout=30)

    mapping = adb("reverse", "--list")
    owned = "tcp:5189" not in mapping
    if not owned and not any(line.split()[-2:] == ["tcp:5189", "tcp:5189"] for line in mapping.splitlines()):
        raise RuntimeError("Port 5189 already has a different reverse mapping")
    try:
        if owned:
            adb("reverse", "--no-rebind", "tcp:5189", "tcp:5189")
        for row, details in report["rows"].items():
            language = details["language"]
            package = "co.featbit.consumer." + language
            for variant in ("debug", "release"):
                apk = evidence / row / language / f"build/outputs/apk/debug/{language}-debug.apk" if variant == "debug" else evidence / f"{row}-release-test-signed.apk"
                adb("install", "-r", apk)
                for phase in args.phases:
                    label = f"{row}-{variant}-target-phase{phase}"
                    print(label, flush=True)
                    adb("shell", "am", "force-stop", package)
                    adb("shell", "am", "start", "-W", "-n", package + "/.SmokeActivity", *(["--ez", f"phase{phase}", "true"] if phase else []))
                    pid = adb("shell", "pidof", package).strip()
                    if not pid.isdigit():
                        raise RuntimeError(label + " did not start")
                    deadline = time.monotonic() + 90
                    while time.monotonic() < deadline:
                        logs = adb("logcat", "-d", f"--pid={pid}", "-s", "FeatBitConsumer:I", "AndroidRuntime:E")
                        (output / (label + ".log")).write_text(logs, encoding="utf-8")
                        if "FAIL" in logs or "FATAL EXCEPTION" in logs:
                            (output / (label + "-system.log")).write_text(adb("logcat", "-d", f"--pid={pid}"), encoding="utf-8")
                            raise RuntimeError(label + " failed")
                        marker = f"PHASE{phase}_PASS" if phase else f"PASS · {language.capitalize()} runtime"
                        if marker in logs:
                            results["checks"].append({"name": label, "result": "passed"})
                            break
                        time.sleep(1)
                    else:
                        (output / (label + "-system.log")).write_text(adb("logcat", "-d", f"--pid={pid}"), encoding="utf-8")
                        raise RuntimeError(label + " timed out")
        results["result"] = "passed"
    except Exception as error:
        results["result"] = "failed"
        results["error"] = str(error)
        raise
    finally:
        if owned:
            adb("reverse", "--remove", "tcp:5189")
        (output / "report.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
