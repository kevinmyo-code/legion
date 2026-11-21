"""The garage assistant, option B slice 1: the manifest, words to ids, the sheet, the spoken lines,
`show_part`'s results and the schematic GLB. Every figure here is MADE UP (no real torque)."""

from __future__ import annotations

import csv
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
from garage.models import FastenerSpec, GarageTruck
from garage.tool import CARD, SHOW_PART, SPOKEN_FIGURES

SERVER = Path(__file__).resolve().parents[1]
EXAMPLE = SERVER / "garage" / "example_specs.csv"
EXAMPLE_TRUCK = SERVER / "garage" / "example_truck.csv"
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
        ("pitman arm shaft nut", "xj.steer.pitman_arm@shaft_nut"),
        ("sector shaft nut", "xj.steer.pitman_arm@shaft_nut"),
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
        # The blind review's case: the arm's own sector shaft nut, or the drag link's castle nut
        # at the arm. Their figures differ a lot, so code never picks one.
        ("pitman arm nut", {"xj.steer.pitman_arm@shaft_nut", "xj.steer.drag_link@pitman_end"}),
        ("pitman nut", {"xj.steer.pitman_arm@shaft_nut", "xj.steer.drag_link@pitman_end"}),
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


JOINS = [(f.id, part) for f in m.FASTENERS for part in f.meets]


def test_the_joins_table_names_real_ids_and_covers_every_rod_end():
    assert all(m.PARTS_BY_ID.get(part) for _, part in JOINS)
    # Every steering joint that lands on another part says which part.
    for fid in ("xj.steer.drag_link@pitman_end", "xj.steer.tie_rod@drag_link_end"):
        assert m.FASTENERS_BY_ID[fid].meets


@pytest.mark.parametrize("fastener,part", JOINS)
def test_naming_a_part_asks_about_every_fastener_that_joins_it(fastener, part):
    # Generated from the manifest: a fastener added to a joint later is covered the same day.
    found = r.resolve(f"the {m.PARTS_BY_ID[part].aliases[0]} fastener")
    assert found.kind == r.AMBIGUOUS, (part, found.kind)
    assert fastener in {c.id for c in found.candidates}


@pytest.mark.parametrize("words", ["the nut", "that bolt", "the stud nut"])
def test_a_bare_nut_or_bolt_picks_nothing(words):
    assert r.resolve(words).kind == r.NONE


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
# The truck profile and the checking sheet (made-up truck, made-up rows)
# =============================================================================

TRUCK = specs.Truck(2000, "9.9", "Example axle", Decimal(0))
TICKED = {
    "fastener_id": "xj.susp.track_bar@frame_end",
    "on_truck": "stock",
    "verified_torque": "12",
    "verified_torque_max": "",
    "verified_unit": "ft-lb",
    "verified_socket": "",
    "verified_thread": "",
    "year_from": "1999",
    "year_to": "2001",
    "engine": "9.9",
    "axle": "any",
    "hardware": "stock",
    "kit_name": "",
    "source_kind": "fsm",
    "source": "Example book",
    "source_page": "Page 1",
    "verified": "yes",
    "verified_on": "2000-01-02",
}
MOCK = ("mockup_torque", "mockup_torque_source")


def _row(**change) -> dict:
    return {**TICKED, **change}


def _sheet(tmp_path, *rows: dict) -> Path:
    path = tmp_path / "sheet.csv"
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=[*specs.COLUMNS, *MOCK])
        writer.writeheader()
        for row in rows:
            writer.writerow({**dict.fromkeys(MOCK, ""), **row})
    return path


def _profile(tmp_path, **change) -> Path:
    path = tmp_path / "truck.csv"
    got = {"year": "2000", "engine": "9.9", "axle": "Example axle", "lift_in": "0", **change}
    path.write_text(",".join(got) + "\n" + ",".join(got.values()) + "\n", encoding="utf-8")
    return path


def _read(tmp_path, *rows: dict):
    return specs.read_sheet(_sheet(tmp_path, *rows), m.FASTENERS_BY_ID, TRUCK)


def test_the_example_sheet_and_truck_load():
    truck, problems = specs.read_profile(EXAMPLE_TRUCK)
    assert problems == [] and truck.year == 2000
    known = {"example.widget@left_bolt", "example.widget@right_bolt"}
    rows, skipped, problems, kit = specs.read_sheet(EXAMPLE, known, truck)
    assert problems == []
    assert [x.fastener_id for x in rows] == ["example.widget@left_bolt"] and skipped == 1
    assert kit == {"example.widget@right_bolt"}


def test_a_ticked_row_becomes_a_row(tmp_path):
    rows, skipped, problems, _kit = _read(tmp_path, _row())
    assert problems == [] and skipped == 0
    assert rows[0].torque_min == Decimal(12) and rows[0].unit == "ft-lb"
    assert (rows[0].year_from, rows[0].year_to, rows[0].source_kind) == (1999, 2001, "fsm")


def test_an_unticked_row_with_a_figure_is_skipped_so_it_can_never_be_spoken(tmp_path):
    rows, skipped, problems, _kit = _read(tmp_path, _row(verified=""))
    assert rows == [] and skipped == 1 and problems == []


@pytest.mark.parametrize(
    "change,problem",
    [
        ({"verified_unit": ""}, "verified_unit"),
        ({"verified_unit": "ft"}, "verified_unit"),
        ({"verified_torque": "abc"}, "verified_torque"),
        ({"verified_torque": "12.25"}, "more than one decimal"),
        ({"source_kind": "forum"}, "source_kind"),
        ({"source_kind": ""}, "source_kind"),
        ({"verified_torque": "450"}, "typo"),
        ({"verified_unit": "in-lb", "verified_torque": "4900"}, "typo"),
        ({"source_page": ""}, "source_page is empty"),
        ({"engine": ""}, "engine is empty"),
        ({"year_from": "99"}, "year_from"),
        ({"year_from": "2001", "year_to": "1999"}, "after"),
        ({"verified_on": "Jan 2"}, "verified_on"),
        ({"verified_torque_max": "10"}, "below"),
        ({"fastener_id": "xj.susp.track_bar@moon_end"}, "not a fastener"),
        ({"on_truck": ""}, "on_truck"),
        ({"hardware": "kit"}, "kit_name"),
        ({"hardware": "kit", "kit_name": "Example kit"}, "kit maker's instructions"),
        ({"source_kind": "kit_instructions"}, "stock figure comes from"),
        ({"on_truck": "kit"}, "kit hardware on the truck"),
        ({"year_from": "2001", "year_to": "2001"}, "do not cover"),
        ({"engine": "8.8"}, "do not cover"),
        ({"axle": "Other axle"}, "do not cover"),
        (
            {"mockup_torque": "12 ft-lb (16 N-m)", "mockup_torque_source": "Example book"},
            "mock-up's own",
        ),
        ({"source": "copied from the mock-up", "mockup_torque": "12 ft-lb"}, "mock-up's own"),
    ],
)
def test_a_bad_ticked_row_is_named_and_nothing_loads(tmp_path, change, problem):
    rows, _skipped, problems, _kit = _read(tmp_path, _row(**change))
    assert len(problems) == 1 and problem in problems[0], problems


def test_a_kit_figure_from_the_kit_instructions_loads_on_kit_hardware(tmp_path):
    row = _row(
        on_truck="kit", hardware="kit", kit_name="Example kit", source_kind="kit_instructions"
    )
    rows, _skipped, problems, kit = _read(tmp_path, row)
    assert problems == [] and rows[0].kit_name == "Example kit"
    assert kit == {"xj.susp.track_bar@frame_end"}


def test_inch_pounds_are_their_own_unit_never_a_default(tmp_path):
    rows, _skipped, problems, _kit = _read(tmp_path, _row(verified_unit="in-lb"))
    assert problems == [] and rows[0].unit == "in-lb"


def test_the_same_fastener_twice_is_a_problem(tmp_path):
    _rows, _skipped, problems, _kit = _read(tmp_path, _row(), _row())
    assert "twice" in problems[0]


@pytest.mark.parametrize(
    "change,problem",
    [({"year": "98"}, "year"), ({"axle": ""}, "axle is empty"), ({"engine": "any"}, "any")],
)
def test_a_bad_profile_is_named(tmp_path, change, problem):
    truck, problems = specs.read_profile(_profile(tmp_path, **change))
    assert truck is None and any(problem in p for p in problems), problems


@pytest.mark.django_db
def test_the_loader_needs_the_count_confirmed_and_replaces_the_table(tmp_path):
    sheet, truck = _sheet(tmp_path, _row()), _profile(tmp_path)
    with pytest.raises(CommandError, match="does not match"):
        call_command("load_garage_specs", str(sheet), "--profile", str(truck), "--confirm", "2")
    assert not FastenerSpec.objects.exists()
    call_command("load_garage_specs", str(sheet), "--profile", str(truck), "--confirm", "1")
    assert FastenerSpec.objects.filter(model=m.MODEL).count() == 1
    assert GarageTruck.objects.get(model=m.MODEL).year == 2000
    bad = _sheet(tmp_path, _row(verified_unit=""))
    with pytest.raises(CommandError, match="Nothing was loaded"):
        call_command("load_garage_specs", str(bad), "--profile", str(truck), "--confirm", "0")
    assert FastenerSpec.objects.filter(model=m.MODEL).count() == 1
    # Un-ticked on the sheet: gone from the table, so no longer spoken.
    unticked = _sheet(tmp_path, _row(verified=""))
    call_command("load_garage_specs", str(unticked), "--profile", str(truck), "--confirm", "0")
    assert not FastenerSpec.objects.filter(model=m.MODEL).exists()


@pytest.mark.django_db
def test_a_dry_run_stores_nothing_and_a_write_without_a_count_is_refused(tmp_path):
    sheet, truck = _sheet(tmp_path, _row()), _profile(tmp_path)
    call_command("load_garage_specs", str(sheet), "--profile", str(truck), "--dry-run")
    assert not FastenerSpec.objects.exists() and not GarageTruck.objects.exists()
    with pytest.raises(CommandError):
        call_command("load_garage_specs", str(sheet), "--profile", str(truck))


# =============================================================================
# show_part's results (made-up figures, made-up truck)
# =============================================================================


def _truck(**change):
    got = {"year": 2000, "engine": "9.9", "axle": "Example axle", "lift_in": Decimal(0)}
    return GarageTruck.objects.create(model=m.MODEL, **{**got, **change})


def _store(fid="xj.susp.track_bar@frame_end", low="12", high=None, **extra):
    got = {
        "unit": "ft-lb",
        "socket": "",
        "thread": "",
        "year_from": 1999,
        "year_to": 2001,
        "engine": "9.9",
        "axle": "any",
        "hardware": "stock",
        "kit_name": "",
        "source_kind": "fsm",
        **extra,
    }
    return FastenerSpec.objects.create(
        model=m.MODEL,
        fastener_id=fid,
        torque_min=Decimal(low),
        torque_max=Decimal(high) if high else None,
        source="Example book",
        source_page="Page 1",
        verified_on=dt.date(2000, 1, 2),
        **got,
    )


def _run(**args):
    return SHOW_PART.handler(None, {"words": "", **args})


@pytest.mark.django_db
def test_an_unchecked_fastener_is_shown_and_said_with_no_number():
    _truck()
    result = _run(words="track bar frame end")
    data = result.structured
    assert result.card["card"] == CARD
    assert result.card["fastener_id"] == "xj.susp.track_bar@frame_end"
    assert data[SPEAK_VERBATIM][-1] == specs.NOT_VERIFIED and data[ENDS_TURN] is True
    assert data[SPOKEN_FIGURES] == []
    assert all(torque_figures(line) == [] for line in data[SPEAK_VERBATIM])
    assert not any(f.get("figure") for f in result.card["facts"])


@pytest.mark.django_db
def test_a_checked_fastener_is_said_with_the_truck_it_fits_and_its_source():
    _truck()
    _store(high="14", socket="10 mm")
    data = _run(words="track bar frame end").structured
    line = data[SPEAK_VERBATIM][1]
    assert line.startswith(
        "12 to 14 foot-pounds. That's for your 2000, 9.9, Example axle axle, the stock part. "
        "From Example book, Page 1"
    )
    assert "January 2" in line
    assert "socket 10 mm" in data[SPEAK_VERBATIM][2]
    assert {"12", "14", "10"} <= set(data[SPOKEN_FIGURES])


@pytest.mark.parametrize(
    "truck,spec,line",
    [
        ({}, {"year_from": 2001, "year_to": 2001}, specs.NOT_YOUR_TRUCK),
        ({"engine": "8.8"}, {}, specs.NOT_YOUR_TRUCK),
        ({"kit_fasteners": ["xj.susp.track_bar@frame_end"]}, {}, specs.STOCK_ON_KIT),
    ],
)
@pytest.mark.django_db
def test_a_row_that_does_not_fit_the_truck_is_never_spoken(truck, spec, line):
    _truck(**truck)
    _store(**spec)
    result = _run(words="track bar frame end")
    assert result.structured[SPEAK_VERBATIM][-1] == line
    assert result.structured[SPOKEN_FIGURES] == []
    assert not any(f.get("figure") for f in result.card["facts"])


@pytest.mark.django_db
def test_with_no_truck_profile_nothing_is_spoken():
    _store()
    result = _run(words="track bar frame end")
    assert result.structured[SPEAK_VERBATIM][-1] == specs.NO_TRUCK
    assert result.structured[SPOKEN_FIGURES] == []


@pytest.mark.django_db
def test_the_model_is_never_handed_a_figure():
    _truck()
    _store()
    result = _run(words="track bar frame end")
    for text in (result.text, result.structured[FOR_MODEL]):
        assert torque_figures(text) == []
        assert "12" not in text


@pytest.mark.django_db
def test_the_card_puts_the_asked_fastener_first_and_labels_the_conversion():
    _truck()
    _store()
    facts = _run(words="track bar frame end").card["facts"]
    assert facts[0]["fastener_id"] == "xj.susp.track_bar@frame_end"
    assert facts[0]["figure"] == "12 ft-lb (converted: about 16 N·m)"
    other = {f["fastener_id"]: f for f in facts[1:]}
    assert other["xj.susp.track_bar@axle_end"]["checked"] is False
    assert "figure" not in other["xj.susp.track_bar@axle_end"]


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
    _truck()
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
