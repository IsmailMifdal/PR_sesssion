"""Vérification de signature et interprétation des webhooks GitHub."""

from __future__ import annotations

import hashlib
import hmac
import re
from typing import Any

from pr_sentinel.queue import NewJob

SIGNATURE_PREFIX = "sha256="
SCANNED_ACTIONS = frozenset({"opened", "synchronize", "reopened"})

_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
_REPO_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")
_DELIVERY_RE = re.compile(r"^[A-Za-z0-9-]{1,100}$")


class InvalidPayload(ValueError):
    """Payload signé mais inexploitable."""


def verify_signature(secret: bytes, body: bytes, signature_header: str | None) -> bool:
    """Vérifie ``X-Hub-Signature-256`` sur le corps brut, à temps constant."""
    if not signature_header or not signature_header.startswith(SIGNATURE_PREFIX):
        return False
    expected = SIGNATURE_PREFIX + hmac.new(secret, body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected.encode(), signature_header.encode())


def sign(secret: bytes, body: bytes) -> str:
    """Calcule l'en-tête de signature (utile aux tests et aux outils de rejeu)."""
    return SIGNATURE_PREFIX + hmac.new(secret, body, hashlib.sha256).hexdigest()


def _get(data: Any, *path: str) -> Any:
    for key in path:
        if not isinstance(data, dict) or key not in data:
            raise InvalidPayload(f"champ manquant : {'.'.join(path)}")
        data = data[key]
    return data


def parse_pull_request(delivery_id: str, payload: Any) -> NewJob | None:
    """Renvoie la tâche à créer, ou ``None`` si l'action n'est pas à scanner."""
    if not isinstance(delivery_id, str) or not _DELIVERY_RE.fullmatch(delivery_id):
        raise InvalidPayload("X-GitHub-Delivery invalide")
    if _get(payload, "action") not in SCANNED_ACTIONS:
        return None

    installation_id = _get(payload, "installation", "id")
    repo = _get(payload, "repository", "full_name")
    number = _get(payload, "pull_request", "number")
    sha = _get(payload, "pull_request", "head", "sha")

    if not isinstance(installation_id, int) or isinstance(installation_id, bool):
        raise InvalidPayload("installation.id invalide")
    if not isinstance(repo, str) or not _REPO_RE.fullmatch(repo) or ".." in repo:
        raise InvalidPayload("repository.full_name invalide")
    if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
        raise InvalidPayload("pull_request.number invalide")
    if not isinstance(sha, str) or not _SHA_RE.fullmatch(sha):
        raise InvalidPayload("pull_request.head.sha invalide")

    return NewJob(
        delivery_id=delivery_id,
        installation_id=installation_id,
        repo_full_name=repo,
        pr_number=number,
        head_sha=sha,
    )
