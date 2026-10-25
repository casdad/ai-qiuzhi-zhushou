"""Run the Get Jobs Spring Boot application from an IDE.

This is intentionally dependency-free.  PyCharm and VSCode can run this file
directly, while Gradle remains responsible for compiling the Java application,
and building the embedded Next.js assets. Gradle also configures the browser driver.
"""

from __future__ import annotations

import os
import signal
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent
# Keep these in sync with application.yaml and front/server.config.js.
BACKEND_PORT = 9527
FRONTEND_PORT = 6866
CDP_PORT = 7866
STARTUP_LOCK_TIMEOUT = 120


@contextmanager
def _startup_lock():
    """Serialize launchers until the backend binds, not for its entire lifetime."""

    lock_path = PROJECT_ROOT / "target" / "launcher-startup.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as lock_file:
        if lock_file.tell() == 0:
            lock_file.write(b"\0")
            lock_file.flush()
        if os.name == "nt":
            import msvcrt

            def acquire():
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)

            def release():
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            def acquire():
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

            def release():
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

        deadline = time.monotonic() + STARTUP_LOCK_TIMEOUT
        waiting = False
        while True:
            try:
                acquire()
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise TimeoutError("Another launcher is still starting; retry after it finishes.") from None
                if not waiting:
                    print("[Get Jobs] Another launch is in progress; waiting before restarting...", flush=True)
                    waiting = True
                time.sleep(0.2)
        try:
            yield
        finally:
            release()


def _port_is_open(port: int) -> bool:
    """Report whether something is already listening on the given local port."""

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def _stop_existing_server() -> bool:
    """Stop only an instance whose process identity belongs to this checkout."""

    if not _port_is_open(BACKEND_PORT):
        return True

    if os.name != "nt":
        print(
            f"[Get Jobs] Port {BACKEND_PORT} is in use. Automatic restart is supported on Windows; "
            "stop the existing instance manually on this platform.",
            file=sys.stderr,
        )
        return False

    print(f"[Get Jobs] Port {BACKEND_PORT} is in use; checking the old instance before restarting...", flush=True)
    try:
        result = subprocess.run(
            [
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", str(PROJECT_ROOT / "bin" / "stop-instance.ps1"),
                "-ProjectRoot", str(PROJECT_ROOT),
                "-BackendPort", str(BACKEND_PORT),
                "-FrontendPort", str(FRONTEND_PORT),
                "-CdpPort", str(CDP_PORT),
            ],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW,
            timeout=45,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"[Get Jobs] Could not stop the old instance: {error}", file=sys.stderr)
        return False

    if result.stdout:
        print(result.stdout.rstrip(), flush=True)
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr)
    if result.returncode != 0:
        print("[Get Jobs] Restart aborted; no new Chrome or Spring Boot process was started.", file=sys.stderr)
        return False
    if _port_is_open(BACKEND_PORT):
        print(f"[Get Jobs] Port {BACKEND_PORT} is still occupied; restart aborted.", file=sys.stderr)
        return False
    return True


def _find_chrome() -> Path | None:
    """Locate a Chrome/Chromium executable without changing any system config."""

    candidates: list[Path] = []
    if os.name == "nt":
        local_app_data = Path(os.environ.get("LOCALAPPDATA", "."))
        for base in (
            Path(os.environ.get("PROGRAMFILES", r"C:\Program Files")),
            Path(os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")),
        ):
            candidates.append(base / "Google/Chrome/Application/chrome.exe")
        candidates.append(local_app_data / "Google/Chrome/Application/chrome.exe")
        candidates.append(local_app_data / "Chromium/Application/chrome.exe")
    else:
        candidates.extend(
            Path(path)
            for path in (
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
            )
        )

    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def _start_debug_chrome() -> subprocess.Popen[bytes] | None:
    """Start an ordinary Chrome with the CDP port enabled, if one is not already running.

    A dedicated user-data-dir is mandatory: Chrome refuses the debugging port on its
    default profile. CDP attachment alone does not fix site compatibility; bootRun also
    selects the project's compatible driver.
    """

    if _port_is_open(CDP_PORT):
        print(f"[Get Jobs] Chrome already listening on {CDP_PORT}, attaching to it")
        return None

    chrome = _find_chrome()
    if chrome is None:
        print("[Get Jobs] Chrome not found; the app will launch its own browser.", file=sys.stderr)
        return None

    profile = PROJECT_ROOT / "chrome-profile"
    profile.mkdir(parents=True, exist_ok=True)
    print(f"[Get Jobs] Starting Chrome with CDP on {CDP_PORT} (profile: {profile})")
    process = subprocess.Popen(
        [
            str(chrome),
            f"--remote-debugging-port={CDP_PORT}",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--no-default-browser-check",
            "--start-maximized",
        ],
        cwd=PROJECT_ROOT,
    )
    for _ in range(20):
        if _port_is_open(CDP_PORT):
            break
        time.sleep(0.5)
    else:
        print(f"[Get Jobs] Chrome did not open port {CDP_PORT}; falling back to its own browser.", file=sys.stderr)
    return process


def _java_version(java_home: Path) -> str:
    """Read the Java version from a JDK's release file, if available."""

    release_file = java_home / "release"
    if not release_file.is_file():
        return ""
    for line in release_file.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("JAVA_VERSION="):
            return line.split("=", 1)[1].strip().strip('"')
    return ""


def _find_jdk21() -> Path | None:
    """Find a JDK 21 installation without changing the user's machine config."""

    candidates: list[Path] = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        candidates.append(Path(configured).expanduser())

    user_home = Path.home()
    candidates.extend(
        (
            user_home / ".jdks" / "temurin-21",
            user_home / ".jdks" / "jdk-21",
            Path("C:/Program Files/Java/jdk-21"),
            Path("C:/Program Files/Eclipse Adoptium/jdk-21"),
        )
    )

    # Include common versioned JDK directories when the exact name differs.
    for parent in (user_home / ".jdks", Path("C:/Program Files/Java"), Path("C:/Program Files/Eclipse Adoptium")):
        if parent.is_dir():
            candidates.extend(sorted(parent.glob("*21*")))

    seen: set[Path] = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen or not candidate.is_dir():
            continue
        seen.add(candidate)
        if _java_version(candidate).startswith("21"):
            return candidate
    return None


def _gradle_command(jdk21: Path) -> str | list[str]:
    """Return a command that works for both Windows and POSIX hosts."""

    if os.name == "nt":
        wrapper = PROJECT_ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
        if not wrapper.is_file():
            raise FileNotFoundError(f"Gradle wrapper not found: {wrapper}")
        # Invoke the same wrapper main class without cmd.exe's Ctrl+C batch prompt.
        # Keep the options as a Windows command line so quoted JVM paths survive.
        return " ".join(part for part in (
            subprocess.list2cmdline([str(jdk21 / "bin" / "java.exe"), "-Xmx64m", "-Xms64m"]),
            os.environ.get("JAVA_OPTS", ""),
            os.environ.get("GRADLE_OPTS", ""),
            subprocess.list2cmdline([
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-Dorg.gradle.appname=gradlew", "-classpath", str(wrapper),
                "org.gradle.wrapper.GradleWrapperMain", "bootRun", "--console=plain", "--no-daemon",
            ]),
        ) if part)

    wrapper = PROJECT_ROOT / "gradlew"
    if not wrapper.is_file():
        raise FileNotFoundError(f"Gradle wrapper not found: {wrapper}")
    if not os.access(wrapper, os.X_OK):
        wrapper.chmod(wrapper.stat().st_mode | 0o111)
    return [str(wrapper), "bootRun", "--console=plain", "--no-daemon"]


@contextmanager
def _stop_signals():
    """Route IDE Stop, Ctrl+C and Ctrl+Break through the same cleanup path."""

    stopping = False

    def request_stop(signum, frame):
        nonlocal stopping
        if not stopping:
            stopping = True
            raise KeyboardInterrupt

    previous = {}
    try:
        for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
            stop_signal = getattr(signal, name, None)
            if stop_signal is not None:
                previous[stop_signal] = signal.signal(stop_signal, request_stop)
        yield
    finally:
        for stop_signal, handler in previous.items():
            signal.signal(stop_signal, handler)


def _stop_backend(process: subprocess.Popen[bytes] | None) -> None:
    """Stop this launch's process tree, never all Java or Gradle processes."""

    if process is None or process.poll() is not None:
        return
    print("[Get Jobs] Stopping Spring Boot and its Gradle process tree...", flush=True)
    try:
        if os.name == "nt":
            # --no-daemon ensures this tree does not own another project's build.
            result = subprocess.run(
                ["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW,
                timeout=15,
                check=False,
            )
            if result.returncode != 0 and process.poll() is None:
                raise OSError(f"Could not stop backend process tree (PID {process.pid})")
        else:
            process.terminate()
        process.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"[Get Jobs] Backend cleanup failed: {error}", file=sys.stderr)


def _run() -> int:
    print(f"[Get Jobs] Project: {PROJECT_ROOT}")
    database = PROJECT_ROOT / "db" / "getjobs.db"
    if not database.is_file():
        print(f"[Get Jobs] Database not found: {database}", file=sys.stderr)
        print("Download getjobs.db.template, rename it to getjobs.db, and place it under db/.", file=sys.stderr)
        return 1

    jdk21 = _find_jdk21()
    if jdk21 is None:
        print("[Get Jobs] JDK 21 was not found.", file=sys.stderr)
        print("Set JAVA_HOME to a JDK 21 installation, then run this file again.", file=sys.stderr)
        return 1

    # Validate startup prerequisites before stopping a working instance.
    try:
        command = _gradle_command(jdk21)
    except FileNotFoundError as error:
        print(f"[Get Jobs] {error}", file=sys.stderr)
        return 1
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(jdk21)
    chrome_process = None
    backend_process = None
    try:
        with _startup_lock():
            if not _stop_existing_server():
                return 1
            print(f"[Get Jobs] JAVA_HOME: {jdk21}")
            chrome_process = _start_debug_chrome()
            print("[Get Jobs] Starting Spring Boot (Ctrl+C to stop)...", flush=True)
            backend_process = subprocess.Popen(
                command, cwd=PROJECT_ROOT, env=environment,
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
            )
            # A second launcher must not pass the port check while Gradle is compiling.
            while backend_process.poll() is None and not _port_is_open(BACKEND_PORT):
                time.sleep(0.2)
        # Bounded waits let Python dispatch IDE stop signals promptly on Windows.
        while True:
            try:
                return backend_process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                pass
    except OSError as error:
        print(f"[Get Jobs] {error}", file=sys.stderr)
        return 1
    finally:
        _stop_backend(backend_process)
        if chrome_process is not None:
            chrome_process.terminate()
            try:
                chrome_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                chrome_process.kill()


def main() -> int:
    with _stop_signals():
        try:
            return _run()
        except KeyboardInterrupt:
            print("\n[Get Jobs] Stopped.", flush=True)
            return 130


if __name__ == "__main__":
    raise SystemExit(main())
