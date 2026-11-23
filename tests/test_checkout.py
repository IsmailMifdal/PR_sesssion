import os
import subprocess
from pathlib import Path

import pytest

from pr_sentinel import checkout as checkout_mod
from pr_sentinel.checkout import CheckoutError, checkout, strip_scanner_overrides
from pr_sentinel.proc import Completed, ProcessTimeout

GIT_ENV = {
    **os.environ,
    "GIT_AUTHOR_NAME": "t",
    "GIT_AUTHOR_EMAIL": "t@example.test",
    "GIT_COMMITTER_NAME": "t",
    "GIT_COMMITTER_EMAIL": "t@example.test",
}


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, env=GIT_ENV, check=True, capture_output=True, text=True
    ).stdout.strip()


@pytest.fixture
def origin(tmp_path) -> tuple[Path, str, str]:
    repo = tmp_path / "origin"
    repo.mkdir()
    git(repo, "init", "--quiet", "-b", "main")
    (repo / "a.txt").write_text("v1\n")
    git(repo, "add", ".")
    git(repo, "commit", "--quiet", "-m", "one")
    first = git(repo, "rev-parse", "HEAD")
    (repo / "a.txt").write_text("v2\n")
    (repo / ".git" / "hooks" / "post-checkout").write_text("#!/bin/sh\ntouch HOOK_RAN\n")
    git(repo, "commit", "--quiet", "-am", "two")
    second = git(repo, "rev-parse", "HEAD")
    return repo, first, second


def test_checks_out_exact_sha(tmp_path, origin):
    repo, first, _ = origin
    dest = checkout(
        f"file://{repo}", first, tmp_path / "dest", token=None, timeout=30, allowed_protocols="file"
    )
    assert (dest / "a.txt").read_text() == "v1\n"
    assert git(dest, "rev-parse", "HEAD") == first
    assert git(dest, "rev-list", "--count", "HEAD") == "1"  # clone superficiel
    assert not (dest / "HOOK_RAN").exists()


def test_https_only_by_default(tmp_path, origin):
    repo, first, _ = origin
    with pytest.raises(CheckoutError, match="fetch"):
        checkout(f"file://{repo}", first, tmp_path / "dest", token=None, timeout=30)


def test_unknown_sha_fails(tmp_path, origin):
    repo, _, _ = origin
    with pytest.raises(CheckoutError):
        checkout(
            f"file://{repo}", "f" * 40, tmp_path / "dest", token=None, timeout=30, allowed_protocols="file"
        )


def test_token_passed_via_env_not_argv(monkeypatch, tmp_path):
    calls = []

    def fake_run(argv, **kwargs):
        calls.append((argv, kwargs["env"]))
        return Completed(0, "")

    monkeypatch.setattr(checkout_mod, "run_bounded", fake_run)
    checkout("https://github.com/acme/w.git", "a" * 40, tmp_path / "d", token="ghs_TOPSECRET", timeout=5)
    for argv, env in calls:
        assert not any("ghs_TOPSECRET" in a for a in argv)
        assert "ghs_TOPSECRET" not in " ".join(argv)
        values = [v for k, v in env.items() if k.startswith("GIT_CONFIG_VALUE_")]
        assert any(v.startswith("Authorization: Basic ") for v in values)
        assert env["GIT_TERMINAL_PROMPT"] == "0"
        config = {
            env[f"GIT_CONFIG_KEY_{i}"]: env[f"GIT_CONFIG_VALUE_{i}"]
            for i in range(int(env["GIT_CONFIG_COUNT"]))
        }
        assert config["protocol.allow"] == "never" and config["protocol.https.allow"] == "always"
        assert config["core.symlinks"] == "false" and config["transfer.fsckObjects"] == "true"
    assert calls[2][0][-2:] == ["origin", "a" * 40]


def test_timeout(monkeypatch, tmp_path):
    def slow(argv, **kwargs):
        raise ProcessTimeout("délai dépassé (1s)")

    monkeypatch.setattr(checkout_mod, "run_bounded", slow)
    with pytest.raises(CheckoutError, match="délai"):
        checkout("https://x/y.git", "a" * 40, tmp_path / "d", token=None, timeout=1)


def test_scanner_override_files_are_removed(tmp_path, origin):
    repo, _, _ = origin
    for rel in (
        ".gitleaksignore",
        ".gitleaks.toml",
        "sub/.semgrepignore",
        "trivy.yaml",
        "deep/x/.trivyignore",
        "keep.toml",
    ):
        (repo / rel).parent.mkdir(parents=True, exist_ok=True)
        (repo / rel).write_text("x\n")
    git(repo, "add", ".")
    git(repo, "commit", "--quiet", "-m", "overrides")
    sha = git(repo, "rev-parse", "HEAD")
    dest = checkout(
        f"file://{repo}", sha, tmp_path / "dest", token=None, timeout=30, allowed_protocols="file"
    )
    remaining = sorted(
        p.relative_to(dest).as_posix()
        for p in dest.rglob("*")
        if p.is_file() and ".git/" not in p.relative_to(dest).as_posix() + "/"
    )
    assert remaining == ["a.txt", "keep.toml"]


def test_strip_ignores_git_dir(tmp_path):
    (tmp_path / ".git").mkdir()
    (tmp_path / ".git" / ".gitleaksignore").write_text("x")
    assert strip_scanner_overrides(tmp_path) == []
