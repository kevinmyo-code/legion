"""Send mail from Alfred's own Gmail (rulings 14 and 17, ticket 04). What a helper calls to send.

    python send_mail.py --task 42 --token T --to dad@example.com --subject "Rates" \
        --body "Here is the sheet." --attach C:/.../work/task-42/rates.xlsx
    python send_mail.py --task 43 --token T --to kevin --subject "..." --body "..." --attach FILE

Refuses unless the worker issued a confirmation token for that task (`tokens/<task>.json`):
- `kind: yes`: Kevin answered yes to the task's needs_confirmation question. Any recipients;
  Kevin is always cc'd.
- `kind: email_self`: Kevin asked for the result by email in the request itself (ruling 17).
  Only to Kevin's own address (`--to kevin`); anything else needs a question first.
One token, one send. The work laptop (scope `listed`, ruling 18) sends only text to Kevin's own
address: no other recipient, no attachments, whatever the token.

Prints one line saying, in words, whether the mail went; the helper puts it in its summary.
Never prints or logs the app password, the body, or attachment paths.
"""
from __future__ import annotations

import argparse
import mimetypes
import smtplib
import ssl
import sys
import time
from email.message import EmailMessage
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, TokenRefused, find_token, load_config, write_json_atomic  # noqa: E402

ATTACH_CAP_BYTES = 20 * 1024 * 1024  # Gmail refuses over 25 MB after encoding


def SMTP_FACTORY(host: str, port: int):  # replaced in tests; never connects there
    return smtplib.SMTP_SSL(host, port, context=ssl.create_default_context(), timeout=60)


class Refused(Exception):
    pass


def _find_token(paths: Paths, task: int, token: str, now: float) -> tuple[Path, dict]:
    """The same check ship.py and undo.py make (`common.find_token`), worded for a send."""
    try:
        return find_token(paths, task, token, now, "mail_used")
    except TokenRefused as e:
        msg = str(e)
        if "already used" in msg:
            msg = "this confirmation was already used for one send"
        elif "no confirmation" in msg:
            msg = "there is no confirmation from Kevin for this send"
        raise Refused(msg) from None


def prepare(argv: list[str], paths: Paths, cfg: dict, now: float | None = None):
    now = time.time() if now is None else now
    ap = argparse.ArgumentParser(prog="send_mail.py")
    ap.add_argument("--task", type=int, required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--to", action="append", required=True, help="address, or 'kevin'")
    ap.add_argument("--subject", required=True)
    body = ap.add_mutually_exclusive_group(required=True)
    body.add_argument("--body")
    body.add_argument("--body-file")
    ap.add_argument("--attach", action="append", default=[])
    a = ap.parse_args(argv)

    listed = cfg.get("scope") != "full"
    mail = cfg.get("mail") or {}
    kevin = (mail.get("kevin") or "").strip()
    if not (mail.get("address") and mail.get("app_password") and kevin):
        raise Refused("Alfred's mail account is not set up on this laptop")

    tok_path, tok = _find_token(paths, a.task, a.token, now)
    to = [kevin if t.strip().lower() == "kevin" else t.strip() for t in a.to]
    if tok.get("kind") == "email_self":
        if any(t.lower() != kevin.lower() for t in to):
            raise Refused("Kevin only asked for it to be sent to himself; anyone else needs his yes")
    elif tok.get("kind") != "yes":
        raise Refused("there is no confirmation from Kevin for this send")
    if listed:
        if any(t.lower() != kevin.lower() for t in to):
            raise Refused("the work laptop only mails Kevin himself")
        if a.attach:
            raise Refused("the work laptop sends the summary as text only, no attachments")

    msg = EmailMessage()
    msg["From"] = mail["address"]
    msg["To"] = ", ".join(to)
    cc = [] if all(t.lower() == kevin.lower() for t in to) else [kevin]
    if cc:
        msg["Cc"] = ", ".join(cc)
    msg["Subject"] = a.subject
    text = a.body if a.body is not None else Path(a.body_file).read_text(encoding="utf-8")
    msg.set_content(text)
    total = 0
    for raw in a.attach:
        p = Path(raw)
        if not p.is_file():
            raise Refused(f"an attachment is missing ({p.name})")
        total += p.stat().st_size
        if total > ATTACH_CAP_BYTES:
            raise Refused("the attachments are over 20 megabytes")
        ctype, _ = mimetypes.guess_type(p.name)
        maintype, subtype = (ctype or "application/octet-stream").split("/", 1)
        msg.add_attachment(p.read_bytes(), maintype=maintype, subtype=subtype, filename=p.name)
    return msg, to + cc, mail, tok_path, tok


def main(argv: list[str] | None = None, paths: Paths | None = None) -> int:
    paths = paths or Paths()
    cfg = load_config(paths)
    try:
        msg, rcpts, mail, tok_path, tok = prepare(sys.argv[1:] if argv is None else argv, paths, cfg)
    except Refused as e:
        print(f"Not sent: {e}.")
        return 1
    try:
        with SMTP_FACTORY(mail.get("smtp_host", "smtp.gmail.com"), int(mail.get("smtp_port", 465))) as s:
            s.login(mail["address"], mail["app_password"])
            s.send_message(msg, to_addrs=rcpts)
    except (smtplib.SMTPException, OSError) as e:
        print(f"Not sent: the mail server refused or could not be reached ({type(e).__name__}).")
        return 1
    tok["mail_used"] = True
    write_json_atomic(tok_path, tok)
    n = len(rcpts)
    copied = ", Kevin copied" if msg["Cc"] else ""
    print(f"Sent from Alfred's mail to {n} {'person' if n == 1 else 'people'}{copied}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
