import pytest

from pr_sentinel.webhook import InvalidPayload, parse_pull_request, sign, verify_signature
from tests.factories import SHA_A, pr_payload

SECRET = b"s3cret-s3cret-s3cret"
BODY = b'{"hello": "world"}'


def test_valid_signature_accepted():
    assert verify_signature(SECRET, BODY, sign(SECRET, BODY))


@pytest.mark.parametrize(
    "header",
    [
        None,
        "",
        "sha1=" + "0" * 40,
        "sha256=" + "0" * 64,
        sign(b"wrong-secret", BODY),
        sign(SECRET, BODY).upper(),
    ],
)
def test_invalid_signatures_rejected(header):
    assert not verify_signature(SECRET, BODY, header)


def test_tampered_body_rejected():
    assert not verify_signature(SECRET, BODY + b" ", sign(SECRET, BODY))


def test_parse_scannable_action():
    job = parse_pull_request("abc-123", pr_payload(action="synchronize"))
    assert job is not None
    assert (job.repo_full_name, job.pr_number, job.head_sha, job.installation_id) == (
        "acme/widgets",
        7,
        SHA_A,
        99,
    )


@pytest.mark.parametrize("action", ["closed", "labeled", "edited", "assigned"])
def test_parse_ignores_other_actions(action):
    assert parse_pull_request("abc", pr_payload(action=action)) is None


@pytest.mark.parametrize(
    "mutate",
    [
        lambda p: p.pop("installation"),
        lambda p: p["pull_request"]["head"].update(sha="not-a-sha"),
        lambda p: p["pull_request"]["head"].update(sha="A" * 40),
        lambda p: p["pull_request"].update(number=0),
        lambda p: p["pull_request"].update(number=True),
        lambda p: p["repository"].update(full_name="../etc/passwd"),
        lambda p: p["repository"].update(full_name="acme/../x"),
        lambda p: p["repository"].update(full_name="acme"),
        lambda p: p["installation"].update(id="99"),
    ],
)
def test_parse_rejects_malformed(mutate):
    payload = pr_payload()
    mutate(payload)
    with pytest.raises(InvalidPayload):
        parse_pull_request("abc", payload)


@pytest.mark.parametrize("delivery", ["", "has space", "x" * 101, "a/b"])
def test_parse_rejects_bad_delivery_id(delivery):
    with pytest.raises(InvalidPayload):
        parse_pull_request(delivery, pr_payload())


def test_parse_rejects_non_object():
    with pytest.raises(InvalidPayload):
        parse_pull_request("abc", ["not", "a", "dict"])
