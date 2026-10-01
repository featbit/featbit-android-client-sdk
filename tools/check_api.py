"""Check the public JVM surface of the real release AAR. --update is an explicit baseline change."""
import argparse
import difflib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--update", action="store_true")
args = parser.parse_args()
aar = ROOT / "sdk/build/outputs/aar/sdk-release.aar"
baseline = ROOT / "sdk/api/public-api.txt"
javap = shutil.which("javap")
if not javap:
    javap = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("javap.exe" if os.name == "nt" else "javap"))

# Delete only this explicitly created temporary file; never recursively remove directories.
fd, temporary_jar = tempfile.mkstemp(prefix="featbit-api-", suffix=".jar")
os.close(fd)
jar = Path(temporary_jar)
try:
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    with zipfile.ZipFile(jar) as archive:
        classes = sorted(n[:-6].replace("/", ".") for n in archive.namelist()
                         if n.endswith(".class") and n.startswith("co/featbit/android/")
                         and not n.startswith("co/featbit/android/internal/")
                         and not n.endswith("/BuildConfig.class"))
        for name in archive.namelist():
            if name.endswith(".class"):
                data = archive.read(name)
                if int.from_bytes(data[6:8], "big") > 55:
                    raise SystemExit(f"Bytecode exceeds Java 11: {name}")
    output = subprocess.check_output([javap, "-public", "-classpath", str(jar), *classes], text=True)
    blocks = []
    current = []
    for line in output.splitlines():
        if line.startswith("Compiled from"):
            continue
        if line.startswith("public ") and line.endswith("{"):
            current = [line]
        elif current:
            current.append(line)
            if line == "}":
                blocks.append("\n".join(current))
                current = []
    actual = "\n\n".join(blocks) + "\n"
    if any(name in actual for name in ("okhttp3.", "kotlinx.serialization.", "androidx.lifecycle.", "co.featbit.android.internal.")):
        raise SystemExit("Implementation dependency leaked into public API")
    if args.update:
        baseline.parent.mkdir(parents=True, exist_ok=True)
        baseline.write_text(actual, encoding="utf-8", newline="\n")
        print("Updated API baseline; review its diff before committing.")
    else:
        expected = baseline.read_text(encoding="utf-8")
        if actual != expected:
            print("".join(difflib.unified_diff(expected.splitlines(True), actual.splitlines(True), fromfile="baseline", tofile="AAR")))
            raise SystemExit("Public API changed. Review and explicitly regenerate baseline.")
        print(f"API baseline and Java 11 bytecode verified ({len(blocks)} public types).")
finally:
    jar.unlink()
