"""`github_status` (round 5, 2026-11-10): read-only GitHub answers.

Fixtures are GitHub's real response SHAPES (recorded 2026-11-10 from the public
REST endpoints) with made-up repos, names and messages: no live call, no real
person. `conftest.py` makes `_get` fail unless a test hands it answers.
"""

from __future__ import annotations

import ast
import inspect
import urllib.error

import pytest

from engine_mcp import github_status as gh
from engine_mcp.tools import TOOLS_BY_NAME
from tests.test_engine_mcp import call, client_with

pytestmark = pytest.mark.django_db

REAL_GET = gh._get  # conftest replaces `_get`; this test drives the real one

RUNS = {
    "total_count": 3,
    "workflow_runs": [
        {
            "name": "Server",
            "status": "completed",
            "conclusion": "success",
            "head_branch": "dev",
            "head_sha": "abc1234567",
            "updated_at": "2026-11-09T21:44:10Z",
            "html_url": "https://github.com/example-user/sample/actions/runs/1",
        },
        {
            "name": "Android",
            "status": "in_progress",
            "conclusion": None,
            "head_branch": "dev",
            "head_sha": "abc1234567",
            "updated_at": "2026-11-09T21:45:00Z",
            "html_url": "https://github.com/example-user/sample/actions/runs/2",
        },
        {
            "name": "Server",
            "status": "completed",
            "conclusion": "failure",
            "head_branch": "dev",
            "head_sha": "0001112223",
            "updated_at": "2026-11-08T10:00:00Z",
            "html_url": "https://github.com/example-user/sample/actions/runs/0",
        },
    ],
}
ISSUES = [
    {"number": 12, "title": "Crash on rotate"},
    {"number": 11, "title": "Add dark mode", "pull_request": {"url": "x"}},
]
PULLS = [{"number": 11, "title": "Add dark mode", "head": {"ref": "feat/dark"}, "draft": True}]
COMMITS = [
    {
        "sha": "abc1234567def",
        "commit": {
            "message": "fix: rotate crash\n\nlong body",
            "author": {"name": "Sam Example", "date": "2026-11-09T21:40:00Z"},
        },
    },
]
PAGES = {
    "status": "built",
    "error": {"message": None},
    "commit": "abc1234567",
    "updated_at": "2026-11-09T22:00:00Z",
}


@pytest.fixture
def mcp(settings, household_user):
    settings.LEGION_MCP = True
    return client_with(household_user)


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv(gh.TOKEN_ENV, "test-token-not-real")
    monkeypatch.setenv(gh.REPOS_ENV, "example-user/sample")


def _answers(monkeypatch, table):
    asked = []

    def fake(cfg, path):
        asked.append(path)
        for prefix, answer in table.items():
            if path.startswith(prefix):
                if isinstance(answer, Exception):
                    raise answer
                return answer
        raise AssertionError(f"unexpected path {path}")

    monkeypatch.setattr(gh, "_get", fake)
    return asked


def test_not_configured_says_nothing_was_read(mcp, monkeypatch):
    monkeypatch.delenv(gh.TOKEN_ENV, raising=False)
    is_error, text, _ = call(mcp, "github_status", {"aspect": "builds"})
    assert is_error
    assert text.startswith("Nothing was read.") and "not set up" in text
    assert gh.TOKEN_ENV in text and gh.REPOS_ENV in text


def test_token_without_repos_is_not_configured(mcp, monkeypatch):
    monkeypatch.setenv(gh.TOKEN_ENV, "t")
    monkeypatch.setenv(gh.REPOS_ENV, "not-a-repo, ,/x")
    is_error, text, _ = call(mcp, "github_status", {"aspect": "open"})
    assert is_error and "not set up" in text


def test_builds_are_the_latest_run_per_workflow(mcp, configured, monkeypatch):
    asked = _answers(monkeypatch, {"/repos/example-user/sample/actions/runs": RUNS})
    is_error, text, structured = call(mcp, "github_status", {"aspect": "builds"})
    assert not is_error, text
    assert asked == ["/repos/example-user/sample/actions/runs?per_page=20"]
    assert text.startswith("From GitHub, read just now.")
    assert "Server on dev (abc12345): success, 2026-11-09 21:44 UTC" in text
    assert "Android on dev (abc12345): in_progress" in text
    assert "failure" not in text  # the older Server run is not the latest
    assert [r["workflow"] for r in structured["runs"]] == ["Server", "Android"]


def test_open_counts_pull_requests_once(mcp, configured, monkeypatch):
    _answers(
        monkeypatch,
        {
            "/repos/example-user/sample/issues": ISSUES,
            "/repos/example-user/sample/pulls": PULLS,
        },
    )
    is_error, text, structured = call(mcp, "github_status", {"aspect": "open", "repo": "sample"})
    assert not is_error, text
    assert "1 pull request(s): #11 Add dark mode (draft)" in text
    assert "1 issue(s): #12 Crash on rotate" in text
    assert "may be written by anyone" in text
    assert structured["issues"] == [{"number": 12, "title": "Crash on rotate"}]


def test_nothing_open_is_a_real_empty_answer(mcp, configured, monkeypatch):
    _answers(
        monkeypatch,
        {"/repos/example-user/sample/issues": [], "/repos/example-user/sample/pulls": []},
    )
    _, text, _ = call(mcp, "github_status", {"aspect": "open"})
    assert "Nothing is open on example-user/sample" in text and "real empty answer" in text


def test_commits_first_line_only(mcp, configured, monkeypatch):
    _answers(monkeypatch, {"/repos/example-user/sample/commits": COMMITS})
    _, text, structured = call(mcp, "github_status", {"aspect": "commits"})
    assert "abc12345 fix: rotate crash (2026-11-09 21:40 UTC)" in text
    assert "long body" not in text
    assert structured["commits"][0]["author"] == "Sam Example"


def test_pages_built_and_absent(mcp, configured, monkeypatch):
    _answers(monkeypatch, {"/repos/example-user/sample/pages": PAGES})
    _, text, _ = call(mcp, "github_status", {"aspect": "pages"})
    assert "latest build is built (abc12345, 2026-11-09 22:00 UTC)" in text
    _answers(monkeypatch, {"/repos/example-user/sample/pages": gh.GitHubFailed("404", 404)})
    is_error, text, _ = call(mcp, "github_status", {"aspect": "pages"})
    assert not is_error and "no GitHub Pages site" in text and "not a failed build" in text


def test_a_repo_off_the_allowlist_is_refused_before_any_request(mcp, configured, monkeypatch):
    asked = _answers(monkeypatch, {})
    for repo in ("someone-else/sample", "other", "example-user/other"):
        is_error, text, _ = call(mcp, "github_status", {"aspect": "builds", "repo": repo})
        assert is_error and text.startswith("Nothing was read.") and "not one of the repos" in text
    assert asked == []


def test_two_repos_need_a_name(mcp, monkeypatch):
    monkeypatch.setenv(gh.TOKEN_ENV, "t")
    monkeypatch.setenv(gh.REPOS_ENV, "example-user/one,example-user/two")
    is_error, text, _ = call(mcp, "github_status", {"aspect": "builds"})
    assert is_error and "say which one" in text


def test_github_failure_is_not_an_empty_answer(mcp, configured, monkeypatch):
    _answers(monkeypatch, {"/repos/": gh.GitHubFailed(gh._http_sentence(401), 401)})
    is_error, text, _ = call(mcp, "github_status", {"aspect": "builds"})
    assert is_error and text.startswith("Nothing was read from GitHub")
    assert "refused the token (401)" in text and "not the same as no builds" in text


def test_get_sends_get_with_a_timeout_and_words_http_errors(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout):
        seen["method"] = req.get_method()
        seen["timeout"] = timeout
        seen["auth"] = req.get_header("Authorization")
        raise urllib.error.HTTPError(req.full_url, 403, "Forbidden", {}, None)

    monkeypatch.setattr(gh.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(gh.GitHubFailed) as exc:
        REAL_GET(gh.Config("tok", ("a/b",)), "/repos/a/b/commits")
    assert seen == {"method": "GET", "timeout": gh.TIMEOUT_SECONDS, "auth": "Bearer tok"}
    assert exc.value.status == 403 and "403" in exc.value.message


def test_read_only_by_construction():
    """The tool is not a write, and the module names no verb but GET."""
    assert TOOLS_BY_NAME["github_status"].writes is False
    source = inspect.getsource(gh)
    tree = ast.parse(source)
    methods = {
        kw.value.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        for kw in node.keywords
        if kw.arg == "method" and isinstance(kw.value, ast.Constant)
    }
    assert methods == {"GET"}
    for verb in ("POST", "PUT", "PATCH", "DELETE"):
        assert f'"{verb}"' not in source and f"'{verb}'" not in source


def test_github_status_is_read_through():
    from assistant import read_through

    assert "github_status" in read_through.THIRD_PARTY_TOOLS
