"""`github_status`: read-only answers about the household's own GitHub repos (round 5,
Kevin 2026-11-10: "did the build pass", "what's open on legion", "recent commits",
Pages and Actions status).

**Read only, by construction.** The one network function, `_get`, sends `GET` and
nothing else; there is no other verb anywhere in this module, and the tool is
`writes=False` in the registry (`tests/test_github_status.py` asserts both). The
token Kevin creates is a fine-grained personal access token with read-only
permissions (Actions, Contents, Issues, Pull requests, Pages, Metadata), so even a
bug here could not write.

**Scoped to his repos.** `LEGION_GITHUB_REPOS` is the allowlist (`owner/name`,
comma separated). A repo not on it is refused in words before any request goes
out, whatever the model asks for; the fine-grained token is itself limited to the
repos Kevin selects when he creates it. Owner and repo names are configuration,
never code.

**Not configured is a sentence, not an empty answer.** No `LEGION_GITHUB_TOKEN` or
no `LEGION_GITHUB_REPOS`: the tool says nothing was read and why. A GitHub
failure (unreachable, 401, 403, 404, rate limit) says nothing is known, which is
not the same as "no builds" or "nothing open".

**Third-party text.** Issue and pull request titles may be written by someone other
than Kevin on a public repo, so `github_status` is in
`assistant.read_through.THIRD_PARTY_TOOLS`: a `remember` in the same web turn is
refused, the way `web_search` is. Nothing here is stored; the answer exists in the
tool result only.

API: GitHub REST v3, `https://api.github.com`, `X-GitHub-Api-Version: 2022-11-28`
(checked 2026-11-10 against the public `actions/runs`, `issues`, `pulls` and
`commits` endpoints; `pages` and `pages/builds/latest` answer 404 unauthenticated).
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass

API = "https://api.github.com"
API_VERSION = "2022-11-28"
TIMEOUT_SECONDS = 8
USER_AGENT = "LEGION engine (household assistant, https://github.com/kevinmyo-code/legion)"
TOKEN_ENV = "LEGION_GITHUB_TOKEN"
REPOS_ENV = "LEGION_GITHUB_REPOS"
LIST_MAX = 5

ASPECTS = ("builds", "open", "commits", "pages")

NOT_CONFIGURED = (
    "Nothing was read. GitHub is not set up on this engine: it needs a read-only GitHub "
    "token ({token}) and the list of repos it may read ({repos}). Until then nothing about "
    "builds, issues, pull requests or commits is known from here."
)


class GitHubFailed(Exception):
    """GitHub did not answer, or answered with an error. `message` is for the model."""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.message = message
        self.status = status


@dataclass(frozen=True)
class Config:
    token: str
    repos: tuple[str, ...]


def config() -> Config | None:
    """The token and the allowlist from the environment, or None when either is missing."""
    token = os.environ.get(TOKEN_ENV, "").strip()
    repos = tuple(
        r.strip().lower()
        for r in os.environ.get(REPOS_ENV, "").split(",")
        if r.strip().count("/") == 1 and all(part for part in r.strip().split("/"))
    )
    if not token or not repos:
        return None
    return Config(token=token, repos=repos)


def not_configured_sentence() -> str:
    return NOT_CONFIGURED.format(token=TOKEN_ENV, repos=REPOS_ENV)


def resolve_repo(cfg: Config, asked: str | None) -> tuple[str | None, str | None]:
    """(repo, None) for an allowed repo, or (None, refusal). A bare name ("legion")
    matches an allowed `owner/legion`; with nothing asked and one repo allowed, that one."""
    asked = (asked or "").strip().lower()
    if not asked:
        if len(cfg.repos) == 1:
            return cfg.repos[0], None
        return None, (
            "Nothing was read. More than one repo is set up; say which one: "
            + ", ".join(cfg.repos)
            + "."
        )
    if "/" in asked:
        if asked in cfg.repos:
            return asked, None
    else:
        matches = [r for r in cfg.repos if r.split("/", 1)[1] == asked]
        if len(matches) == 1:
            return matches[0], None
    return None, (
        f"Nothing was read. {asked!r} is not one of the repos this engine may read "
        f"({', '.join(cfg.repos)})."
    )


def _get(cfg: Config, path: str):
    """GET only. The one network call; tests replace it."""
    req = urllib.request.Request(
        f"{API}{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {cfg.token}",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": USER_AGENT,
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            raw = response.read()
    except urllib.error.HTTPError as exc:
        raise GitHubFailed(_http_sentence(exc.code), exc.code) from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise GitHubFailed(
            f"GitHub could not be reached ({type(exc).__name__}), so nothing is known."
        ) from exc
    try:
        return json.loads(raw)
    except ValueError as exc:
        raise GitHubFailed("GitHub answered with something that was not JSON.") from exc


def _http_sentence(code: int) -> str:
    if code == 401:
        return "GitHub refused the token (401): it may have expired or been revoked."
    if code == 403:
        return (
            "GitHub refused the request (403): the token lacks read permission for this, or "
            "the rate limit was hit."
        )
    if code == 404:
        return "GitHub says it is not there (404), or the token cannot see it."
    return f"GitHub answered with an error ({code})."


# --------------------------------------------------------------------------- formatting


def _day_time(iso: str | None) -> str:
    """'2026-11-09 21:44 UTC' from GitHub's ISO time, or 'an unknown time'."""
    if not iso or len(iso) < 16:
        return "an unknown time"
    return f"{iso[:10]} {iso[11:16]} UTC"


def summarize_runs(repo: str, body: dict) -> tuple[str, list[dict]]:
    runs = body.get("workflow_runs") if isinstance(body, dict) else None
    if not isinstance(runs, list):
        raise GitHubFailed("GitHub's Actions answer had no list of runs.")
    rows = []
    # Latest run per workflow: "did the build pass" means each workflow's newest run.
    seen: set[str] = set()
    for run in runs:
        name = str(run.get("name") or "workflow")
        if name in seen:
            continue
        seen.add(name)
        rows.append(
            {
                "workflow": name,
                "status": run.get("status"),
                "conclusion": run.get("conclusion"),
                "branch": run.get("head_branch"),
                "sha": str(run.get("head_sha") or "")[:8],
                "updated_at": run.get("updated_at"),
                "url": run.get("html_url"),
            }
        )
    if not rows:
        return f"GitHub Actions has no runs on {repo}. That is a real empty answer.", rows
    parts = []
    for r in rows:
        state = r["conclusion"] if r["status"] == "completed" else (r["status"] or "unknown")
        parts.append(
            f"{r['workflow']} on {r['branch']} ({r['sha']}): {state}, {_day_time(r['updated_at'])}"
        )
    return f"GitHub Actions on {repo}, latest run per workflow: " + "; ".join(parts) + ".", rows


def summarize_open(repo: str, issues: list, pulls: list) -> tuple[str, dict]:
    if not isinstance(issues, list) or not isinstance(pulls, list):
        raise GitHubFailed("GitHub's issues or pull requests answer was not a list.")
    # The issues endpoint lists pull requests too; they are counted once, as pulls.
    only_issues = [i for i in issues if "pull_request" not in i]
    pr_rows = [
        {
            "number": p.get("number"),
            "title": p.get("title"),
            "branch": (p.get("head") or {}).get("ref"),
            "draft": bool(p.get("draft")),
        }
        for p in pulls[:LIST_MAX]
    ]
    issue_rows = [
        {"number": i.get("number"), "title": i.get("title")} for i in only_issues[:LIST_MAX]
    ]
    if not pr_rows and not issue_rows:
        return (
            f"Nothing is open on {repo}: no open pull requests and no open issues. That is a "
            f"real empty answer.",
            {"pulls": [], "issues": []},
        )
    words = [f"Open on {repo} (titles may be written by anyone):"]
    words.append(
        f"{len(pulls)}{'+' if len(pulls) >= LIST_MAX else ''} pull request(s)"
        + (
            ": "
            + "; ".join(
                f"#{p['number']} {p['title']}" + (" (draft)" if p["draft"] else "") for p in pr_rows
            )
            if pr_rows
            else ""
        )
    )
    words.append(
        f"{len(only_issues)}{'+' if len(issues) >= LIST_MAX else ''} issue(s)"
        + (
            ": " + "; ".join(f"#{i['number']} {i['title']}" for i in issue_rows)
            if issue_rows
            else ""
        )
    )
    return " ".join(words[:1]) + " " + ". ".join(words[1:]) + ".", {
        "pulls": pr_rows,
        "issues": issue_rows,
    }


def summarize_commits(repo: str, body: list) -> tuple[str, list[dict]]:
    if not isinstance(body, list):
        raise GitHubFailed("GitHub's commits answer was not a list.")
    rows = []
    for c in body[:LIST_MAX]:
        commit = c.get("commit") or {}
        message = str(commit.get("message") or "").splitlines()[0] if commit.get("message") else ""
        rows.append(
            {
                "sha": str(c.get("sha") or "")[:8],
                "message": message[:120],
                "author": (commit.get("author") or {}).get("name"),
                "date": (commit.get("author") or {}).get("date"),
            }
        )
    if not rows:
        return f"{repo} has no commits on its default branch. That is a real empty answer.", rows
    return (
        f"Latest commits on {repo}'s default branch: "
        + "; ".join(f"{r['sha']} {r['message']} ({_day_time(r['date'])})" for r in rows)
        + ".",
        rows,
    )


def summarize_pages(repo: str, body: dict | None) -> tuple[str, dict]:
    if body is None:
        return (
            f"{repo} has no GitHub Pages site, or the token cannot read Pages (404). That is "
            f"not a failed build.",
            {},
        )
    status = body.get("status")
    error = (body.get("error") or {}).get("message")
    row = {
        "status": status,
        "updated_at": body.get("updated_at"),
        "commit": str(body.get("commit") or "")[:8],
        "error": error,
    }
    text = (
        f"GitHub Pages for {repo}: the latest build is {status or 'of unknown status'} "
        f"({row['commit']}, {_day_time(row['updated_at'])})"
    )
    if error:
        text += f", error: {error}"
    return text + ".", row


def status(aspect: str, repo: str, cfg: Config) -> tuple[str, dict]:
    """One aspect of one allowed repo, as (sentence, structured). Raises GitHubFailed."""
    if aspect == "builds":
        text, rows = summarize_runs(repo, _get(cfg, f"/repos/{repo}/actions/runs?per_page=20"))
        return text, {"runs": rows}
    if aspect == "open":
        issues = _get(cfg, f"/repos/{repo}/issues?state=open&per_page={LIST_MAX * 2}")
        pulls = _get(cfg, f"/repos/{repo}/pulls?state=open&per_page={LIST_MAX}")
        return summarize_open(repo, issues, pulls)
    if aspect == "commits":
        text, rows = summarize_commits(
            repo, _get(cfg, f"/repos/{repo}/commits?per_page={LIST_MAX}")
        )
        return text, {"commits": rows}
    if aspect == "pages":
        try:
            body = _get(cfg, f"/repos/{repo}/pages/builds/latest")
        except GitHubFailed as exc:
            if exc.status != 404:
                raise
            body = None
        return summarize_pages(repo, body)
    raise GitHubFailed(f"{aspect!r} is not something this tool reads.")
