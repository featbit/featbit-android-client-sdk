"""Linux/macOS full live acceptance launcher. Requires an already booted emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PORT = 5189


def capture(command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True, timeout=30).strip()


def require_file(path):
    path = Path(path).expanduser().resolve()
    if not path.is_file():
        raise RuntimeError(f"Required file missing: {path}")
    return path


def executable(value):
    found = shutil.which(str(value))
    if not found:
        raise RuntimeError(f"Executable not found: {value}")
    return str(Path(found).absolute())


def assert_free_port():
    with socket.socket() as probe:
        try:
            if os.name != "nt":
                # Match the POSIX server: closed connections in TIME_WAIT are reusable.
                # Do not use SO_REUSEPORT or Windows SO_REUSEADDR (port sharing).
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", PORT))
            probe.listen()
        except OSError as error:
            raise RuntimeError(f"Port {PORT} is unavailable; stop the existing service yourself.") from error


def preflight(args):
    if sys.platform not in ("linux", "darwin"):
        raise RuntimeError("Use run-live-acceptance.ps1 on Windows; this launcher supports Linux/macOS.")
    if sys.version_info < (3, 8):
        raise RuntimeError("Python 3.8+ is required.")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?", args.version):
        raise RuntimeError("Invalid artifact version.")
    java_home = args.java_home
    if not java_home and sys.platform == "darwin":
        java_home = capture(["/usr/libexec/java_home", "-v", "17"])
    if not java_home:
        java_home = Path(executable("java")).resolve().parent.parent
    java_home = Path(java_home).expanduser().resolve()
    java = executable(java_home / "bin/java")
    if not re.search(r'version "17\.', capture([java, "-version"])):
        raise RuntimeError("JDK 17 is required; set JAVA_HOME or --java-home.")
    default_sdk = Path.home() / ("Library/Android/sdk" if sys.platform == "darwin" else "Android/Sdk")
    sdk = Path(args.android_home or default_sdk).expanduser().resolve()
    adb = executable(sdk / "platform-tools/adb")
    require_file(sdk / "platforms/android-34/android.jar")
    executable(sdk / "build-tools/34.0.0/apksigner")
    executable(sdk / "build-tools/35.0.0/aapt2")
    require_file(Path.home() / ".android/debug.keystore")
    local_dotnet = Path.home() / ".dotnet/dotnet"
    dotnet = executable(args.dotnet or (local_dotnet if local_dotnet.is_file() else "dotnet"))
    if not re.search(r"^10\.", capture([dotnet, "--list-sdks"]), re.MULTILINE):
        raise RuntimeError(".NET SDK 10 is required.")
    executable("git")
    executable("bash")
    server_root = ROOT.parent / "featbit/modules/evaluation-server"
    require_file(server_root / "src/Api/Api.csproj")
    ready = re.findall(r"^(emulator-\d+)\s+device\s*$", capture([adb, "devices"]), re.MULTILINE)
    serial = args.serial
    if not serial:
        if len(ready) != 1:
            raise RuntimeError("Start one emulator, or select it with --serial emulator-5554.")
        serial = ready[0]
    if serial not in ready:
        raise RuntimeError("The selected emulator is not connected and ready.")
    if capture([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"]) != "1":
        raise RuntimeError("Wait for the emulator to finish booting.")
    assert_free_port()
    env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), DOTNET_HOST_PATH=dotnet)
    print(f"JDK: {java_home}\nAndroid SDK: {sdk}\n.NET: {dotnet}\nEmulator: {serial}\nArtifact version: {args.version}", flush=True)
    return server_root, dotnet, serial, env


def stop_process(process, grace=10):
    """Only signal the session created by this launcher, never a port owner."""
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def run_logged(command, log, cwd, env, echo=False):
    with log.open("w", encoding="utf-8") as output:
        child = subprocess.Popen([str(p) for p in command], cwd=cwd, env=env,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, errors="replace", start_new_session=True)
        try:
            for line in child.stdout:
                output.write(line)
                output.flush()
                if echo:
                    print(line, end="", flush=True)
            code = child.wait()
            if code:
                raise RuntimeError(f"Command failed with exit code {code}; see {log}")
        except BaseException:
            # Let Python acceptance execute its device cleanup on interruption first.
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGINT)
                try:
                    child.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    pass
            raise
        finally:
            stop_process(child)
            child.stdout.close()


def wait_for_server(server, stdout_log, stderr_log, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError(f"Test server exited before readiness; see {stdout_log} and {stderr_log}")
        # Only this process's startup log can admit readiness. A foreign listener
        # winning the preflight/start race must not be mistaken for our server.
        output = stdout_log.read_text(encoding="utf-8", errors="replace")
        if listening_logged(output):
            try:
                with socket.create_connection(("127.0.0.1", PORT), timeout=1):
                    if server.poll() is None:
                        return
            except OSError:
                pass
        time.sleep(.25)
    raise RuntimeError(f"Test server was not ready within {seconds} seconds; see {stdout_log} and {stderr_log}")


def listening_logged(output):
    if re.search(r"Now listening on:\s*http://127\.0\.0\.1:5189\b", output):
        return True
    for line in output.splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue  # Includes a partially written last line.
        if isinstance(entry, dict) and entry.get("SourceContext") == "Microsoft.Hosting.Lifetime" \
                and entry.get("@mt") == "Now listening on: {address}" \
                and entry.get("address") == "http://127.0.0.1:5189":
            return True
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-home", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--dotnet", default=os.environ.get("DOTNET_HOST_PATH"))
    parser.add_argument("--version", default="0.1.0-SNAPSHOT")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args(argv)
    server_root, dotnet, serial, env = preflight(args)
    if args.check_only:
        print("Preflight passed. No server was started and no matrix was run.")
        return
    run = ROOT / "build/live-acceptance" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    print(f"Launcher logs: {run}", flush=True)
    print(capture(["git", "-C", str(ROOT), "rev-parse", "HEAD"]), flush=True)
    print(capture(["git", "-C", str(ROOT), "status", "--short"]), flush=True)
    server_output = run / "server"
    run_logged([dotnet, "build", server_root / "src/Api/Api.csproj", "-c", "Debug", "-o", server_output, "--nologo"],
               run / "server-build.log", ROOT, env)
    assert_free_port()
    service_env = dict(env, DbProvider="Fake", MqProvider="None", CacheProvider="None",
                       ASPNETCORE_URLS="http://127.0.0.1:5189")
    # The dotted logging category must remain a single environment key.
    service_env["Logging__LogLevel__Microsoft.Hosting.Lifetime"] = "Information"
    service_env["Logging__Console__LogLevel__Microsoft.Hosting.Lifetime"] = "Information"
    stdout_log, stderr_log = run / "server-stdout.log", run / "server-stderr.log"
    with stdout_log.open("w") as stdout, stderr_log.open("w") as stderr:
        server = subprocess.Popen([dotnet, str(server_output / "Api.dll")], cwd=server_root / "src/Api",
                                  env=service_env, stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            wait_for_server(server, stdout_log, stderr_log)
            print("Running the complete live and emulator matrix...", flush=True)
            run_logged([sys.executable, "-u", "-B", ROOT / "tools/acceptance.py", "--live", "--serial", serial,
                        "--version", args.version], run / "acceptance-console.log", ROOT, env, echo=True)
        finally:
            stop_process(server)
            print("Stopped the test server started by this script.", flush=True)
    print("Full live and emulator acceptance passed. Evidence directory is printed above.")


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except KeyboardInterrupt:
        print("Acceptance interrupted; child process cleanup attempted.", file=sys.stderr)
        sys.exit(130)
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
