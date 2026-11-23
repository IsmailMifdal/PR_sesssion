"""Exécution bornée de sous-processus : groupe de processus tué au timeout, stderr non bloquant."""

from __future__ import annotations

import os
import re
import signal
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

MAX_STDERR_CHARS = 500
_CONTROL = re.compile(r"[\x00-\x1f\x7f]+")


class ProcessTimeout(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class Completed:
    returncode: int
    stderr: str  # fin de stderr, nettoyée (sans caractères de contrôle)


def sanitize(text: str, limit: int = MAX_STDERR_CHARS) -> str:
    """Rend un texte non fiable sûr pour les logs : une seule ligne, longueur bornée."""
    return _CONTROL.sub(" ", text).strip()[-limit:]


def run_bounded(argv: list[str], *, cwd: Path, env: dict[str, str], timeout: float) -> Completed:
    """Lance ``argv`` sans shell dans son propre groupe de processus.

    Au timeout, tout le groupe est tué (y compris les petits-enfants comme ``semgrep-core``
    ou ``git-remote-https``). stderr va dans un fichier temporaire : un descendant qui garde le
    descripteur ouvert ne peut donc pas bloquer l'appelant.
    """
    with tempfile.TemporaryFile() as err:
        proc = subprocess.Popen(  # noqa: S603 - argv construit par le code, pas de shell
            argv,
            cwd=cwd,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=err,
            start_new_session=True,
        )
        try:
            returncode = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            _kill_group(proc)
            raise ProcessTimeout(f"délai dépassé ({timeout:g}s)") from exc
        finally:
            _kill_group(proc, only_leftovers=True)
        err.seek(0, os.SEEK_END)
        size = err.tell()
        err.seek(max(0, size - 4 * MAX_STDERR_CHARS))
        tail = err.read().decode("utf-8", "replace")
    return Completed(returncode, sanitize(tail))


def _kill_group(proc: subprocess.Popen[bytes], *, only_leftovers: bool = False) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    if not only_leftovers:
        proc.wait()
