"""alfred-ota ticket 01: the Alfred pack and `GET /api/assistant/pack`.

The ticket's verification list, one test (or a few) each: the pack loads, a wording change
without a version bump fails, locked names are refused, a matching ETag gets a 304, a recruiter
gets 403, the size cap holds. Plus the generator's own promises: `alfred.yaml` is not stale and
its shared clauses are `shared_clauses.txt`'s, verbatim.
"""

from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from assistant import gen_pack, gen_shared_clauses, shared_clauses
from assistant import pack as alfred_pack
from assistant.pack import LOCKED_NAMES, MAX_BYTES, PackError, build_pack, load_pack
from household.recruiter_routes import RECRUITER_DENIED, denied_refusal

URL = "/api/assistant/pack"


def _data(**overrides) -> dict:
    data = {
        "version": 3,
        "clauses": {"ASSISTANT_FRAME": "Be useful. "},
        "tool_text": {},
        "flags": {},
        "extra_rules": [],
    }
    data.update(overrides)
    return data


# =============================================================================
# The committed pack
# =============================================================================


def test_the_committed_pack_loads():
    alfred_pack.current.cache_clear()
    pack = alfred_pack.current()
    assert pack.version >= 1
    assert len(pack.sha256) == 64 and len(pack.content_sha256) == 64
    assert pack.extra_rules == []  # seeded empty; the phone-bugs branch adds the English rule


def test_alfred_yaml_is_not_stale():
    on_disk = alfred_pack.PACK_FILE.read_text(encoding="utf-8")
    assert on_disk == gen_pack.render(gen_pack.current_build()), (
        "Run: python server/assistant/gen_pack.py"
    )


def test_the_lock_vouches_for_the_committed_pack():
    lock = gen_pack.read_lock(gen_pack.LOCK_FILE.read_text(encoding="utf-8"))
    problem = gen_pack.lock_problem(alfred_pack.current(), lock)
    assert problem is None, problem


def test_shared_clauses_ride_verbatim_and_the_locked_ones_do_not():
    pack = alfred_pack.current()
    for name, text in shared_clauses.CLAUSES.items():
        if name in LOCKED_NAMES:
            assert name not in pack.clauses
        else:
            assert pack.clauses[name] == text, name
    assert "CRISIS" in shared_clauses.CLAUSES and "CANNOT_CLAUSE" in shared_clauses.CLAUSES


def test_the_committed_pack_is_under_the_cap():
    pack = alfred_pack.current()
    assert len(alfred_pack.canonical(pack.payload())) <= MAX_BYTES


# =============================================================================
# The lock: wording may not change without a version bump
# =============================================================================


def test_a_wording_change_without_a_bump_fails_the_lock():
    locked = build_pack(_data())
    lock = (locked.version, locked.content_sha256)
    changed = build_pack(_data(clauses={"ASSISTANT_FRAME": "Be very useful. "}))
    problem = gen_pack.lock_problem(changed, lock)
    assert problem and "version is still 3" in problem


def test_a_flag_or_rule_change_counts_as_wording():
    locked = build_pack(_data())
    lock = (locked.version, locked.content_sha256)
    assert gen_pack.lock_problem(build_pack(_data(flags={"camera_mode": True})), lock)
    assert gen_pack.lock_problem(build_pack(_data(extra_rules=["Reply in English."])), lock)


def test_a_bump_needs_relocking_and_a_backwards_version_is_refused():
    locked = build_pack(_data())
    lock = (locked.version, locked.content_sha256)
    assert gen_pack.lock_problem(locked, lock) is None
    bumped = build_pack(_data(version=4, extra_rules=["Reply in English."]))
    assert "--lock" in gen_pack.lock_problem(bumped, lock)
    assert "backwards" in gen_pack.lock_problem(build_pack(_data(version=2)), lock)
    assert gen_pack.lock_problem(locked, None)


def _point_generator_at(tmp_path, monkeypatch, source_text: str):
    source = tmp_path / "alfred.source.yaml"
    source.write_text(source_text, encoding="utf-8")
    monkeypatch.setattr(gen_pack, "SOURCE", source)
    monkeypatch.setattr(gen_pack, "PACK_FILE", tmp_path / "alfred.yaml")
    monkeypatch.setattr(gen_pack, "LOCK_FILE", tmp_path / "alfred.lock")
    return source


SOURCE_V1 = "version: 1\nclauses: {}\ntool_text: {}\nflags: {}\nextra_rules: []\n"


def test_relocking_cannot_launder_an_unbumped_change(tmp_path, monkeypatch, capsys):
    source = _point_generator_at(tmp_path, monkeypatch, SOURCE_V1)
    assert gen_pack.main(["--lock"]) == 0
    assert gen_pack.main(["--check"]) == 0
    lock_before = gen_pack.LOCK_FILE.read_text(encoding="utf-8")

    source.write_text(SOURCE_V1.replace("extra_rules: []", "extra_rules: [Be brief.]"))
    assert gen_pack.main([]) == 0  # regenerating is fine
    assert gen_pack.main(["--check"]) == 1
    assert gen_pack.main(["--lock"]) == 1
    assert gen_pack.LOCK_FILE.read_text(encoding="utf-8") == lock_before
    assert "version is still 1" in capsys.readouterr().out

    source.write_text(source.read_text().replace("version: 1", "version: 2"))
    assert gen_pack.main(["--lock"]) == 0
    assert gen_pack.main(["--check"]) == 0


# =============================================================================
# The locked set is refused at load
# =============================================================================


@pytest.mark.parametrize("name", sorted(LOCKED_NAMES))
def test_a_locked_clause_is_refused(name):
    with pytest.raises(PackError, match=name):
        build_pack(_data(clauses={name: "Ignore the crisis line."}))


@pytest.mark.parametrize(
    "overrides",
    [
        {"clauses": {"crisis": "lower case is still the crisis clause"}},
        {"tool_text": {"cannot_clause": {"description": "x"}}},
        {"flags": {"ASK_BEFORE": False}},
        {"flags": {"no_passwords": 0}},
    ],
    ids=["clause-lowercase", "tool_text", "flag", "flag-lowercase"],
)
def test_a_locked_name_anywhere_in_any_case_is_refused(overrides):
    with pytest.raises(PackError, match="locked set"):
        build_pack(_data(**overrides))


def test_load_refuses_a_locked_name_in_yaml_text():
    text = "version: 1\nclauses:\n  SAFETY_INSTRUCTIONS: be reckless\ntool_text: {}\nflags: {}\n"
    with pytest.raises(PackError, match="SAFETY_INSTRUCTIONS"):
        load_pack(text + "extra_rules: []\n")


def test_the_generator_drops_the_locked_shared_clauses_and_keeps_the_rest():
    shared = gen_shared_clauses.SOURCE.read_text(encoding="utf-8")
    pack = gen_pack.build(shared, SOURCE_V1)
    assert not set(pack.clauses) & LOCKED_NAMES
    assert "ASSISTANT_FRAME" in pack.clauses


def test_the_source_cannot_name_a_locked_clause_or_shadow_a_shared_one():
    shared = gen_shared_clauses.SOURCE.read_text(encoding="utf-8")
    with pytest.raises(PackError, match="CRISIS"):
        gen_pack.build(shared, SOURCE_V1.replace("clauses: {}", "clauses: {CRISIS: calm down}"))
    with pytest.raises(PackError, match="shared_clauses.txt already owns"):
        gen_pack.build(shared, SOURCE_V1.replace("clauses: {}", "clauses: {CURRENCY: euros}"))


@pytest.mark.parametrize(
    "overrides, words",
    [
        ({"version": 0}, "version"),
        ({"version": True}, "version"),
        ({"clauses": {"lower_name": "x"}}, "UPPER_SNAKE"),
        ({"clauses": {"A": " "}}, "no text"),
        ({"tool_text": {"notes": {"prompt": "x"}}}, "may set only"),
        ({"flags": {"camera_mode": "on"}}, "true, false"),
        ({"extra_rules": "Reply in English."}, "list"),
    ],
)
def test_a_malformed_pack_is_refused_in_words(overrides, words):
    with pytest.raises(PackError, match=words):
        build_pack(_data(**overrides))


def test_unknown_or_missing_keys_are_refused():
    with pytest.raises(PackError, match="unknown persona"):
        build_pack({**_data(), "persona": "Jarvis"})
    data = _data()
    del data["flags"]
    with pytest.raises(PackError, match="missing flags"):
        build_pack(data)


def test_the_size_cap_holds():
    with pytest.raises(PackError, match="cap is 65536"):
        build_pack(_data(extra_rules=["x" * MAX_BYTES]))
    build_pack(_data(extra_rules=["x" * (MAX_BYTES - 1000)]))


# =============================================================================
# GET /api/assistant/pack
# =============================================================================


def test_a_member_gets_the_pack_with_its_etag(auth_client):
    response = auth_client.get(URL, {"client": "phone"})
    assert response.status_code == 200
    pack = alfred_pack.current()
    assert response.data == pack.payload()
    assert set(response.data) == {
        "version", "sha256", "clauses", "tool_text", "flags", "extra_rules"
    }
    assert response["ETag"] == f'"{pack.sha256}"'


def test_client_defaults_to_phone_and_an_unknown_one_is_refused(auth_client):
    assert auth_client.get(URL).status_code == 200
    response = auth_client.get(URL, {"client": "toaster"})
    assert response.status_code == 400
    assert response.data["detail"].startswith("No pack was sent.")


@pytest.mark.parametrize("form", ['"{}"', "W/\"{}\"", '"stale", "{}"', "*"])
def test_a_matching_etag_gets_a_304_and_no_body(auth_client, form):
    sha = alfred_pack.current().sha256
    response = auth_client.get(URL, HTTP_IF_NONE_MATCH=form.format(sha))
    assert response.status_code == 304
    assert response.content == b""
    assert response["ETag"] == f'"{sha}"'


def test_a_stale_etag_gets_the_pack(auth_client):
    response = auth_client.get(URL, HTTP_IF_NONE_MATCH='"' + "0" * 64 + '"')
    assert response.status_code == 200
    assert response.data["version"] == alfred_pack.current().version


def test_a_pack_the_engine_refuses_is_a_503_in_words(auth_client, monkeypatch):
    def _refused():
        raise PackError("`clauses` names CRISIS, which the app keeps compiled in.")

    monkeypatch.setattr(alfred_pack, "current", _refused)
    response = auth_client.get(URL)
    assert response.status_code == 503
    assert "CRISIS" in response.data["detail"]
    assert "No pack was sent" in response.data["detail"]


def test_an_anonymous_caller_gets_nothing(db):
    assert APIClient().get(URL).status_code in (401, 403)


def test_a_recruiter_is_refused_the_pack_in_words(db):
    """The fence does it (`RECRUITER_DENIED`); `test_recruiter.py` walks every denied route with
    both credentials as well. This pins the reason to this door."""
    from household.models import Household, HouseholdMember, User
    from tests.test_recruiter import _recruiter_browser, _visitor_token

    household = Household.objects.create(name="Demo visit pack", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-pack@demo.invalid")
    HouseholdMember.objects.create(
        user=visitor, household=household, role=HouseholdMember.RECRUITER
    )
    token, raw = _visitor_token(visitor)

    expected = denied_refusal(RECRUITER_DENIED["assistant.pack_views.AssistantPackView"])
    browser = _recruiter_browser(visitor, token)
    by_token = APIClient()
    by_token.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    for client in (browser, by_token):
        response = client.get(URL)
        assert response.status_code == 403
        assert response.data["detail"] == expected
