"""Download one file into the task's work folder, from a short list of hosts (containment, 2026-11-10).

    python fetch_file.py --task 42 --url https://www.gutenberg.org/ebooks/345.epub3.images --name dracula.epub

What a helper uses instead of curl or a Python one-liner to save a file from the web (book-finder's
legal free copy is the case it is for). Shell network commands are allowlisted to github, the
engine, pypi and npm (`containment.py`); this script is the one other door, and it checks:

- https only, and the host (and every redirect's host) is on `download_hosts` (config) or the
  defaults below: free-book sources and GitHub's own;
- the file lands in `work/task-<N>/` under a plain file name, at most 100 MB;
- the file goes on the laptop's written list, so no shell command ever runs or handles it (it is
  data from the web); the hook marks the task read-through when this runs.

Prints one line, in words, saying what was saved or why not. Never prints the URL's query.
"""
from __future__ import annotations

import argparse
import re
import sys
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import containment as C  # noqa: E402
from common import Paths, load_config  # noqa: E402

CAP_BYTES = 100 * 1024 * 1024
DEFAULT_HOSTS = ("standardebooks.org", "gutenberg.org", "gutenberg.pglaf.org", "wikisource.org",
                 "archive.org", "github.com", "githubusercontent.com")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,120}$")


class Refused(Exception):
    pass


def allowed_hosts(cfg: dict) -> set[str]:
    return {h.lower() for h in (cfg.get("download_hosts") or DEFAULT_HOSTS)}


def check_url(url: str, hosts: set[str]) -> str:
    u = urlparse(url)
    if u.scheme != "https" or not u.hostname:
        raise Refused("only an https address can be downloaded")
    if not C.host_allowed(u.hostname, hosts):
        raise Refused(f"{u.hostname} is not one of the download sources this laptop allows")
    return u.hostname


class _Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts: set[str]):
        self.hosts = hosts

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl, self.hosts)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(paths: Paths, cfg: dict, task: int, url: str, name: str | None, opener=None) -> Path:
    hosts = allowed_hosts(cfg)
    check_url(url, hosts)
    name = name or Path(urlparse(url).path).name or "download"
    if not NAME_RE.match(name) or name in (".", ".."):
        raise Refused("the file name must be a plain name, letters, digits, spaces, dots or dashes")
    work = paths.task_work(task)
    if not work.is_dir():
        raise Refused(f"task {task} has no work folder on this laptop")
    dest = work / name
    if dest.exists():
        raise Refused("a file with that name is already in the work folder")
    opener = opener or urllib.request.build_opener(_Redirects(hosts))
    req = urllib.request.Request(url, headers={"User-Agent": "legion-claude-worker"})
    total = 0
    tmp = dest.with_name(dest.name + ".part")
    try:
        with opener.open(req, timeout=60) as r, open(tmp, "wb") as f:
            final = getattr(r, "geturl", lambda: url)()
            check_url(final, hosts)
            while True:
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                total += len(chunk)
                if total > CAP_BYTES:
                    raise Refused("the file is over 100 MB")
                f.write(chunk)
        C.record_written(paths, str(dest))
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)
    return dest


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="fetch_file.py")
    ap.add_argument("--task", type=int, required=True)
    ap.add_argument("--url", required=True)
    ap.add_argument("--name")
    a = ap.parse_args(argv)
    paths = Paths()
    try:
        dest = fetch(paths, load_config(paths), a.task, a.url, a.name)
    except Refused as e:
        print(f"Not downloaded: {e}.")
        return 1
    except OSError as e:
        print(f"Not downloaded: the download failed ({type(e).__name__}).")
        return 1
    size = dest.stat().st_size
    print(f"Saved {dest.name}, {max(1, size // 1024)} KB, in the work folder: {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
