from __future__ import annotations

import json
from typing import Any

SHA_A = "a" * 40
SHA_B = "b" * 40


def pr_payload(
    *,
    action: str = "opened",
    sha: str = SHA_A,
    number: int = 7,
    repo: str = "acme/widgets",
    installation: int = 99,
) -> dict[str, Any]:
    return {
        "action": action,
        "installation": {"id": installation},
        "repository": {"full_name": repo, "clone_url": f"https://github.com/{repo}.git"},
        "pull_request": {"number": number, "head": {"sha": sha}},
    }


def to_body(payload: Any) -> bytes:
    return json.dumps(payload).encode()
