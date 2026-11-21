"""The garage assistant, option B slice 1: the manifest, words to ids, the sheet, the spoken lines,
`show_part`'s results and the schematic GLB. Every figure here is MADE UP (no real torque)."""

from __future__ import annotations

import datetime as dt
import math
import re
from decimal import Decimal
from pathlib import Path

import pytest
from django.core.management import CommandError, call_command

from agents.legion.speech_rules import torque_figures
from engine_mcp.tools import ENDS_TURN, FOR_MODEL, SPEAK_VERBATIM
from garage import glb, specs
from garage import resolve as r
from garage import xj_front as m
from garage.models import FastenerSpec
from garage.tool import CARD, SHOW_PART, SPOKEN_FIGURES

SERVER = Path(__file__).resolve().parents[1]
EXAMPLE = SERVER / "garage" / "example_specs.csv"
COMMITTED_GLB = SERVER / "frontend" / "public" / "garage" / f"{m.MODEL}.glb"

PART_ID = re.compile(r"^xj\.[a-z]+\.[a-z_]+(\.(driver|passenger))?$")
FASTENER_ID = re.compile(r"^xj\.[a-z]+\.[a-z_]+(\.(driver|passenger))?@[a-z0-9_]+$")


# =============================================================================
# The manifest
# =============================================================================


def test_every_id_is_stable_shaped_and_unique():
    ids = [p.id for p in m.PARTS] + [f.id for f in m.FASTENERS]
    assert len(ids) == len(set(ids))
    assert all(PART_ID.match(p.id) for p in m.PARTS)
    assert all(FASTENER_ID.match(f.id) and f.id.startswith(f.part + "@") for f in m.FASTENERS)
    assert all(f.part in m.PARTS_BY_ID for f in m.FASTENERS)


def test_the_front_end_has_every_fastener_of_the_schematic():
    # The schematic's 41 markers, with the track bar bracket's one marker split into its three.
    assert len(m.FASTENERS) == 43
    assert len(m.fasteners_of("xj.susp.track_bar")) == 5


def test_every_view_is_a_finite_point_away_from_its_target():
    for item in (*m.PARTS, *m.FASTENERS):
        view = item.view.as_dict()
        assert all(math.isfinite(v) for v in view["target"] + view["from"])
        assert view["target"] != view["from"]


def test_the_manifest_states_no_figure():
    # Torque, sockets and threads come only from the checked table, never from the public repo.
    for p in m.PARTS:
        for text in (p.name, p.where, *p.aliases):
            assert torque_figures(text) == [], text
    for f in m.FASTENERS:
        for text in (f.name, f.where, f.kind, *f.joint_words, *f.aliases):
            assert torque_figures(text) == [], text


# =============================================================================
# Words to an id (golden phrasings, made-up wording)
# =============================================================================


@pytest.mark.parametrize(
    "words,fastener",
    [
        ("where is the track bar nut at the frame", "xj.susp.track_bar@frame_end"),
        ("the track bar bolt on the axle", "xj.susp.track_bar@axle_end"),
        ("panhard bar axle end", "xj.susp.track_bar@axle_end"),
        ("track bar ball joint end", "xj.susp.track_bar@frame_end"),
        ("track bar bracket nut", "xj.susp.track_bar@frame_bracket_nut"),
        ("drag link at the pitman arm", "xj.steer.drag_link@pitman_end"),
        ("drag link knuckle end", "xj.steer.drag_link@knuckle_end"),
        ("tie rod end at the knuckle", "xj.steer.tie_rod@knuckle_end"),
        ("where the tie rod joins the drag link", "xj.steer.tie_rod@drag_link_end"),
        ("tie rod sleeve clamps", "xj.steer.tie_rod_sleeve@clamps"),
        ("pitman nut", "xj.steer.pitman_arm@shaft_nut"),
        ("pitman arm nut", "xj.steer.pitman_arm@shaft_nut"),
        ("driver side axle nut", "xj.axle.hub.driver@axle_nut"),
        ("left lower control arm frame end", "xj.susp.lca.driver@frame_end"),
        ("passenger upper control arm bolt at the axle", "xj.susp.uca.passenger@axle_end"),
        ("right upper ball joint", "xj.axle.ball_joint_upper.passenger@stud_nut"),
        ("driver's lower ball joint nut", "xj.axle.ball_joint_lower.driver@stud_nut"),
        ("sway bar link top on the driver side", "xj.susp.sway_link.driver@top"),
        ("sway bar clamp passenger side", "xj.susp.sway_bar@frame_clamp_passenger"),
        ("steering stabilizer at the drag link", "xj.steer.stabilizer@drag_link_end"),
        ("passenger shock top nut", "xj.susp.shock.passenger@top_nut"),
    ],
)
def test_spoken_words_land_on_one_fastener(words, fastener):
    found = r.resolve(words)
    assert found.kind == r.FASTENER, (words, found.kind, found.candidates)
    assert found.fastener.id == fastener


@pytest.mark.parametrize(
    "words,ids",
    [
        (
            "the lower control arm bolt at the axle",
            {"xj.susp.lca.driver@axle_end", "xj.susp.lca.passenger@axle_end"},
        ),
        (
            "upper ball joint",
            {
                "xj.axle.ball_joint_upper.driver@stud_nut",
                "xj.axle.ball_joint_upper.passenger@stud_nut",
            },
        ),
        (
            "ball joint on the driver side",
            {
                "xj.axle.ball_joint_upper.driver@stud_nut",
                "xj.axle.ball_joint_lower.driver@stud_nut",
            },
        ),
        ("the sleeve", {"xj.steer.tie_rod_sleeve@clamps", "xj.steer.drag_link_sleeve@clamps"}),
        ("control arm on the passenger side", {"xj.susp.lca.passenger", "xj.susp.uca.passenger"}),
        ("axle nut", {"xj.axle.hub.driver@axle_nut", "xj.axle.hub.passenger@axle_nut"}),
    ],
)
def test_two_matches_are_asked_about_never_guessed(words, ids):
    found = r.resolve(words)
    assert found.kind == r.AMBIGUOUS
    assert {c.id for c in found.candidates} == ids


def test_a_part_with_no_end_named_lists_its_fasteners():
    found = r.resolve("where is the track bar")
    assert found.kind == r.PART and found.part.id == "xj.susp.track_bar"


def test_words_naming_nothing_on_the_model_match_nothing():
    assert r.resolve("where is the alternator belt").kind == r.NONE


def test_a_follow_up_id_must_be_on_the_model():
    assert r.by_id(None, "xj.susp.track_bar@moon", "", None).kind == r.NONE
    assert r.by_id("xj.susp.lca.driver", None, "frame end", None).fastener.id == (
        "xj.susp.lca.driver@frame_end"
    )


def test_the_side_argument_wins_over_side_words():
    found = r.resolve("left axle nut", side=m.PASSENGER)
    assert found.fastener.id == "xj.axle.hub.passenger@axle_nut"


# =============================================================================
# The checking sheet (made-up rows)
# =============================================================================

HEADER = ",".join(specs.COLUMNS) + "\n"


def _sheet(tmp_path, *rows: str) -> Path:
    path = tmp_path / "sheet.csv"
    path.write_text(HEADER + "".join(row + "\n" for row in rows), encoding="utf-8")
    return path


TICKED = (
    "xj.susp.track_bar@frame_end,12,,ft-lb,,,a made-up truck,Example book,Page 1,yes,2000-01-02"
)


def test_the_example_sheet_loads_its_ticked_row_and_skips_the_other():
    known = {"example.widget@left_bolt", "example.widget@right_bolt"}
    rows, skipped, problems = specs.read_sheet(EXAMPLE, known)
    assert problems == []
    assert [x.fastener_id for x in rows] == ["example.widget@left_bolt"] and skipped == 1


def test_a_ticked_row_becomes_a_row(tmp_path):
    rows, skipped, problems = specs.read_sheet(_sheet(tmp_path, TICKED), m.FASTENERS_BY_ID)
    assert problems == [] and skipped == 0
    assert rows[0].torque_min == Decimal(12) and rows[0].unit == "ft-lb"
    assert rows[0].verified_on == dt.date(2000, 1, 2)


def test_an_unticked_row_with_a_figure_is_skipped_so_it_can_never_be_spoken(tmp_path):
    row = TICKED.replace(",yes,", ",,")
    rows, skipped, problems = specs.read_sheet(_sheet(tmp_path, row), m.FASTENERS_BY_ID)
    assert rows == [] and skipped == 1 and problems == []


@pytest.mark.parametrize(
    "row,problem",
    [
        (TICKED.replace(",ft-lb,", ",,"), "verified_unit"),
        (TICKED.replace(",12,", ",abc,"), "verified_torque"),
        (TICKED.replace("Example book", "Some forum thread"), "forum"),
        (TICKED.replace(",12,", ",450,"), "typo"),
        (TICKED.replace("Page 1", ""), "source_page is empty"),
        (TICKED.replace("a made-up truck", ""), "applies_to is empty"),
        (TICKED.replace("2000-01-02", "Jan 2"), "verified_on"),
        (TICKED.replace(",12,,", ",12,10,"), "below"),
        (TICKED.replace("frame_end", "moon_end"), "not a fastener"),
    ],
)
def test_a_bad_ticked_row_is_named_and_nothing_loads(tmp_path, row, problem):
    rows, _skipped, problems = specs.read_sheet(_sheet(tmp_path, row), m.FASTENERS_BY_ID)
    assert len(problems) == 1 and problem in problems[0]


def test_the_same_fastener_twice_is_a_problem(tmp_path):
    _rows, _skipped, problems = specs.read_sheet(
        _sheet(tmp_path, TICKED, TICKED), m.FASTENERS_BY_ID
    )
    assert "twice" in problems[0]


@pytest.mark.django_db
def test_the_loader_replaces_the_table_and_loads_nothing_on_a_problem(tmp_path):
    call_command("load_garage_specs", str(_sheet(tmp_path, TICKED)))
    assert FastenerSpec.objects.filter(model=m.MODEL).count() == 1
    bad = _sheet(tmp_path, TICKED.replace(",ft-lb,", ",,"))
    with pytest.raises(CommandError, match="Nothing was loaded"):
        call_command("load_garage_specs", str(bad))
    assert FastenerSpec.objects.filter(model=m.MODEL).count() == 1
    # Un-ticked on the sheet: gone from the table, so no longer spoken.
    call_command("load_garage_specs", str(_sheet(tmp_path, TICKED.replace(",yes,", ",,"))))
    assert not FastenerSpec.objects.filter(model=m.MODEL).exists()


@pytest.mark.django_db
def test_a_dry_run_stores_nothing(tmp_path):
    call_command("load_garage_specs", str(_sheet(tmp_path, TICKED)), "--dry-run")
    assert not FastenerSpec.objects.exists()


# =============================================================================
# show_part's results (made-up figures)
# =============================================================================


def _store(fid="xj.susp.track_bar@frame_end", low="12", high=None, **extra):
    return FastenerSpec.objects.create(
        model=m.MODEL,
        fastener_id=fid,
        torque_min=Decimal(low),
        torque_max=Decimal(high) if high else None,
        unit=extra.get("unit", "ft-lb"),
        socket=extra.get("socket", ""),
        thread=extra.get("thread", ""),
        applies_to="a made-up truck",
        source="Example book",
        source_page="Page 1",
        verified_on=dt.date(2000, 1, 2),
    )


def _run(**args):
    return SHOW_PART.handler(None, {"words": "", **args})


@pytest.mark.django_db
def test_an_unchecked_fastener_is_shown_and_said_with_no_number():
    result = _run(words="track bar frame end")
    data = result.structured
    assert result.card["card"] == CARD
    assert result.card["fastener_id"] == "xj.susp.track_bar@frame_end"
    assert data[SPEAK_VERBATIM][-1] == specs.NOT_VERIFIED and data[ENDS_TURN] is True
    assert data[SPOKEN_FIGURES] == []
    assert all(torque_figures(line) == [] for line in data[SPEAK_VERBATIM])
    assert not any(f.get("figure") for f in result.card["facts"])


@pytest.mark.django_db
def test_a_checked_fastener_is_said_with_its_figure_source_and_date():
    _store(high="14", socket="10 mm")
    data = _run(words="track bar frame end").structured
    line = data[SPEAK_VERBATIM][1]
    assert line.startswith("12 to 14 foot-pounds, for a made-up truck. From Example book, Page 1")
    assert "January 2" in line
    assert "socket 10 mm" in data[SPEAK_VERBATIM][2]
    assert set(data[SPOKEN_FIGURES]) == {"12", "14", "10"}


@pytest.mark.django_db
def test_the_model_is_never_handed_a_figure():
    _store()
    result = _run(words="track bar frame end")
    for text in (result.text, result.structured[FOR_MODEL]):
        assert torque_figures(text) == []
        assert "12" not in text


@pytest.mark.django_db
def test_the_card_shows_both_units_for_a_checked_row_only():
    _store()
    facts = {f["fastener_id"]: f for f in _run(words="track bar frame end").card["facts"]}
    assert facts["xj.susp.track_bar@frame_end"]["figure"] == "12 ft-lb (16 N·m)"
    assert facts["xj.susp.track_bar@axle_end"]["checked"] is False
    assert "figure" not in facts["xj.susp.track_bar@axle_end"]


@pytest.mark.django_db
def test_two_matches_return_candidates_and_no_card():
    result = _run(words="axle nut")
    assert result.card is None and result.structured["match"] == r.AMBIGUOUS
    assert {c["fastener_id"] for c in result.structured["candidates"]} == {
        "xj.axle.hub.driver@axle_nut",
        "xj.axle.hub.passenger@axle_nut",
    }


def test_nothing_matched_shows_nothing():
    result = _run(words="alternator")
    assert result.card is None and result.structured["match"] == r.NONE


@pytest.mark.django_db
def test_a_castle_nut_gets_the_cotter_pin_line_only_with_a_figure():
    _store(fid="xj.steer.tie_rod@knuckle_end")
    lines = _run(fastener_id="xj.steer.tie_rod@knuckle_end").structured[SPEAK_VERBATIM]
    assert lines[-1] == specs.CASTLE


# =============================================================================
# The schematic GLB
# =============================================================================


def _shape(gltf: dict) -> dict:
    """The GLB's JSON with float bounds dropped: libm may differ in a last bit across machines."""
    for accessor in gltf["accessors"]:
        accessor.pop("min", None)
        accessor.pop("max", None)
    return gltf


def test_the_committed_glb_is_the_manifests_build():
    committed = COMMITTED_GLB.read_bytes()
    assert len(committed) < 4 * 1024 * 1024
    assert _shape(glb.read_json(committed)) == _shape(glb.read_json(glb.build()))


def test_every_manifest_id_is_one_node_of_the_glb():
    gltf = glb.read_json(COMMITTED_GLB.read_bytes())
    ids = [n["extras"]["legion"]["id"] for n in gltf["nodes"]]
    assert len(ids) == len(set(ids))
    assert set(ids) == {p.id for p in m.PARTS} | {f.id for f in m.FASTENERS} | {m.MODEL}
    assert "images" not in gltf and "textures" not in gltf
