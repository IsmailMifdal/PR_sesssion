"""Modèle commun des scanners : alertes normalisées et exécution en sous-processus."""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from pr_sentinel.proc import ProcessTimeout, run_bounded

log = logging.getLogger(__name__)


MAX_REPORT_BYTES = 50 * 1024 * 1024

# Variables transmises aux scanners ; tout le reste (secrets du worker compris) est filtré.
_ENV_ALLOWLIST = frozenset(
    {
        "PATH",
        "HOME",
        "LANG",
        "LC_ALL",
        "TMPDIR",
        "SSL_CERT_FILE",
        "REQUESTS_CA_BUNDLE",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "NO_PROXY",
        "https_proxy",
        "http_proxy",
        "no_proxy",
        "TRIVY_CACHE_DIR",
    }
)


class ScannerError(RuntimeError):
    """Le scanner n'a pas pu produire de rapport exploitable."""


@dataclass(frozen=True, slots=True)
class Finding:
    tool: str
    rule_id: str
    severity: str
    path: str
    line: int | None
    message: str


@dataclass(frozen=True, slots=True)
class ScanResult:
    tool: str
    findings: tuple[Finding, ...] = ()
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.error is None


class Scanner(Protocol):
    name: str

    def scan(self, source: Path, workdir: Path) -> ScanResult: ...


@dataclass(frozen=True, slots=True)
class CommandSpec:
    argv: list[str]
    ok_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))


def scrubbed_env() -> dict[str, str]:
    return {k: v for k, v in os.environ.items() if k in _ENV_ALLOWLIST}


def run_command(tool: str, spec: CommandSpec, *, cwd: Path, timeout: int) -> None:
    """Exécute un scanner sans shell ; lève ``ScannerError`` en cas d'échec ou de timeout."""
    try:
        done = run_bounded(spec.argv, cwd=cwd, env=scrubbed_env(), timeout=timeout)
    except ProcessTimeout as exc:
        raise ScannerError(str(exc)) from exc
    except FileNotFoundError as exc:
        raise ScannerError(f"exécutable introuvable : {spec.argv[0]}") from exc
    if done.returncode not in spec.ok_exit_codes:
        log.warning("%s a échoué (code %s) : %s", tool, done.returncode, done.stderr)
        raise ScannerError(f"code de sortie {done.returncode}")


def load_json_report(path: Path) -> Any:
    try:
        if path.stat().st_size > MAX_REPORT_BYTES:
            raise ScannerError("rapport trop volumineux")
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ScannerError("rapport absent") from exc
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ScannerError("rapport JSON illisible") from exc


def relative_path(raw: str, source: Path) -> str:
    """Rend un chemin de rapport relatif au dépôt cloné (jamais de chemin absolu du worker)."""
    path = Path(raw)
    if path.is_absolute():
        try:
            return path.resolve().relative_to(source.resolve()).as_posix()
        except ValueError:
            return path.name
    return path.as_posix().removeprefix("./")


def as_int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else None


def as_str(value: Any, default: str = "") -> str:
    return value if isinstance(value, str) else default
