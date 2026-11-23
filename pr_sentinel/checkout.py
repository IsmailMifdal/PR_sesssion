"""Récupération du commit de tête de la PR (clone superficiel, sans exécuter le code)."""

from __future__ import annotations

import base64
import os
from pathlib import Path

from pr_sentinel.proc import ProcessTimeout, run_bounded
from pr_sentinel.scanners.base import scrubbed_env

# Fichiers par lesquels une PR pourrait désactiver ou reconfigurer les scanners.
SCANNER_OVERRIDE_FILES = frozenset(
    {
        ".semgrepignore",
        ".gitleaks.toml",
        ".gitleaksignore",
        "trivy.yaml",
        "trivy.yml",
        ".trivyignore",
        ".trivyignore.yaml",
    }
)


class CheckoutError(RuntimeError):
    pass


def strip_scanner_overrides(root: Path) -> list[str]:
    """Supprime de l'arbre cloné les fichiers de configuration/exclusion des scanners."""
    removed = []
    for dirpath, dirnames, filenames in os.walk(root):
        if ".git" in dirnames:
            dirnames.remove(".git")
        for name in filenames:
            if name in SCANNER_OVERRIDE_FILES:
                path = Path(dirpath) / name
                path.unlink()
                removed.append(path.relative_to(root).as_posix())
    return removed


def _git_env(token: str | None, allowed_protocols: str) -> dict[str, str]:
    """Configuration git passée par l'environnement : le jeton n'apparaît ni dans argv ni dans l'URL."""
    config = [
        ("protocol.allow", "never"),
        *((f"protocol.{p}.allow", "always") for p in allowed_protocols.split(",")),
        ("core.hooksPath", os.devnull),
        ("core.symlinks", "false"),
        ("credential.helper", ""),
        ("submodule.recurse", "false"),
        ("transfer.fsckObjects", "true"),
        ("fetch.fsckObjects", "true"),
    ]
    if token:
        basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        config.append(("http.extraHeader", f"Authorization: Basic {basic}"))
    env = scrubbed_env() | {
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_LFS_SKIP_SMUDGE": "1",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_COUNT": str(len(config)),
    }
    for i, (key, value) in enumerate(config):
        env[f"GIT_CONFIG_KEY_{i}"] = key
        env[f"GIT_CONFIG_VALUE_{i}"] = value
    return env


def _git(args: list[str], *, cwd: Path, env: dict[str, str], timeout: int) -> None:
    try:
        done = run_bounded(["git", *args], cwd=cwd, env=env, timeout=timeout)
    except ProcessTimeout as exc:
        raise CheckoutError(f"git {args[0]} : {exc}") from exc
    if done.returncode != 0:
        raise CheckoutError(f"git {args[0]} a échoué ({done.returncode}) : {done.stderr[-300:]}")


def checkout(
    clone_url: str,
    sha: str,
    dest: Path,
    *,
    token: str | None,
    timeout: int,
    allowed_protocols: str = "https",
) -> Path:
    """Récupère exactement ``sha`` dans ``dest`` (créé vide)."""
    env = _git_env(token, allowed_protocols)
    dest.mkdir(parents=True, exist_ok=False)
    _git(["init", "--quiet"], cwd=dest, env=env, timeout=timeout)
    _git(["remote", "add", "origin", clone_url], cwd=dest, env=env, timeout=timeout)
    _git(["fetch", "--quiet", "--depth", "1", "--no-tags", "origin", sha], cwd=dest, env=env, timeout=timeout)
    _git(["checkout", "--quiet", "--detach", "FETCH_HEAD"], cwd=dest, env=env, timeout=timeout)
    strip_scanner_overrides(dest)
    return dest
