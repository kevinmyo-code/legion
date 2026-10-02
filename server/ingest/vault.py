"""The session vault: saved logins for the upstreams a job reads, encrypted
(backend-etl ticket 02, map ruling 2).

A person logs in by hand, in a real browser on their own machine, and
`tools/connect_session.py` hands the resulting session to the server. **No
password is ever stored, asked for, or accepted**: what arrives is a cookie jar
(Canvas, WebAssign) or a Google OAuth refresh token (Drive), and it is sealed
here with Fernet under `LEGION_VAULT_KEY` before it touches the database.

- **No key, no vault.** With `LEGION_VAULT_KEY` unset, `store` refuses in
  words and writes nothing, and `session_for` raises `NeedsLogin`, so a job
  records `needs_login` rather than crashing or pretending.
- **A refused session is marked, not retried.** A job that gets a 401/403 or
  a login redirect calls `refuse_session`, which stamps `invalid_since` and
  returns the `NeedsLogin` to raise. Later runs see the stamp and stop at
  once instead of replaying a dead session at the upstream every half hour.
- **BofA is refused by name** (map ruling 7, ticket 09). A BofA session never
  leaves the laptop it was made on. `store` refuses it here, the route refuses
  it before calling `store`, and the table's check constraint refuses it in
  SQL.
"""
from __future__ import annotations

import datetime
import json
import os
import re
from typing import Any

from cryptography.fernet import Fernet, InvalidToken
from django.utils import timezone

from ingest.jobs import NeedsLogin
from ingest.models import (
    KIND_FOR_SOURCE,
    LOGIN_SCRIPT,
    CredentialKind,
    SessionSource,
    SourceCredential,
)

VAULT_KEY_ENV = "LEGION_VAULT_KEY"
GENERATE_KEY = (
    'python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"'
)

# Sources the vault refuses by name, with the words it refuses them in.
REFUSED_SOURCES: dict[str, str] = {
    "bofa": (
        "Nothing was stored. A BofA session never leaves the laptop it was made on: "
        "BofA's idle timeout is minutes and it fingerprints the device, so a replayed "
        "session would be dead or read as a hijack (backend-etl ticket 09). "
        f"`{LOGIN_SCRIPT} bofa` pulls statements inside the login sitting instead."
    ),
}

# A key that names a password, anywhere in what is handed over, is refused:
# nothing in this vault is ever a password, so one arriving is a client bug
# that must not quietly become a stored secret.
_PASSWORD_KEY = re.compile(r"pass(?:word|wd)|\bpwd\b", re.IGNORECASE)
# `config` is served to every member by GET, so a key there that names a
# secret is refused rather than published.
_SECRET_CONFIG_KEY = re.compile(
    r"pass(?:word|wd)|secret|token|cookie|session|credential|api[_-]?key", re.IGNORECASE
)


class VaultUnavailable(Exception):
    """The vault cannot seal or open anything. The message says why and what
    to do, in words a person can act on."""


class SecretRejected(ValueError):
    """What was handed over is not a session this vault stores."""


def _fernet() -> Fernet:
    raw = os.environ.get(VAULT_KEY_ENV, "").strip()
    if not raw:
        raise VaultUnavailable(
            f"{VAULT_KEY_ENV} is not set, so the session vault cannot encrypt or decrypt. "
            f"Nothing was stored. Generate a key with `{GENERATE_KEY}`, put it in "
            f"deploy/.env (see deploy/.env.example) and keep it: without it, every "
            f"stored session has to be handed over again."
        )
    try:
        return Fernet(raw.encode())
    except (ValueError, TypeError) as exc:
        raise VaultUnavailable(
            f"{VAULT_KEY_ENV} is set but is not a Fernet key (32 url-safe base64 bytes). "
            f"Nothing was stored. Generate one with `{GENERATE_KEY}`."
        ) from exc


def seal(secret: Any) -> bytes:
    """`secret` as JSON, encrypted. Raises `VaultUnavailable` without a key."""
    fernet = _fernet()
    plaintext = json.dumps(secret, separators=(",", ":"), sort_keys=True).encode()
    return fernet.encrypt(plaintext)


def unseal(ciphertext: bytes | memoryview) -> Any:
    """The JSON secret back. Raises `VaultUnavailable` without the key that
    sealed it."""
    fernet = _fernet()
    try:
        plaintext = fernet.decrypt(bytes(ciphertext))
    except InvalidToken as exc:
        raise VaultUnavailable(
            f"A stored session cannot be decrypted with the current {VAULT_KEY_ENV}. "
            f"The key has changed since it was stored; hand the session over again "
            f"with {LOGIN_SCRIPT}."
        ) from exc
    return json.loads(plaintext)


def _has_password_key(value: Any) -> bool:
    if isinstance(value, dict):
        return any(
            _PASSWORD_KEY.search(str(key)) or _has_password_key(item)
            for key, item in value.items()
        )
    if isinstance(value, list):
        return any(_has_password_key(item) for item in value)
    return False


def _nonempty_str(value: Any) -> bool:
    return isinstance(value, str) and value.strip() != ""


def validate_secret(kind: str, secret: Any) -> None:
    """Refuses, in words, anything that is not the shape `kind` stores."""
    if _has_password_key(secret):
        raise SecretRejected(
            "Nothing was stored. What was sent names a password, and this vault never "
            "stores one: log in by hand in the browser and hand over the session instead."
        )
    if kind == CredentialKind.COOKIE_JAR:
        cookies = secret.get("cookies") if isinstance(secret, dict) else None
        if not isinstance(cookies, list) or not cookies:
            raise SecretRejected(
                'Nothing was stored. A cookie-jar session is {"cookies": [...]} with at '
                "least one cookie; this had none, which usually means the login never "
                "finished."
            )
        for cookie in cookies:
            if not isinstance(cookie, dict) or not all(
                _nonempty_str(cookie.get(field)) for field in ("name", "domain")
            ) or not isinstance(cookie.get("value"), str):
                raise SecretRejected(
                    "Nothing was stored. Every cookie needs a name, a value and a domain."
                )
        return
    if kind == CredentialKind.OAUTH_REFRESH:
        if not isinstance(secret, dict) or not all(
            _nonempty_str(secret.get(field))
            for field in ("refresh_token", "client_id", "client_secret")
        ):
            raise SecretRejected(
                "Nothing was stored. A Drive session needs refresh_token, client_id and "
                "client_secret; Google returns no refresh token when the consent screen "
                "was skipped, so run the login again."
            )
        return
    raise SecretRejected(f"Nothing was stored. {kind!r} is not a kind this vault stores.")


def validate_config(config: Any) -> None:
    if not isinstance(config, dict):
        raise SecretRejected("Nothing was stored. config must be a JSON object.")
    named = sorted(str(key) for key in config if _SECRET_CONFIG_KEY.search(str(key)))
    if named:
        raise SecretRejected(
            f"Nothing was stored. config is shown to every member of the household, so it "
            f"cannot carry {', '.join(named)}. Secrets belong in the sealed session."
        )


def session_source(source: str) -> SessionSource:
    """`source` as a `SessionSource`, refusing BofA by name and anything else
    unknown with `SecretRejected`."""
    if source in REFUSED_SOURCES:
        raise SecretRejected(REFUSED_SOURCES[source])
    try:
        return SessionSource(source)
    except ValueError as exc:
        raise SecretRejected(
            f"Nothing was stored. {source!r} is not a source this vault holds; it holds "
            f"{', '.join(SessionSource.values)}."
        ) from exc


def store(
    household,
    source: str,
    secret: Any,
    *,
    config: dict | None = None,
    expires_hint: datetime.datetime | None = None,
) -> SourceCredential:
    """Seal `secret` and save it as `household`'s session for `source`,
    replacing any earlier one and clearing `invalid_since`.

    `config` is MERGED into what is already stored, so a re-login never wipes
    a setting a later ticket put there (the Drive folder id). Raises
    `SecretRejected` or `VaultUnavailable` before anything is written.
    """
    source = session_source(source)
    kind = KIND_FOR_SOURCE[source]
    validate_secret(kind, secret)
    config = config or {}
    validate_config(config)
    ciphertext = seal(secret)

    existing = SourceCredential.objects.filter(household=household, source=source).first()
    merged = {**(existing.config if existing else {}), **config}
    credential, _ = SourceCredential.objects.update_or_create(
        household=household,
        source=source,
        defaults={
            "kind": kind,
            "ciphertext": ciphertext,
            "captured_at": timezone.now(),
            "expires_hint": expires_hint,
            "invalid_since": None,
            "config": merged,
        },
    )
    return credential


def credential_for(household, source: str) -> SourceCredential | None:
    return SourceCredential.objects.filter(household=household, source=source).first()


def is_configured(household, source: str) -> bool:
    """Whether `household` has ever handed over a `source` session. A job for a
    household that has not records `skipped` ("not set up"); one whose session
    has since been refused is still configured, and records `needs_login`."""
    return SourceCredential.objects.filter(household=household, source=source).exists()


def _label(source: str) -> str:
    try:
        return SessionSource(source).label
    except ValueError:
        return source


def login_hint(source: str) -> str:
    return f"run {LOGIN_SCRIPT} {source}"


def session_for(household, source: str) -> tuple[SourceCredential, Any]:
    """The credential row and its opened secret, for a job to use.

    Raises `NeedsLogin` when there is nothing usable: no session stored, one
    already refused by the upstream, or a vault that cannot open it. Every
    message says what to run, and none of them contains the secret.
    """
    label = _label(source)
    credential = credential_for(household, source)
    if credential is None:
        raise NeedsLogin(f"No {label} login is stored. {login_hint(source)}.")
    if credential.invalid_since is not None:
        raise NeedsLogin(
            f"{label} refused the saved login at {credential.invalid_since.isoformat()}. "
            f"{login_hint(source)}."
        )
    try:
        secret = unseal(credential.ciphertext)
    except VaultUnavailable as exc:
        raise NeedsLogin(str(exc)) from exc
    return credential, secret


def refuse_session(credential: SourceCredential, detail: str) -> NeedsLogin:
    """Mark `credential` refused by its upstream and return the `NeedsLogin`
    for the job to raise.

    `detail` is what the upstream did ("HTTP 401", "redirected to /login"),
    never anything from the request. `invalid_since` keeps the FIRST refusal:
    a later run re-stamping it would hide how long the feed has been down.

    Stamped here AND again by `run_job` once the job has unwound (see
    `NeedsLogin`): here for a caller outside `run_job`, there because a job's
    own `atomic()` block would roll this write back with everything else.
    """
    if credential.invalid_since is None:
        credential.invalid_since = timezone.now()
        credential.save(update_fields=["invalid_since"])
    label = _label(credential.source)
    return NeedsLogin(
        f"{label} refused the saved login ({detail}). {login_hint(credential.source)}.",
        refused_credential=credential,
    )


LOGIN_PATH_MARKERS = ("/login", "/signin", "/sign_in", "/saml", "/sso", "/cas/")


def is_login_refusal(status_code: int, final_url: str | None = None) -> bool:
    """True for the two shapes an expired session takes: a 401/403, or a
    response that landed on a login page after redirects."""
    if status_code in (401, 403):
        return True
    if final_url:
        lowered = final_url.lower()
        return any(marker in lowered for marker in LOGIN_PATH_MARKERS)
    return False
