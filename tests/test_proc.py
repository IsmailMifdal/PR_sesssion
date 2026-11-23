import sys
import time

import pytest

from pr_sentinel.proc import ProcessTimeout, run_bounded, sanitize


def test_success_and_stderr_tail(tmp_path):
    done = run_bounded(
        [sys.executable, "-c", "import sys; sys.stderr.write('a\\x1b[31mb\\nc'); sys.exit(4)"],
        cwd=tmp_path,
        env={},
        timeout=10,
    )
    assert done.returncode == 4
    assert done.stderr == "a [31mb c"


def test_timeout_kills_grandchildren_holding_stderr(tmp_path):
    # Le petit-enfant garde stderr ouvert : subprocess.run(timeout=...) resterait bloqué ici.
    script = (
        "import subprocess, sys, time;"
        "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)']);"
        "time.sleep(60)"
    )
    start = time.monotonic()
    with pytest.raises(ProcessTimeout, match="délai dépassé"):
        run_bounded([sys.executable, "-c", script], cwd=tmp_path, env={}, timeout=1)
    assert time.monotonic() - start < 10


def test_leftover_background_process_does_not_block(tmp_path):
    script = "import subprocess, sys;subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])"
    start = time.monotonic()
    assert run_bounded([sys.executable, "-c", script], cwd=tmp_path, env={}, timeout=10).returncode == 0
    assert time.monotonic() - start < 10


def test_sanitize_bounds_length():
    assert len(sanitize("x" * 5000)) == 500
