"""Regression tests for the dependency-free Python launcher."""

from __future__ import annotations

import io
import re
import signal
import subprocess
import tempfile
import unittest
from contextlib import nullcontext, redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

import run


class OutputTestCase(unittest.TestCase):
    def setUp(self):
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()
        for context in (redirect_stdout(self.stdout), redirect_stderr(self.stderr)):
            context.__enter__()
            self.addCleanup(context.__exit__, None, None, None)


class RestartTests(OutputTestCase):
    def test_backend_port_matches_application_config(self):
        config = (run.PROJECT_ROOT / "src/main/resources/application.yaml").read_text(
            encoding="utf-8"
        )
        server = re.search(r"(?m)^server:\s*\n((?:[ \t]+[^\n]*\n)+)", config)
        self.assertIsNotNone(server)
        port = re.search(r"(?m)^\s+port:\s+(\d+)", server.group(1))
        self.assertIsNotNone(port)
        self.assertEqual(run.BACKEND_PORT, int(port.group(1)))

    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", return_value=False)
    def test_free_port_does_not_stop_anything(self, port_check, stop_process):
        self.assertTrue(run._stop_existing_server())
        port_check.assert_called_once_with(run.BACKEND_PORT)
        stop_process.assert_not_called()

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", side_effect=[True, False])
    def test_verified_old_instance_is_stopped_before_startup(self, port_check, stop_process):
        stop_process.return_value = subprocess.CompletedProcess([], 0, "Old instance stopped.\n", "")
        self.assertTrue(run._stop_existing_server())
        args = stop_process.call_args.args[0]
        self.assertIn(str(run.PROJECT_ROOT / "bin" / "stop-instance.ps1"), args)
        self.assertIn(str(run.PROJECT_ROOT), args)
        self.assertEqual(args[args.index("-BackendPort") + 1], str(run.BACKEND_PORT))
        self.assertEqual(stop_process.call_args.kwargs["timeout"], 45)
        self.assertIn("Old instance stopped", self.stdout.getvalue())
        self.assertEqual(self.stderr.getvalue(), "")

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", return_value=True)
    def test_foreign_process_or_stop_failure_aborts(self, port_check, stop_process):
        stop_process.return_value = subprocess.CompletedProcess([], 1, "", "Not a verified instance")
        self.assertFalse(run._stop_existing_server())
        self.assertIn("Not a verified instance", self.stderr.getvalue())
        self.assertIn("Restart aborted", self.stderr.getvalue())

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", return_value=True)
    def test_port_reoccupied_after_cleanup_aborts(self, port_check, stop_process):
        stop_process.return_value = subprocess.CompletedProcess([], 0, "", "")
        self.assertFalse(run._stop_existing_server())
        self.assertIn("still occupied", self.stderr.getvalue())

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", return_value=True)
    def test_missing_powershell_or_timeout_aborts(self, port_check, stop_process):
        for error in (FileNotFoundError("powershell.exe"), subprocess.TimeoutExpired("powershell.exe", 45)):
            with self.subTest(error=error):
                stop_process.side_effect = error
                self.assertFalse(run._stop_existing_server())

    @patch.object(run.os, "name", "posix")
    @patch.object(run.subprocess, "run")
    @patch.object(run, "_port_is_open", return_value=True)
    def test_unsupported_platform_never_kills_unverified_processes(self, port_check, stop_process):
        self.assertFalse(run._stop_existing_server())
        stop_process.assert_not_called()
        self.assertIn("manually", self.stderr.getvalue())


class LauncherTests(OutputTestCase):
    def setUp(self):
        super().setUp()
        for name, replacement in (
            ("_find_jdk21", MagicMock(return_value=run.PROJECT_ROOT / "fake-jdk-21")),
            ("_gradle_command", MagicMock(return_value=["gradlew", "bootRun"])),
            ("_stop_existing_server", MagicMock(return_value=True)),
            ("_start_debug_chrome", MagicMock()),
            ("_startup_lock", MagicMock(side_effect=nullcontext)),
            ("_port_is_open", MagicMock(return_value=True)),
            ("_stop_backend", MagicMock()),
        ):
            context = patch.object(run, name, replacement)
            context.start()
            self.addCleanup(context.stop)
            setattr(self, name, replacement)
        for target, name in ((run.Path, "is_file"), (run.subprocess, "Popen")):
            context = patch.object(target, name)
            mock = context.start()
            self.addCleanup(context.stop)
            setattr(self, "start_backend" if name == "Popen" else name, mock)
        self.is_file.return_value = True

    def test_missing_database_keeps_old_instance_running(self):
        self.is_file.return_value = False
        self.assertEqual(run.main(), 1)
        self._stop_existing_server.assert_not_called()
        self.start_backend.assert_not_called()

    def test_missing_jdk_keeps_old_instance_running(self):
        self._find_jdk21.return_value = None
        self.assertEqual(run.main(), 1)
        self._stop_existing_server.assert_not_called()
        self.start_backend.assert_not_called()

    def test_missing_gradle_keeps_old_instance_running(self):
        self._gradle_command.side_effect = FileNotFoundError("Gradle wrapper not found")
        self.assertEqual(run.main(), 1)
        self._stop_existing_server.assert_not_called()
        self.start_backend.assert_not_called()

    def test_failed_stop_does_not_start_chrome_or_gradle(self):
        self._stop_existing_server.return_value = False
        self.assertEqual(run.main(), 1)
        self._start_debug_chrome.assert_not_called()
        self.start_backend.assert_not_called()

    def test_restart_order_and_backend_exit_code(self):
        calls = []
        self._stop_existing_server.side_effect = lambda: calls.append("stop old") or True
        chrome = MagicMock()
        self._start_debug_chrome.side_effect = lambda: calls.append("start chrome") or chrome
        for exit_code in (0, 1):
            with self.subTest(exit_code=exit_code):
                calls.clear()
                chrome.reset_mock()
                backend = MagicMock()
                backend.poll.return_value = None
                backend.wait.return_value = exit_code
                self.start_backend.side_effect = lambda *args, **kwargs: calls.append("start backend") or backend
                self.assertEqual(run.main(), exit_code)
                self.assertEqual(calls, ["stop old", "start chrome", "start backend"])
                self.assertEqual(self.start_backend.call_args.kwargs["cwd"], run.PROJECT_ROOT)
                self.assertEqual(
                    self.start_backend.call_args.kwargs["env"]["JAVA_HOME"], str(self._find_jdk21.return_value)
                )
                chrome.terminate.assert_called_once_with()
                chrome.wait.assert_called_once_with(timeout=5)
                self._stop_backend.assert_called_with(backend)
                backend.wait.assert_called_once_with(timeout=0.5)

    def test_startup_lock_is_held_until_backend_listens(self):
        events = []
        lock = MagicMock()
        lock.__enter__.side_effect = lambda: events.append("lock")
        lock.__exit__.side_effect = lambda *args: events.append("unlock")
        self._startup_lock.side_effect = None
        self._startup_lock.return_value = lock
        backend = self.start_backend.return_value
        backend.poll.return_value = None
        backend.wait.side_effect = lambda **kwargs: events.append("wait") or 0
        self._port_is_open.side_effect = lambda port: events.append("port ready") or True
        self.assertEqual(run.main(), 0)
        self.assertEqual(events, ["lock", "port ready", "unlock", "wait"])

    def test_failed_backend_start_releases_lock_without_waiting_for_port(self):
        backend = self.start_backend.return_value
        backend.poll.return_value = 1
        backend.wait.return_value = 1
        self.assertEqual(run.main(), 1)
        self._port_is_open.assert_not_called()


    def test_stop_while_running_cleans_backend_and_chrome(self):
        backend = self.start_backend.return_value
        backend.poll.return_value = None
        backend.wait.side_effect = KeyboardInterrupt
        self.assertEqual(run.main(), 130)
        self._stop_backend.assert_called_once_with(backend)
        self._start_debug_chrome.return_value.terminate.assert_called_once_with()
        self.assertIn("Stopped.", self.stdout.getvalue())

    def test_stop_during_startup_cleans_spawned_backend(self):
        backend = self.start_backend.return_value
        backend.poll.return_value = None
        self._port_is_open.side_effect = KeyboardInterrupt
        self.assertEqual(run.main(), 130)
        self._stop_backend.assert_called_once_with(backend)
        backend.wait.assert_not_called()

    def test_timed_waits_keep_stop_signals_responsive(self):
        backend = self.start_backend.return_value
        backend.poll.return_value = None
        backend.wait.side_effect = [subprocess.TimeoutExpired("java", 0.5), 0]
        self.assertEqual(run.main(), 0)
        self.assertEqual(backend.wait.call_count, 2)
        for call in backend.wait.call_args_list:
            self.assertEqual(call.kwargs, {"timeout": 0.5})

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NEW_PROCESS_GROUP", 0x200, create=True)
    def test_windows_backend_has_its_own_process_group_and_no_shell(self):
        backend = self.start_backend.return_value
        backend.poll.return_value = 0
        backend.wait.return_value = 0
        self.assertEqual(run.main(), 0)
        self.assertEqual(self.start_backend.call_args.kwargs["creationflags"], 0x200)
        self.assertFalse(self.start_backend.call_args.kwargs.get("shell", False))


class GradleCommandTests(OutputTestCase):
    @patch.object(run.os, "name", "nt")
    @patch.object(run.Path, "is_file", return_value=True)
    def test_windows_launches_java_directly_without_a_batch_prompt(self, is_file):
        jdk = run.PROJECT_ROOT / "JDK with spaces"
        with patch.dict(run.os.environ, {
            "JAVA_OPTS": '-Dexample="value with spaces"',
            "GRADLE_OPTS": "-Xmx128m",
        }):
            command = run._gradle_command(jdk)
        self.assertTrue(command.startswith(subprocess.list2cmdline([str(jdk / "bin/java.exe")])))
        self.assertNotIn("cmd.exe", command)
        self.assertNotIn("gradlew.bat", command)
        self.assertIn("org.gradle.wrapper.GradleWrapperMain", command)
        self.assertIn("--no-daemon", command)
        self.assertIn("-Dsun.stdout.encoding=UTF-8", command)
        self.assertIn("-Dsun.stderr.encoding=UTF-8", command)
        self.assertIn('-Dexample="value with spaces"', command)
        self.assertIn("-Xmx128m", command)

    @patch.object(run.os, "name", "nt")
    @patch.object(run.Path, "is_file", return_value=False)
    def test_missing_wrapper_jar_fails_before_launch(self, is_file):
        with self.assertRaisesRegex(FileNotFoundError, "gradle-wrapper.jar"):
            run._gradle_command(run.PROJECT_ROOT / "jdk")

    @patch.object(run.os, "name", "posix")
    @patch.object(run.Path, "is_file", return_value=True)
    @patch.object(run.os, "access", return_value=True)
    def test_posix_keeps_native_wrapper(self, access, is_file):
        self.assertEqual(run._gradle_command(run.PROJECT_ROOT / "jdk"), [
            str(run.PROJECT_ROOT / "gradlew"), "bootRun", "--console=plain", "--no-daemon",
        ])


class StopTests(OutputTestCase):
    @patch.object(run.subprocess, "run")
    def test_absent_or_exited_backend_is_not_killed(self, kill):
        run._stop_backend(None)
        process = MagicMock()
        process.poll.return_value = 0
        run._stop_backend(process)
        kill.assert_not_called()
        process.terminate.assert_not_called()

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    def test_windows_cleanup_targets_only_this_launchs_tree(self, kill):
        process = MagicMock(pid=12345)
        process.poll.return_value = None
        kill.return_value = subprocess.CompletedProcess([], 0)
        run._stop_backend(process)
        self.assertEqual(kill.call_args.args[0], ["taskkill.exe", "/PID", "12345", "/T", "/F"])
        self.assertEqual(kill.call_args.kwargs["stdout"], subprocess.DEVNULL)
        self.assertEqual(kill.call_args.kwargs["stderr"], subprocess.DEVNULL)
        self.assertEqual(kill.call_args.kwargs["timeout"], 15)
        process.wait.assert_called_once_with(timeout=5)
        process.terminate.assert_not_called()

    @patch.object(run.os, "name", "nt")
    @patch.object(run.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True)
    @patch.object(run.subprocess, "run")
    def test_cleanup_failure_is_reported_without_preventing_browser_cleanup(self, kill):
        process = MagicMock(pid=12345)
        process.poll.return_value = None
        for outcome in (subprocess.CompletedProcess([], 1), subprocess.TimeoutExpired("taskkill", 15)):
            with self.subTest(outcome=outcome):
                kill.side_effect = outcome if isinstance(outcome, Exception) else None
                kill.return_value = outcome
                run._stop_backend(process)
                self.assertIn("Backend cleanup failed", self.stderr.getvalue())

    def test_stop_signals_interrupt_once_and_restore_previous_handlers(self):
        for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
            stop_signal = getattr(signal, name, None)
            if stop_signal is None:
                continue
            with self.subTest(signal=name):
                original = signal.getsignal(stop_signal)
                with run._stop_signals():
                    handler = signal.getsignal(stop_signal)
                    with self.assertRaises(KeyboardInterrupt):
                        handler(stop_signal, None)
                    handler(stop_signal, None)
                self.assertEqual(signal.getsignal(stop_signal), original)


class StartupLockTests(OutputTestCase):
    def test_only_one_launcher_can_enter_and_lock_is_reusable(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(run, "PROJECT_ROOT", Path(directory)), patch.object(run, "STARTUP_LOCK_TIMEOUT", 0):
                with run._startup_lock():
                    with self.assertRaises(TimeoutError):
                        with run._startup_lock():
                            self.fail("Concurrent launcher acquired the startup lock")
                with run._startup_lock():
                    pass

    def test_lock_is_released_after_an_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(run, "PROJECT_ROOT", Path(directory)), patch.object(run, "STARTUP_LOCK_TIMEOUT", 0):
                with self.assertRaisesRegex(RuntimeError, "startup failed"):
                    with run._startup_lock():
                        raise RuntimeError("startup failed")
                with run._startup_lock():
                    pass


if __name__ == "__main__":
    unittest.main()
