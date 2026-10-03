"""Launcher regressions; generated evidence is retained under build/launcher-tests."""
import contextlib
import io
import os
from pathlib import Path
import socket
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import run_live_acceptance as launcher


class LauncherTests(unittest.TestCase):
    def setUp(self):
        parent = launcher.ROOT / "build/launcher-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="case-", dir=parent))

    def test_occupied_port_is_rejected_without_stopping_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            with patch.object(launcher, "PORT", listener.getsockname()[1]):
                with self.assertRaisesRegex(RuntimeError, "unavailable"):
                    launcher.assert_free_port()
                with socket.create_connection(listener.getsockname()):
                    pass

    def test_readiness_rejects_early_exit(self):
        server = Mock()
        server.poll.return_value = 7
        with self.assertRaisesRegex(RuntimeError, "exited before readiness"):
            launcher.wait_for_server(server, self.root / "out", self.root / "err")

    def test_foreign_listener_alone_cannot_admit_readiness(self):
        output = self.root / "stdout.log"
        output.write_text("Starting...", encoding="utf-8")
        server = Mock()
        server.poll.return_value = None
        with patch.object(launcher.time, "monotonic", side_effect=[0, 0, 2]), patch.object(launcher.time, "sleep"), \
                patch.object(launcher.socket, "create_connection") as connect:
            with self.assertRaisesRegex(RuntimeError, "not ready"):
                launcher.wait_for_server(server, output, self.root / "err", seconds=1)
            connect.assert_not_called()

    def test_own_startup_log_and_connection_admit_readiness(self):
        output = self.root / "stdout.log"
        output.write_text("Now listening on: http://127.0.0.1:5189\n", encoding="utf-8")
        server = Mock()
        server.poll.return_value = None
        with patch.object(launcher.socket, "create_connection") as connect:
            launcher.wait_for_server(server, output, self.root / "err")
            connect.assert_called_once()

    def test_structured_server_startup_log(self):
        record = '{"@mt":"Now listening on: {address}","address":"http://127.0.0.1:5189","SourceContext":"Microsoft.Hosting.Lifetime"}'
        self.assertTrue(launcher.listening_logged("Starting...\n" + record))
        self.assertFalse(launcher.listening_logged(record[:-3]))
        self.assertFalse(launcher.listening_logged(record.replace("5189", "5190")))
        self.assertFalse(launcher.listening_logged(record.replace("Microsoft.Hosting.Lifetime", "Foreign")))

    def exercise_main(self, failure=None, check_only=False):
        environment = dict(os.environ)
        cwd = Path.cwd()
        child = Mock()
        with patch.object(launcher, "ROOT", self.root), \
                patch.object(launcher, "preflight", return_value=(self.root, "dotnet", "emulator-5554", environment)), \
                patch.object(launcher, "capture", return_value="baseline"), \
                patch.object(launcher, "assert_free_port"), \
                patch.object(launcher, "run_logged", side_effect=[None, failure] if failure else None) as run, \
                patch.object(launcher.subprocess, "Popen", return_value=child) as popen, \
                patch.object(launcher, "wait_for_server"), \
                patch.object(launcher, "stop_process") as stop, contextlib.redirect_stdout(io.StringIO()):
            if failure:
                with self.assertRaises(type(failure)):
                    launcher.main([])
            else:
                launcher.main(["--check-only"] if check_only else [])
            if check_only:
                run.assert_not_called()
                popen.assert_not_called()
                stop.assert_not_called()
            else:
                stop.assert_called_once_with(child)
                self.assertEqual(popen.call_args.kwargs["env"]["DbProvider"], "Fake")
                self.assertEqual(run.call_args_list[-1].args[3], environment)
                self.assertIn("--live", run.call_args_list[-1].args[0])
        self.assertEqual(dict(os.environ), environment)
        self.assertEqual(Path.cwd(), cwd)

    def test_success_cleans_service_and_preserves_environment(self):
        self.exercise_main()

    def test_failure_cleans_service(self):
        self.exercise_main(RuntimeError("acceptance failed"))

    def test_interrupt_cleans_service(self):
        self.exercise_main(KeyboardInterrupt())

    def test_check_only_never_starts_service(self):
        self.exercise_main(check_only=True)

    @unittest.skipIf(os.name == "nt", "POSIX process groups")
    def test_real_child_exit_and_combined_log(self):
        log = self.root / "child.log"
        with self.assertRaisesRegex(RuntimeError, "exit code 7"):
            launcher.run_logged([sys.executable, "-c", "import sys; print('stdout'); print('stderr', file=sys.stderr); sys.exit(7)"],
                                log, self.root, dict(os.environ))
        self.assertIn("stdout", log.read_text())
        self.assertIn("stderr", log.read_text())

    @unittest.skipIf(os.name == "nt", "POSIX process groups")
    def test_real_owned_process_is_stopped(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        try:
            launcher.stop_process(child)
            self.assertIsNotNone(child.poll())
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()

    @unittest.skipIf(os.name == "nt", "Linux/macOS Bash integration")
    def test_bash_end_to_end_with_controlled_tools(self):
        try:
            launcher.assert_free_port()
        except RuntimeError:
            self.skipTest("The fixed live-service port is already in use")
        sdk_root = self.root / "sdk checkout"
        tools = sdk_root / "tools"
        tools.mkdir(parents=True)
        for name in ("run-live-acceptance.sh", "run_live_acceptance.py"):
            shutil.copyfile(launcher.ROOT / "tools" / name, tools / name)

        def script(path, body):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"#!{sys.executable}\n" + body, encoding="utf-8")
            path.chmod(0o755)

        java = self.root / "jdk/bin/java"
        script(java, "print('openjdk version \"17.0.1\"')\n")
        android = self.root / "android sdk"
        script(android / "platform-tools/adb", "import sys\nprint('emulator-5554 device' if sys.argv[-1] == 'devices' else '1')\n")
        for name in ("platforms/android-34/android.jar", "build-tools/34.0.0/apksigner", "build-tools/35.0.0/aapt2"):
            script(android / name, "")
        home = self.root / "home"
        script(home / ".android/debug.keystore", "")
        script(self.root / "featbit/modules/evaluation-server/src/Api/Api.csproj", "")
        dotnet = self.root / "bin/dotnet"
        script(dotnet, """import os, socket, sys, time
if '--list-sdks' in sys.argv:
    print('10.0.100 [controlled tool]')
elif 'build' not in sys.argv:
    assert os.environ['DbProvider'] == 'Fake'
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(('127.0.0.1', 5189))
        listener.listen()
        print('{"@mt":"Now listening on: {address}","address":"http://127.0.0.1:5189","SourceContext":"Microsoft.Hosting.Lifetime"}', flush=True)
        while True:
            connection, _ = listener.accept()
            connection.close()
""")
        script(self.root / "bin/git", "print('controlled baseline')\n")
        script(tools / "acceptance.py", """import os, sys
assert '--live' in sys.argv and 'emulator-5554' in sys.argv
assert os.environ['DbProvider'] == 'original'
print('controlled acceptance stdout')
print('controlled acceptance stderr', file=sys.stderr)
sys.exit(int(os.environ['CONTROLLED_EXIT']))
""")
        env = dict(os.environ, HOME=str(home), JAVA_HOME=str(java.parent.parent),
                   ANDROID_HOME=str(android), DOTNET_HOST_PATH=str(dotnet), PYTHON=sys.executable,
                   PATH=str(self.root / "bin") + os.pathsep + os.environ["PATH"], DbProvider="original")
        for code in (0, 7):
            env["CONTROLLED_EXIT"] = str(code)
            result = subprocess.run(["bash", str(tools / "run-live-acceptance.sh"), "--serial", "emulator-5554"],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0 if code == 0 else 1, result.stdout + result.stderr)
            self.assertIn("controlled acceptance stderr", result.stdout)
            self.assertIn("Stopped the test server", result.stdout)
            launcher.assert_free_port()


if __name__ == "__main__":
    unittest.main()
