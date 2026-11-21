"""The XJ front end, schematic stage 0: every part and fastener with a stable id (option B).

A Cherokee XJ's front suspension and steering, drawn as simple rods, boxes and balls (not to
scale). This module is the manifest: the ids, the names and the words people say for each part,
where each fastener sits, and the camera view to show it from. `garage.glb` builds the 3D file
from the same shapes (`manage.py build_garage_glb`), so the picture and the ids cannot drift.

**Ids never change and are never index based.** Part `xj.<assembly>.<part>[.<side>]`; fastener
`<part id>@<joint>`. Sides are `driver` / `passenger`, never left / right (the camera flips). A
new fastener gets a new id; a renamed one keeps its old id.

Nothing here is a figure: no torque, no socket or thread size. Those come only from the private,
checked spec table (`garage.models.FastenerSpec`).

Axes are glTF's: metres, +Y up, +Z toward the front bumper, +X the driver side (left-hand drive).
Origin: under the front axle's centre, at about ground level. Hardpoints are written in inches
(the schematic's own unit) and converted once.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

MODEL = "xj-front-s0"
MODEL_URL = f"/garage/{MODEL}.glb"
INCH = 0.0254

DRIVER = "driver"
PASSENGER = "passenger"
SIDES = ((DRIVER, 1), (PASSENGER, -1))


@dataclass(frozen=True)
class Shape:
    """One solid of a part: `rod` from `a` to `b` with radius `r`, `box` centred on `a` with size
    `b`, or `ball` centred on `a` with radius `r`. Inches."""

    kind: str
    a: tuple[float, float, float]
    b: tuple[float, float, float] = (0.0, 0.0, 0.0)
    r: float = 0.0
    segments: int = 12


@dataclass(frozen=True)
class View:
    """Where the camera looks (`target`) and stands (`at`), in metres."""

    target: tuple[float, float, float]
    at: tuple[float, float, float]

    def as_dict(self) -> dict:
        return {"target": list(self.target), "from": list(self.at)}


@dataclass(frozen=True)
class Part:
    id: str
    name: str
    assembly: str
    side: str | None
    aliases: tuple[str, ...]
    where: str
    shapes: tuple[Shape, ...]
    color: tuple[float, float, float, float]
    view: View = field(default=None)  # type: ignore[assignment]


@dataclass(frozen=True)
class Fastener:
    id: str
    part: str
    name: str
    side: str | None
    kind: str
    joint_words: tuple[str, ...]
    aliases: tuple[str, ...]
    pos: tuple[float, float, float]
    where: str
    view: View = field(default=None)  # type: ignore[assignment]


# ---- hardpoints (inches) ----------------------------------------------------------------------


def _lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


PITMAN_END = (10.5, 21.5, 8.0)
BOX_SHAFT = (10.5, 22.0, 15.0)


def _knuckle_arm(s):
    return (s * 25.0, 16.0, 6.5)


DRAG_KNUCKLE = _knuckle_arm(-1)
TIE_KNUCKLE = _knuckle_arm(1)
TIE_JOINT = _lerp(PITMAN_END, DRAG_KNUCKLE, 0.74)
STAB_LINK = _lerp(PITMAN_END, DRAG_KNUCKLE, 0.45)
STAB_AXLE = (4.0, 15.0, 4.0)
TRACK_FRAME = (13.5, 23.5, 3.5)
TRACK_AXLE = (-13.5, 18.8, 3.2)


def _lca_axle(s):
    return (s * 18.5, 11.5, -1.5)


def _lca_frame(s):
    return (s * 14.0, 14.0, -17.5)


def _uca_axle(s):
    return (s * 12.0, 19.8, -1.0)


def _uca_frame(s):
    return (s * 13.0, 21.5, -15.0)


def _sway_arm(s):
    return (s * 22.5, 24.0, 3.0)


def _sway_axle(s):
    return (s * 22.5, 17.5, 3.0)


def rod(a, b, r, segments=12):
    return Shape("rod", tuple(a), tuple(b), r, segments)


def box(centre, size):
    return Shape("box", tuple(centre), tuple(size))


def ball(centre, r):
    return Shape("ball", tuple(centre), r=r)


def _coil(s):
    """The coil as a chain of short rods along a helix: 7 turns, 12 steps a turn."""
    steps = 7 * 12
    points = [
        (
            s * 18.5 + math.cos(i / 12 * 2 * math.pi) * 2.4,
            17.4 + i / steps * 11.9,
            math.sin(i / 12 * 2 * math.pi) * 2.4,
        )
        for i in range(steps + 1)
    ]
    return tuple(rod(points[i], points[i + 1], 0.36, 6) for i in range(steps))


# ---- colours (baked into materials: no textures, CSP) ----------------------------------------

STEERING = (0.85, 0.45, 0.16, 1.0)
SUSPENSION = (0.22, 0.45, 0.78, 1.0)
AXLE = (0.55, 0.58, 0.61, 1.0)
FRAME = (0.36, 0.39, 0.43, 1.0)
SPRING = (0.40, 0.62, 0.36, 1.0)
WHEEL = (0.25, 0.27, 0.30, 0.15)

# ---- parts ------------------------------------------------------------------------------------

_CONTROL_ARM = ("control arm", "arm")
_BALL_JOINT = ("ball joint",)
_SLEEVE = ("sleeve", "adjusting sleeve", "adjuster")


def _sided(make):
    return [make(side, s) for side, s in SIDES]


def _parts() -> list[Part]:
    parts = [
        Part(
            "xj.steer.drag_link",
            "Drag link",
            "xj.steer",
            None,
            ("drag link", "draglink"),
            "The long rod in front of the axle, from the pitman arm under the steering box to the "
            "passenger knuckle.",
            (rod(PITMAN_END, DRAG_KNUCKLE, 0.6), ball(PITMAN_END, 0.95), ball(DRAG_KNUCKLE, 0.95)),
            STEERING,
        ),
        Part(
            "xj.steer.tie_rod",
            "Tie rod",
            "xj.steer",
            None,
            ("tie rod", "tierod", "tie rod end"),
            "The shorter rod from the driver knuckle to a joint on the drag link, near the "
            "passenger side.",
            (rod(TIE_KNUCKLE, TIE_JOINT, 0.55), ball(TIE_KNUCKLE, 0.95), ball(TIE_JOINT, 0.9)),
            STEERING,
        ),
        Part(
            "xj.steer.tie_rod_sleeve",
            "Tie rod sleeve",
            "xj.steer",
            None,
            ("tie rod sleeve", "tie rod adjuster", "tie rod adjusting sleeve", "toe sleeve")
            + _SLEEVE,
            "The threaded tube on the tie rod, near the driver wheel. It sets toe.",
            (rod(_lerp(TIE_KNUCKLE, TIE_JOINT, 0.1), _lerp(TIE_KNUCKLE, TIE_JOINT, 0.22), 0.85),),
            STEERING,
        ),
        Part(
            "xj.steer.drag_link_sleeve",
            "Drag link sleeve",
            "xj.steer",
            None,
            ("drag link sleeve", "drag link adjuster", "drag link adjusting sleeve") + _SLEEVE,
            "The threaded tube on the drag link, near the pitman arm. It centres the steering "
            "wheel.",
            (
                rod(
                    _lerp(PITMAN_END, DRAG_KNUCKLE, 0.1), _lerp(PITMAN_END, DRAG_KNUCKLE, 0.22), 0.9
                ),
            ),
            STEERING,
        ),
        Part(
            "xj.steer.pitman_arm",
            "Pitman arm",
            "xj.steer",
            DRIVER,
            ("pitman arm", "pitman", "pittman arm", "pittman"),
            "The lever hanging under the steering box on the driver side. It swings the drag link.",
            (rod(BOX_SHAFT, PITMAN_END, 0.8), ball(BOX_SHAFT, 1.1)),
            STEERING,
        ),
        Part(
            "xj.steer.box",
            "Steering box",
            "xj.steer",
            DRIVER,
            ("steering box", "steering gear", "gear box", "gearbox", "steering gearbox"),
            "Bolted to the inside of the driver frame rail, ahead of the axle.",
            (
                box((10.5, 27, 15.5), (3.6, 5, 6)),
                rod((10.5, 24.5, 15), (10.5, 22.2, 15), 0.8),
                rod((10.5, 29, 18.5), (8.5, 31.5, 22), 0.5),
            ),
            STEERING,
        ),
        Part(
            "xj.steer.stabilizer",
            "Steering stabilizer",
            "xj.steer",
            None,
            (
                "steering stabilizer",
                "steering stabiliser",
                "stabilizer",
                "stabiliser",
                "steering damper",
            ),
            "The small shock absorber from a bracket on the axle to the drag link.",
            (
                rod(STAB_AXLE, _lerp(STAB_AXLE, STAB_LINK, 0.55), 0.9),
                rod(_lerp(STAB_AXLE, STAB_LINK, 0.55), STAB_LINK, 0.35),
            ),
            STEERING,
        ),
        Part(
            "xj.susp.track_bar",
            "Track bar",
            "xj.susp",
            None,
            ("track bar", "trackbar", "trac bar", "panhard bar", "panhard rod", "panhard"),
            "From a bracket on the driver frame rail across to a bracket on top of the axle, "
            "passenger side.",
            (rod(TRACK_FRAME, TRACK_AXLE, 0.7), ball(TRACK_FRAME, 1.0), ball(TRACK_AXLE, 1.0)),
            SUSPENSION,
        ),
        Part(
            "xj.susp.sway_bar",
            "Sway bar",
            "xj.susp",
            None,
            (
                "sway bar",
                "swaybar",
                "anti roll bar",
                "anti sway bar",
                "stabilizer bar",
                "stabiliser bar",
            ),
            "The bar clamped under both frame rails ahead of the axle; its ends go to the sway "
            "bar links.",
            (rod((-19, 24.3, 9.5), (19, 24.3, 9.5), 0.5),)
            + tuple(rod((s * 19, 24.3, 9.5), _sway_arm(s), 0.5) for _, s in SIDES)
            + tuple(box((s * 13.5, 24.3, 9.5), (2.4, 1.3, 1.6)) for _, s in SIDES),
            SUSPENSION,
        ),
        Part(
            "xj.axle.housing",
            "Front axle",
            "xj.axle",
            None,
            ("front axle", "axle housing", "axle tube", "axle", "differential", "diff"),
            "The axle tube across the front, with the differential towards the driver side.",
            (
                rod((-25, 15, 0), (25, 15, 0), 1.6, 18),
                ball((11, 15, 0), 4.8),
                rod((11, 15, 3.4), (11, 15, 4.6), 4.1, 18),
                rod((11, 16, -3), (11, 17, -9), 1.5),
                box((-12, 18.3, -1), (1.6, 3, 2.4)),
                box((-13.5, 17.6, 3), (2.2, 2.6, 2.2)),
                box((4, 15.6, 3.2), (1.6, 1.6, 2.4)),
            )
            + tuple(
                shape
                for _, s in SIDES
                for shape in (
                    rod((s * 25, 10.5, 0), (s * 25, 19.5, 0), 1.1),
                    rod((s * 25, 19.5, 0), (s * 26.5, 19.5, 0), 0.9),
                    rod((s * 25, 10.5, 0), (s * 26.5, 10.5, 0), 0.9),
                    box((s * 18.5, 13, -1.5), (2.6, 3.4, 3)),
                    rod((s * 18.5, 16.5, 0), (s * 18.5, 17.2, 0), 3, 18),
                    box((s * 22.5, 17, 2.2), (1.4, 2.2, 2.6)),
                )
            ),
            AXLE,
        ),
        Part(
            "xj.body.frame",
            "Frame rails",
            "xj.body",
            None,
            ("frame rail", "frame", "unibody", "crossmember"),
            "The two rails the suspension hangs from, with the front crossmember.",
            (box((0, 27, 24), (30, 3, 2.5)), box((13.5, 24.4, 3.5), (2.4, 2.2, 3)))
            + tuple(
                shape
                for _, s in SIDES
                for shape in (
                    box((s * 13.5, 27, -2), (3, 4, 56)),
                    box((s * 18.5, 31, 0), (6.5, 0.6, 6.5)),
                    box((s * 15.6, 29.6, 0), (1.4, 2.4, 6)),
                    box((s * 14, 18.5, -17.5), (1, 13, 3)),
                    box((s * 14.4, 23, -15), (1, 4, 3)),
                )
            ),
            FRAME,
        ),
    ]
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.lca.{side}",
            "Lower control arm",
            "xj.susp",
            side,
            ("lower control arm", "lower arm", "lower link", "lca") + _CONTROL_ARM,
            f"Under the axle on the {side} side, running back to a bracket under the frame "
            "behind the axle.",
            (rod(_lca_axle(s), _lca_frame(s), 0.85),),
            SUSPENSION,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.uca.{side}",
            "Upper control arm",
            "xj.susp",
            side,
            ("upper control arm", "upper arm", "upper link", "uca") + _CONTROL_ARM,
            f"From an ear on top of the axle on the {side} side back to the frame.",
            (rod(_uca_axle(s), _uca_frame(s), 0.65),),
            SUSPENSION,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.sway_link.{side}",
            "Sway bar link",
            "xj.susp",
            side,
            (
                "sway bar link",
                "sway link",
                "end link",
                "sway bar end link",
                "stabilizer link",
                "sway bar disconnect",
                "quick disconnect",
                "disconnect",
            ),
            f"The short upright from the sway bar end down to the axle, {side} side.",
            (rod(_sway_arm(s), _sway_axle(s), 0.45),),
            SUSPENSION,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.shock.{side}",
            "Front shock",
            "xj.susp",
            side,
            ("shock absorber", "front shock", "shock"),
            f"Up through the middle of the {side} coil spring.",
            (
                rod((s * 18.5, 16.8, 0.6), (s * 18.5, 25, 0.6), 1.05),
                rod((s * 18.5, 25, 0.6), (s * 18.5, 32.6, 0.6), 0.45),
            ),
            SUSPENSION,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.bump_stop.{side}",
            "Bump stop",
            "xj.susp",
            side,
            ("bump stop", "bumpstop", "jounce bumper"),
            f"Inside the top of the {side} coil spring, in a cup.",
            (rod((s * 18.5, 26.6, -1.3), (s * 18.5, 29.4, -1.3), 0.85),),
            SUSPENSION,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.susp.coil.{side}",
            "Coil spring and spacer",
            "xj.susp",
            side,
            (
                "coil spring",
                "coil spacer",
                "lift spacer",
                "spring spacer",
                "coil",
                "spring",
                "spacer",
            ),
            f"On the axle's {side} spring seat, up to the spring tower; the spacer sits on top.",
            _coil(s) + (rod((s * 18.5, 29.4, 0), (s * 18.5, 30.7, 0), 2.9, 18),),
            SPRING,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.axle.ball_joint_upper.{side}",
            "Upper ball joint",
            "xj.axle",
            side,
            ("upper ball joint", "top ball joint") + _BALL_JOINT,
            f"Pressed into the top of the axle end, {side} side; the knuckle hangs on its stud.",
            (ball((s * 26.5, 19.7, 0), 1.15),),
            AXLE,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.axle.ball_joint_lower.{side}",
            "Lower ball joint",
            "xj.axle",
            side,
            ("lower ball joint", "bottom ball joint") + _BALL_JOINT,
            f"Pressed into the bottom of the axle end, {side} side.",
            (ball((s * 26.5, 10.3, 0), 1.15),),
            AXLE,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.axle.knuckle.{side}",
            "Steering knuckle",
            "xj.axle",
            side,
            ("steering knuckle", "knuckle", "spindle"),
            f"The iron the {side} wheel hangs on; its forward arm takes the steering rod.",
            (
                rod((s * 27.4, 10.3, 0), (s * 27.4, 19.7, 0), 1.3),
                rod((s * 27, 15.6, 0.5), _knuckle_arm(s), 0.8),
            ),
            AXLE,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.axle.hub.{side}",
            "Wheel hub bearing",
            "xj.axle",
            side,
            ("wheel hub", "hub bearing", "unit bearing", "wheel bearing", "hub assembly", "hub"),
            f"Bolted to the outside of the {side} knuckle; the wheel bolts to it.",
            (
                rod((s * 27.8, 15, 0), (s * 29.8, 15, 0), 2.6, 18),
                rod((s * 29.8, 15, 0), (s * 30.4, 15, 0), 5.6, 24),
            ),
            AXLE,
        )
    )
    parts += _sided(
        lambda side, s: Part(
            f"xj.wheel.{side}",
            "Wheel and tyre",
            "xj.wheel",
            side,
            ("wheel", "tire", "tyre"),
            f"The {side} front wheel, drawn see-through.",
            (rod((s * 27, 15, 0), (s * 36, 15, 0), 14.5, 24),),
            WHEEL,
        )
    )
    return parts


# ---- fasteners --------------------------------------------------------------------------------

TAPER = "Castle nut and cotter pin on a tapered stud"
ARM = "Bolt and nut through a rubber bushing"


def _f(part, joint, name, pos, kind, where, words=(), aliases=(), side=None):
    return Fastener(
        f"{part}@{joint}", part, name, side, kind, tuple(words), tuple(aliases), tuple(pos), where
    )


def _fasteners() -> list[Fastener]:
    out = [
        _f(
            "xj.steer.drag_link",
            "pitman_end",
            "pitman arm end",
            PITMAN_END,
            TAPER,
            "Where the drag link meets the pitman arm, under the steering box.",
            ("pitman arm end", "pitman end", "pitman arm", "pitman", "box end", "steering box end"),
            side=DRIVER,
        ),
        _f(
            "xj.steer.drag_link",
            "knuckle_end",
            "knuckle end",
            DRAG_KNUCKLE,
            TAPER,
            "At the passenger knuckle's steering arm.",
            ("knuckle end", "knuckle", "passenger end", "wheel end", "outer end"),
            side=PASSENGER,
        ),
        _f(
            "xj.steer.tie_rod",
            "knuckle_end",
            "knuckle end",
            TIE_KNUCKLE,
            TAPER,
            "At the driver knuckle's steering arm.",
            ("knuckle end", "knuckle", "driver end", "wheel end", "outer end"),
            side=DRIVER,
        ),
        _f(
            "xj.steer.tie_rod",
            "drag_link_end",
            "drag link end",
            TIE_JOINT,
            TAPER,
            "Where the tie rod joins the drag link, near the passenger side.",
            ("drag link end", "drag link", "inner end", "middle", "joint"),
        ),
        _f(
            "xj.steer.tie_rod_sleeve",
            "clamps",
            "sleeve clamps",
            _lerp(TIE_KNUCKLE, TIE_JOINT, 0.16),
            "Two clamps, one pinch bolt each",
            "Two clamps on the tie rod sleeve, near the driver wheel.",
            ("clamp", "clamp bolt", "pinch bolt"),
        ),
        _f(
            "xj.steer.drag_link_sleeve",
            "clamps",
            "sleeve clamps",
            _lerp(PITMAN_END, DRAG_KNUCKLE, 0.16),
            "Two clamps, one pinch bolt each",
            "Two clamps on the drag link sleeve, near the pitman arm.",
            ("clamp", "clamp bolt", "pinch bolt"),
        ),
        _f(
            "xj.steer.pitman_arm",
            "shaft_nut",
            "shaft nut",
            (10.5, 21.3, 15),
            "One large nut and lock washer on a splined shaft",
            "The big nut holding the pitman arm on the steering box shaft.",
            ("shaft nut", "shaft", "big nut", "sector shaft nut", "nut"),
            ("pitman nut",),
            side=DRIVER,
        ),
    ]
    out += [
        _f(
            "xj.steer.box",
            f"frame_bolt_{i}",
            f"frame bolt {i} of 3",
            (15.4, 27, z),
            "Bolt through the frame rail",
            "One of three bolts through the driver frame rail that hold the steering box.",
            (f"frame bolt {i}", "frame bolt", "mounting bolt", "box bolt", "bolt"),
            ("steering box bolt", "steering box bolts", "gearbox bolt"),
            side=DRIVER,
        )
        for i, z in ((1, 13.0), (2, 15.5), (3, 18.0))
    ]
    out += [
        _f(
            "xj.susp.track_bar",
            "frame_end",
            "frame end",
            TRACK_FRAME,
            "Nut on a tapered ball stud",
            "The top end of the track bar, at the bracket on the driver frame rail.",
            (
                "frame end",
                "frame side",
                "frame",
                "top end",
                "upper end",
                "driver end",
                "ball joint end",
                "ball stud",
            ),
            side=DRIVER,
        ),
        _f(
            "xj.susp.track_bar",
            "axle_end",
            "axle end",
            TRACK_AXLE,
            "Bolt into a flag nut",
            "The bottom end of the track bar, at the bracket on top of the axle, passenger side.",
            (
                "axle end",
                "axle side",
                "axle",
                "bottom end",
                "lower end",
                "passenger end",
                "flag nut",
                "bushing end",
            ),
            side=PASSENGER,
        ),
        _f(
            "xj.susp.track_bar",
            "frame_bracket_bolts",
            "frame bracket bolts",
            (14.8, 25, 2.2),
            "Bolts into the frame rail",
            "The bolts holding the track bar bracket to the driver frame rail.",
            ("frame bracket bolt", "bracket bolt", "bracket to frame"),
            ("track bar bracket bolt",),
            side=DRIVER,
        ),
        _f(
            "xj.susp.track_bar",
            "frame_bracket_nut",
            "frame bracket nut",
            (14.8, 23.8, 4.6),
            "Nut on the bracket",
            "The nut on the track bar's frame bracket.",
            ("frame bracket nut", "bracket nut"),
            ("track bar bracket nut",),
            side=DRIVER,
        ),
        _f(
            "xj.susp.track_bar",
            "frame_bracket_support_bolts",
            "bracket support bolts",
            (14.8, 26, 0.4),
            "Bolts through the bracket's support",
            "The smaller bolts through the support brace of the track bar's frame bracket.",
            ("support bolt", "bracket support bolt", "brace bolt", "support brace"),
            ("track bar brace bolt",),
            side=DRIVER,
        ),
    ]
    for side, s in SIDES:
        out += [
            _f(
                f"xj.susp.lca.{side}",
                "axle_end",
                "axle end",
                _lca_axle(s),
                ARM,
                f"The front end of the {side} lower arm, at the bracket under the axle.",
                ("axle end", "axle side", "axle", "front end", "front bolt"),
            ),
            _f(
                f"xj.susp.lca.{side}",
                "frame_end",
                "frame end",
                _lca_frame(s),
                ARM,
                f"The rear end of the {side} lower arm, at the bracket under the frame.",
                ("frame end", "frame side", "frame", "rear end", "back end", "rear bolt"),
            ),
            _f(
                f"xj.susp.uca.{side}",
                "axle_end",
                "axle end",
                _uca_axle(s),
                ARM,
                f"The front end of the {side} upper arm, at the ear on top of the axle.",
                ("axle end", "axle side", "axle", "front end", "front bolt"),
            ),
            _f(
                f"xj.susp.uca.{side}",
                "frame_end",
                "frame end",
                _uca_frame(s),
                ARM,
                f"The rear end of the {side} upper arm, at the frame.",
                ("frame end", "frame side", "frame", "rear end", "back end", "rear bolt"),
            ),
            _f(
                f"xj.susp.sway_link.{side}",
                "top",
                "top",
                _sway_arm(s),
                "Stud and nut through the bar end",
                f"The top of the {side} sway bar link, through the end of the sway bar.",
                ("top", "upper", "sway bar end", "bar end"),
            ),
            _f(
                f"xj.susp.sway_link.{side}",
                "bottom",
                "bottom",
                _sway_axle(s),
                "Bolt and nut",
                f"The bottom of the {side} sway bar link, at the axle bracket.",
                ("bottom", "lower", "axle end", "axle"),
            ),
            _f(
                "xj.susp.sway_bar",
                f"frame_clamp_{side}",
                f"frame clamp, {side} side",
                (s * 13.5, 24.3, 9.5),
                "Two bolts per clamp",
                f"The clamp holding the sway bar under the {side} frame rail.",
                ("frame clamp", "clamp", "bushing clamp", "bracket", "frame"),
                side=side,
            ),
            _f(
                f"xj.susp.shock.{side}",
                "top_nut",
                "top nut",
                (s * 18.5, 32.8, 0.6),
                "Nut on a threaded stem, with rubber bushings",
                f"On top of the {side} spring tower, reached from the engine bay.",
                ("top nut", "top", "upper", "stem nut", "stem"),
            ),
            _f(
                f"xj.susp.shock.{side}",
                "bottom_bolts",
                "bottom bolts",
                (s * 18.5, 16.1, 0.6),
                "Two small bolts from underneath",
                f"Two bolts up into the {side} spring seat on the axle.",
                ("bottom bolt", "bottom", "lower", "lower bolt"),
            ),
            _f(
                f"xj.axle.ball_joint_upper.{side}",
                "stud_nut",
                "stud nut",
                (s * 26.5, 20.7, 0),
                TAPER,
                f"The nut on the upper ball joint's stud, on top of the {side} knuckle.",
                ("stud nut", "stud", "nut"),
            ),
            _f(
                f"xj.axle.ball_joint_lower.{side}",
                "stud_nut",
                "stud nut",
                (s * 26.5, 9.3, 0),
                TAPER,
                f"The nut on the lower ball joint's stud, under the {side} knuckle.",
                ("stud nut", "stud", "nut"),
            ),
            _f(
                f"xj.axle.hub.{side}",
                "hub_bolts",
                "hub bolts",
                (s * 27.2, 15, -1.8),
                "Three bolts through the back of the knuckle",
                f"Three bolts from behind the {side} knuckle into the hub.",
                ("hub bolt", "mounting bolt", "bolt"),
                ("hub bolts", "hub bearing bolts"),
            ),
            _f(
                f"xj.axle.hub.{side}",
                "axle_nut",
                "axle nut",
                (s * 31, 15, 0),
                "One large nut in the wheel centre, with a cotter pin",
                f"The big nut in the centre of the {side} hub, behind a cap.",
                ("axle nut", "hub nut", "spindle nut", "center nut", "centre nut"),
                ("axle nut", "hub nut", "spindle nut"),
            ),
        ]
    out += [
        _f(
            "xj.steer.stabilizer",
            "axle_end",
            "axle end",
            STAB_AXLE,
            "Bolt and nut",
            "The stabilizer's end at the bracket on the axle.",
            ("axle end", "axle", "bracket"),
        ),
        _f(
            "xj.steer.stabilizer",
            "drag_link_end",
            "drag link end",
            STAB_LINK,
            "Stud and nut",
            "The stabilizer's end at the drag link.",
            ("drag link end", "drag link", "rod end"),
        ),
    ]
    return out


# ---- views ------------------------------------------------------------------------------------


def _m(p):
    return tuple(round(v * INCH, 4) for v in p)


def _view(target_in, side_sign, distance_m):
    d = (side_sign * 0.9, 0.55, 1.0)
    n = math.sqrt(sum(v * v for v in d))
    target = _m(target_in)
    at = tuple(round(target[i] + d[i] / n * distance_m, 4) for i in range(3))
    return View(target, at)


def _sign(side, x):
    if side == DRIVER:
        return 1
    if side == PASSENGER:
        return -1
    return 1 if x >= 0 else -1


def _centre(shapes):
    points = [s.a if s.kind != "rod" else _lerp(s.a, s.b, 0.5) for s in shapes]
    return tuple(sum(p[i] for p in points) / len(points) for i in range(3))


def _build():
    fasteners = _fasteners()
    by_part: dict[str, list[Fastener]] = {}
    out_f = []
    for f in fasteners:
        f = Fastener(**{**f.__dict__, "view": _view(f.pos, _sign(f.side, f.pos[0]), 0.9)})
        out_f.append(f)
        by_part.setdefault(f.part, []).append(f)
    out_p = []
    for p in _parts():
        mine = by_part.get(p.id)
        centre = _centre(p.shapes)
        if mine:
            centre = tuple(sum(f.pos[i] for f in mine) / len(mine) for i in range(3))
        out_p.append(Part(**{**p.__dict__, "view": _view(centre, _sign(p.side, centre[0]), 1.5)}))
    return tuple(out_p), tuple(out_f)


PARTS, FASTENERS = _build()
PARTS_BY_ID = {p.id: p for p in PARTS}
FASTENERS_BY_ID = {f.id: f for f in FASTENERS}


def fasteners_of(part_id: str) -> list[Fastener]:
    return [f for f in FASTENERS if f.part == part_id]


def position_m(f: Fastener) -> tuple[float, float, float]:
    return _m(f.pos)
