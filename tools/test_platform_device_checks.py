"""Exercise the real script's setup/finally paths without changing a device or binding a port."""
import contextlib
import io
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch


class ReverseMappingTest(unittest.TestCase):
    def run_fixture(self, initial=None, collision=False, replacement=None, remove_fails=False):
        mappings = dict(initial or {})
        calls = []
        fixture_started = False

        def run(argv, **kwargs):
            nonlocal fixture_started
            command = tuple(argv[3:])
            calls.append(command)
            output = "1\n"
            error = ""
            if command == ("reverse", "--list"):
                output = "\n".join(f"emulator-5554 {source} {target}" for source, target in mappings.items())
            elif command[:2] == ("reverse", "--no-rebind"):
                if collision:
                    mappings["tcp:5196"] = "tcp:7777"
                if command[2] in mappings:
                    error = "cannot rebind existing socket"
                else:
                    mappings[command[2]] = command[3]
            elif command[:2] == ("reverse", "--remove"):
                if remove_fails:
                    error = "remove failed"
                else:
                    mappings.pop(command[2], None)
            elif command[:3] == ("shell", "am", "force-stop") and not fixture_started:
                fixture_started = True
                if replacement:
                    mappings["tcp:5196"] = replacement
                error = "intentional fixture failure"
            return subprocess.CompletedProcess(argv, int(bool(error)), stdout=output, stderr=error)

        output = io.StringIO()
        with patch("subprocess.run", side_effect=run), patch("http.server.ThreadingHTTPServer"), \
                patch("threading.Thread"), patch("time.sleep"), \
                patch.object(sys, "argv", ["platform_device_checks.py"]), contextlib.redirect_stdout(output):
            with self.assertRaises(RuntimeError) as raised:
                runpy.run_path(str(Path(__file__).with_name("platform_device_checks.py")), run_name="__main__")
        return mappings, calls, str(raised.exception), output.getvalue()

    def test_new_mapping_is_removed_after_fixture_failure(self):
        mappings, calls, error, _ = self.run_fixture({"tcp:51960": "tcp:9000"})
        self.assertEqual({"tcp:51960": "tcp:9000"}, mappings)
        self.assertIn(("reverse", "--no-rebind", "tcp:5196", "tcp:5196"), calls)
        self.assertIn(("reverse", "--remove", "tcp:5196"), calls)
        self.assertEqual("intentional fixture failure", error)

    def test_matching_existing_mapping_is_reused_and_preserved(self):
        original = {"tcp:5196": "tcp:5196"}
        mappings, calls, _, _ = self.run_fixture(original)
        self.assertEqual(original, mappings)
        self.assertFalse(any(c[0] == "reverse" and c[1] != "--list" for c in calls))

    def test_conflicting_mapping_stops_before_device_changes(self):
        original = {"tcp:5196": "tcp:7777"}
        mappings, calls, error, _ = self.run_fixture(original)
        self.assertEqual(original, mappings)
        self.assertEqual([("reverse", "--list")], calls)
        self.assertIn("different reverse mapping", error)

    def test_no_rebind_closes_the_creation_race_without_cleanup_side_effects(self):
        mappings, calls, error, _ = self.run_fixture(collision=True)
        self.assertEqual({"tcp:5196": "tcp:7777"}, mappings)
        self.assertEqual("cannot rebind existing socket", error)
        self.assertNotIn(("reverse", "--remove", "tcp:5196"), calls)
        self.assertFalse(any(c[:3] == ("shell", "settings", "put") or c[:2] == ("shell", "svc") for c in calls))

    def test_replaced_mapping_is_not_removed(self):
        mappings, calls, error, output = self.run_fixture(replacement="tcp:7777")
        self.assertEqual({"tcp:5196": "tcp:7777"}, mappings)
        self.assertNotIn(("reverse", "--remove", "tcp:5196"), calls)
        self.assertIn("cleanup incomplete", error)
        self.assertIn("mapping changed", output)

    def test_removal_failure_is_reported(self):
        mappings, _, error, output = self.run_fixture(remove_fails=True)
        self.assertEqual({"tcp:5196": "tcp:5196"}, mappings)
        self.assertIn("cleanup incomplete", error)
        self.assertIn("remove failed", output)


if __name__ == "__main__":
    unittest.main()
