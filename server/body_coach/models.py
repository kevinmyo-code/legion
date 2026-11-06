"""The fat-loss loop's four tables (fat-loss map, tickets 03, 05, 06 and 07).

Kevin, 2026-11-05: *"get lean, keep muscle"*. The eight body tables (`bodyweight_logs`,
`meal_logs`, `meal_targets`, ...) are Supabase-era and `managed = False`; these four are new and
Django owns them end to end, born tenanted in `public` like `member_memories`.

- **`body_profiles`** (ticket 03): what the calculator needs about one member. **Private to that
  member** (ADR 0052): `owner_user` is never null, so `household.tenancy.visible()`
  (`OWNER_PATHS`) gives a member their own profile and nobody else's, and the owner guard trigger
  holds that the owner is a member of the row's household. One per member per household.
- **`waist_logs`** (ticket 05): a weekly waist measurement, the second falsifiable anchor beside
  the scale. Shared in the household (map ruling 6). Hard delete.
- **`saved_meals`** (ticket 07): "my usual breakfast". Shared (ruling 6). The macros are
  ESTIMATES (CLAUDE.md section 4 rule 5): nothing on a plate states them.
- **`energy_proposals`** (ticket 06): a two-weekly calorie change the engine proposes and a person
  accepts or dismisses; never applied by itself. **Every input it was computed from is stored
  beside it** (section 4 rule 8), so the proposal can be re-derived from its own row.

Lengths are integer millimetres; rates and protein are integers in small units (basis points of
bodyweight per week, tenths of a gram per kilogram) so a stored figure never carries float noise.
Nothing about a real person is in this file or its tests.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Lower, Now

SEX_MALE = "male"
SEX_FEMALE = "female"
SEXES: tuple[str, ...] = (SEX_MALE, SEX_FEMALE)

# Mifflin-St Jeor's activity multipliers (ticket 04).
ACTIVITY_FACTORS: dict[str, float] = {
    "sedentary": 1.2,
    "light": 1.375,
    "moderate": 1.55,
    "very": 1.725,
}
ACTIVITY_LEVELS: tuple[str, ...] = tuple(ACTIVITY_FACTORS)

# Kevin, 2026-11-06: for "get lean, keep muscle" while lifting, 0.5% of bodyweight a week.
TARGET_RATE_BP_DEFAULT = 50
TARGET_RATE_BP_MIN, TARGET_RATE_BP_MAX = 25, 100
PROTEIN_TENTHS_DEFAULT = 20
PROTEIN_TENTHS_MIN, PROTEIN_TENTHS_MAX = 16, 22
# Plausibility bounds, not targets: a typo (178 typed into a millimetre field) is refused.
HEIGHT_MM_MIN, HEIGHT_MM_MAX = 1000, 2500
BIRTH_YEAR_MIN, BIRTH_YEAR_MAX = 1900, 2100
WAIST_MM_MAX = 3000
GOAL_WEIGHT_LBS_MAX = 1000

STATUS_PENDING = "pending"
STATUS_ACCEPTED = "accepted"
STATUS_DISMISSED = "dismissed"
STATUS_EXPIRED = "expired"
PROPOSAL_STATUSES: tuple[str, ...] = (
    STATUS_PENDING,
    STATUS_ACCEPTED,
    STATUS_DISMISSED,
    STATUS_EXPIRED,
)

ESTIMATE_HELP = (
    "An estimate, not a measurement: nothing on a plate states its calories or macros "
    "(CLAUDE.md section 4 rule 5)."
)


def _null_or_between(field: str, low: int, high: int) -> models.Q:
    return models.Q(**{f"{field}__isnull": True}) | (
        models.Q(**{f"{field}__gte": low}) & models.Q(**{f"{field}__lte": high})
    )


class BodyProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # Never null: a profile is always one member's (ADR 0052). CASCADE: it goes with the person.
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    # Null until given: PUT is partial, and `missing` names what the calculator still needs.
    height_mm = models.IntegerField(null=True, blank=True)
    sex = models.TextField(null=True, blank=True)
    birth_year = models.IntegerField(null=True, blank=True)
    activity_level = models.TextField(null=True, blank=True)
    target_rate_bp = models.SmallIntegerField(default=TARGET_RATE_BP_DEFAULT)
    protein_tenths_g_per_kg = models.SmallIntegerField(default=PROTEIN_TENTHS_DEFAULT)
    # Map ruling 7: the Monday line is on until one instruction turns it off.
    weekly_line_on = models.BooleanField(default=True)
    # The member's own goal or milestone weight, in lbs. Used when no active `goals` row with
    # `metric_key = bodyweight_lbs` is on the engine (the phone's goals do not sync to it).
    goal_weight_lbs = models.FloatField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "body_profiles"
        constraints = [
            models.UniqueConstraint(
                fields=["household", "owner_user"], name="body_profiles_one_per_member"
            ),
            models.CheckConstraint(
                condition=models.Q(sex__isnull=True) | models.Q(sex__in=SEXES),
                name="body_profiles_sex_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(activity_level__isnull=True)
                | models.Q(activity_level__in=ACTIVITY_LEVELS),
                name="body_profiles_activity_level_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(target_rate_bp__gte=TARGET_RATE_BP_MIN)
                & models.Q(target_rate_bp__lte=TARGET_RATE_BP_MAX),
                name="body_profiles_target_rate_range",
            ),
            models.CheckConstraint(
                condition=models.Q(protein_tenths_g_per_kg__gte=PROTEIN_TENTHS_MIN)
                & models.Q(protein_tenths_g_per_kg__lte=PROTEIN_TENTHS_MAX),
                name="body_profiles_protein_range",
            ),
            models.CheckConstraint(
                condition=_null_or_between("height_mm", HEIGHT_MM_MIN, HEIGHT_MM_MAX),
                name="body_profiles_height_range",
            ),
            models.CheckConstraint(
                condition=_null_or_between("birth_year", BIRTH_YEAR_MIN, BIRTH_YEAR_MAX),
                name="body_profiles_birth_year_range",
            ),
            models.CheckConstraint(
                condition=models.Q(goal_weight_lbs__isnull=True)
                | (
                    models.Q(goal_weight_lbs__gt=0)
                    & models.Q(goal_weight_lbs__lt=GOAL_WEIGHT_LBS_MAX)
                ),
                name="body_profiles_goal_weight_range",
            ),
        ]

    def __str__(self) -> str:
        # Never a figure: admin lists and log lines name the row, not the person's numbers.
        return f"body profile {self.id}"


class WaistLog(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    measured_on = models.DateField()
    waist_mm = models.IntegerField()
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "waist_logs"
        indexes = [
            models.Index(fields=["household", "measured_on"], name="waist_logs_day_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(waist_mm__gt=0) & models.Q(waist_mm__lte=WAIST_MM_MAX),
                name="waist_logs_waist_mm_range",
            ),
        ]

    def __str__(self) -> str:
        return f"waist log {self.id}"


class SavedMeal(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    name = models.TextField()
    description = models.TextField(blank=True, default="")
    calories_kcal = models.IntegerField(help_text=ESTIMATE_HELP)
    protein_g = models.FloatField(help_text=ESTIMATE_HELP)
    carbs_g = models.FloatField(null=True, blank=True, help_text=ESTIMATE_HELP)
    fat_g = models.FloatField(null=True, blank=True, help_text=ESTIMATE_HELP)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "saved_meals"
        constraints = [
            models.UniqueConstraint(
                "household", Lower("name"), name="saved_meals_household_name_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(calories_kcal__gte=0), name="saved_meals_calories_non_negative"
            ),
            models.CheckConstraint(
                condition=models.Q(protein_g__gte=0), name="saved_meals_protein_non_negative"
            ),
            models.CheckConstraint(
                condition=models.Q(carbs_g__isnull=True) | models.Q(carbs_g__gte=0),
                name="saved_meals_carbs_non_negative",
            ),
            models.CheckConstraint(
                condition=models.Q(fat_g__isnull=True) | models.Q(fat_g__gte=0),
                name="saved_meals_fat_non_negative",
            ),
        ]

    def __str__(self) -> str:
        return f"saved meal {self.id}"


class EnergyProposal(models.Model):
    """One proposed change to the daily calorie target, with every input it came from."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The 14 full household-local days the evidence covers, inclusive.
    window_start = models.DateField()
    window_end = models.DateField()
    intake_days = models.SmallIntegerField()
    weigh_in_days = models.SmallIntegerField()
    # The anchors, unrounded, so the figures below can be re-derived from this row alone.
    mean_intake_kcal = models.FloatField()
    trend_start_lbs = models.FloatField()
    trend_end_lbs = models.FloatField()
    observed_maintenance_kcal = models.IntegerField()
    # Percent of bodyweight per week x 100; negative is losing.
    observed_rate_bp = models.IntegerField()
    target_rate_bp = models.SmallIntegerField()
    current_target_kcal = models.IntegerField()
    proposed_target_kcal = models.IntegerField()
    status = models.TextField(default=STATUS_PENDING)
    created_at = models.DateTimeField(db_default=Now())
    decided_at = models.DateTimeField(null=True, blank=True)
    decided_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    # The `meal_targets` row an acceptance wrote.
    meal_target_origin_guid = models.TextField(null=True, blank=True)

    class Meta:
        db_table = "energy_proposals"
        indexes = [
            models.Index(fields=["household", "created_at"], name="energy_proposals_made_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=PROPOSAL_STATUSES),
                name="energy_proposals_status_valid",
            ),
            # One pending proposal per household, held by the database, not only by the view.
            models.UniqueConstraint(
                fields=["household"],
                condition=models.Q(status=STATUS_PENDING),
                name="energy_proposals_one_pending",
            ),
            models.CheckConstraint(
                condition=models.Q(window_end__gte=models.F("window_start")),
                name="energy_proposals_window_ordered",
            ),
            models.CheckConstraint(
                condition=models.Q(proposed_target_kcal__gt=800),
                name="energy_proposals_above_800_floor",
            ),
        ]

    def __str__(self) -> str:
        return f"energy proposal {self.id}"


# =============================================================================
# The workout programme (workouts map, 2026-11-06). Rules in `body_coach/programme.py`.
# =============================================================================
#
# Three tables, shared in the household like every body table (map ruling 6: only Kevin logs;
# a second member training needs an owner column first). **Every target is stored beside what
# was done** (CLAUDE.md section 4 rule 8 in spirit): a session's set rows hold the rule's output
# (target weight, range, the rule's name) and the actuals, so a progression can be re-derived and
# audited from the rows alone. The model never writes a target; the views call the rules.

SESSION_IN_PROGRESS = "in_progress"
SESSION_FINISHED = "finished"
SESSION_CANCELLED = "cancelled"
SESSION_STATUSES: tuple[str, ...] = (SESSION_IN_PROGRESS, SESSION_FINISHED, SESSION_CANCELLED)
WORKOUT_WEIGHT_LBS_MAX = 2000
WORKOUT_REPS_MAX = 1000


class TrainingProgramme(models.Model):
    """The household's one active programme: which template, which weekdays, which swaps."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    template = models.TextField(default="full_body_ab")
    # Comma-separated weekday keys (`mon,wed,fri`); its length is the sessions a week.
    training_days = models.TextField(default="mon,wed,fri")
    # Slot key -> exercise key, for slots whose exercise was swapped for an alternative.
    swaps = models.JSONField(default=dict, blank=True)
    # Body coach 2 (2026-11-06): the filters a slot's exercise is picked by
    # (`programme.resolve_day`): equipment he does not have, joints to go easy on, the cardio
    # finisher. Set only by accepting a programme proposal.
    exclude_equipment = models.JSONField(default=list, blank=True)
    avoid = models.JSONField(default=list, blank=True)
    cardio = models.BooleanField(default=False)
    # When the template last changed: the day after it starts from the template's first day.
    template_since = models.DateTimeField(null=True, blank=True)
    active = models.BooleanField(default=True)
    started_on = models.DateField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "training_programmes"
        constraints = [
            models.UniqueConstraint(
                fields=["household"],
                condition=models.Q(active=True),
                name="training_programmes_one_active",
            ),
            models.CheckConstraint(
                condition=~models.Q(training_days=""), name="training_programmes_days_given"
            ),
        ]

    def day_list(self) -> list[str]:
        return [d for d in self.training_days.split(",") if d]

    def __str__(self) -> str:
        return f"training programme {self.id}"


class WorkoutSession(models.Model):
    """One workout. The id may be minted by the phone, so a start queued offline is idempotent."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    programme = models.ForeignKey(TrainingProgramme, on_delete=models.PROTECT, related_name="sessions")
    day_key = models.TextField()
    # 1-based count of this programme's sessions, cancelled ones excluded; decides the deload.
    session_number = models.IntegerField()
    deload = models.BooleanField(default=False)
    # Body coach 2 (Kevin, 2026-11-06): a commercial gym, or home with a kettlebell and bodyweight.
    location = models.TextField(default="gym")
    session_date = models.DateField()
    status = models.TextField(default=SESSION_IN_PROGRESS)
    started_at = models.DateTimeField()
    finished_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "workout_sessions"
        indexes = [
            models.Index(fields=["household", "session_date"], name="workout_sessions_day_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=SESSION_STATUSES), name="workout_sessions_status_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(day_key__in=("A", "B", "C", "D")),
                name="workout_sessions_day_key_any_template",
            ),
            models.UniqueConstraint(
                fields=["household"],
                condition=models.Q(status=SESSION_IN_PROGRESS),
                name="workout_sessions_one_in_progress",
            ),
            models.CheckConstraint(
                condition=models.Q(session_number__gte=1), name="workout_sessions_number_positive"
            ),
            models.CheckConstraint(
                condition=models.Q(location__in=("gym", "home")),
                name="workout_sessions_location_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"workout session {self.id}"


class WorkoutSet(models.Model):
    """One planned set: the rule's target and, once ticked, what was actually done."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    session = models.ForeignKey(WorkoutSession, on_delete=models.CASCADE, related_name="sets")
    slot_key = models.TextField()
    slot_order = models.SmallIntegerField()
    exercise_key = models.TextField()
    exercise_name = models.TextField()
    kind = models.TextField()
    set_number = models.SmallIntegerField()
    reps_low = models.SmallIntegerField()
    reps_high = models.SmallIntegerField()
    target_weight_lbs = models.FloatField(null=True, blank=True)
    rule = models.TextField()
    rule_words = models.TextField(default="")
    done = models.BooleanField(default=False)
    reps_done = models.IntegerField(null=True, blank=True)
    weight_lbs = models.FloatField(null=True, blank=True)
    done_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "workout_sets"
        ordering = ["slot_order", "set_number"]
        constraints = [
            models.UniqueConstraint(
                fields=["session", "exercise_key", "set_number"], name="workout_sets_one_per_number"
            ),
            models.CheckConstraint(
                condition=models.Q(
                    rule__in=("calibrate", "add", "hold", "reset", "deload", "reps", "extra")
                ),
                name="workout_sets_rule_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(reps_low__gt=0) & models.Q(reps_high__gte=models.F("reps_low")),
                name="workout_sets_range_ordered",
            ),
            models.CheckConstraint(
                condition=models.Q(set_number__gte=1), name="workout_sets_number_positive"
            ),
            models.CheckConstraint(
                condition=models.Q(reps_done__isnull=True)
                | (models.Q(reps_done__gte=0) & models.Q(reps_done__lte=WORKOUT_REPS_MAX)),
                name="workout_sets_reps_range",
            ),
            models.CheckConstraint(
                condition=models.Q(weight_lbs__isnull=True)
                | (models.Q(weight_lbs__gte=0) & models.Q(weight_lbs__lt=WORKOUT_WEIGHT_LBS_MAX)),
                name="workout_sets_weight_range",
            ),
            models.CheckConstraint(
                condition=models.Q(target_weight_lbs__isnull=True)
                | (
                    models.Q(target_weight_lbs__gt=0)
                    & models.Q(target_weight_lbs__lt=WORKOUT_WEIGHT_LBS_MAX)
                ),
                name="workout_sets_target_range",
            ),
        ]

    def __str__(self) -> str:
        return f"workout set {self.id}"


# =============================================================================
# Programme change proposals (body coach 2, 2026-11-06). Rules in `body_coach/programme_change.py`.
# =============================================================================

PROPOSAL_PENDING = STATUS_PENDING
PROGRAMME_PROPOSAL_STATUSES = PROPOSAL_STATUSES


class ProgrammeProposal(models.Model):
    """One proposed change to the household's programme, built by the engine from structured
    choices (never an exercise or a number the model wrote). Kevin accepts or dismisses it, like an
    `EnergyProposal`. **The programme before and after are stored beside the request**, so what
    an acceptance did can be read back from this row alone."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    programme = models.ForeignKey(
        TrainingProgramme, on_delete=models.PROTECT, related_name="proposals"
    )
    # The structured choices it was asked for, as the engine read them.
    request = models.JSONField(default=dict)
    # {template, training_days, swaps, exclude_equipment, avoid, cardio}
    before = models.JSONField()
    after = models.JSONField()
    # kept / dropped / added / paused exercises, and whether the split changed.
    diff = models.JSONField(default=dict)
    words = models.TextField()
    status = models.TextField(default=STATUS_PENDING)
    created_at = models.DateTimeField(db_default=Now())
    decided_at = models.DateTimeField(null=True, blank=True)
    decided_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )

    class Meta:
        db_table = "programme_proposals"
        indexes = [
            models.Index(fields=["household", "created_at"], name="programme_proposals_made_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=PROPOSAL_STATUSES),
                name="programme_proposals_status_valid",
            ),
            models.UniqueConstraint(
                fields=["household"],
                condition=models.Q(status=STATUS_PENDING),
                name="programme_proposals_one_pending",
            ),
        ]

    def __str__(self) -> str:
        return f"programme proposal {self.id}"


# =============================================================================
# The plan (body coach 2, Kevin 2026-11-06). Rules in `body_coach/plans.py`.
# =============================================================================
#
# *"basically formulating a plan, working together with alfred, then locking it in and making
# adjustments as i go with alfred is the goal"*. One row per VERSION of one member's plan: a draft
# Alfred and he shape by voice, locked into the active version, superseded by the next version an
# accepted proposal makes. **Private to its member** (`owner_user`, never null; `OWNER_PATHS`), like
# the profile it is built from. The estimate's inputs are stored beside the figures they gave.

PLAN_DRAFT = "draft"
PLAN_ACTIVE = "active"
PLAN_SUPERSEDED = "superseded"
PLAN_DISCARDED = "discarded"
PLAN_STATUSES: tuple[str, ...] = (PLAN_DRAFT, PLAN_ACTIVE, PLAN_SUPERSEDED, PLAN_DISCARDED)
TARGET_BY_ALFRED = "alfred"
TARGET_BY_MEMBER = "member"
TARGET_SETTERS: tuple[str, ...] = (TARGET_BY_ALFRED, TARGET_BY_MEMBER)


class BodyPlan(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    # 1, 2, 3... once locked; a draft or a discarded draft has none.
    version = models.IntegerField(null=True, blank=True)
    status = models.TextField(default=PLAN_DRAFT)
    kcal_target = models.IntegerField(null=True, blank=True, help_text=ESTIMATE_HELP)
    protein_g = models.IntegerField(null=True, blank=True, help_text=ESTIMATE_HELP)
    rate_bp = models.SmallIntegerField(default=TARGET_RATE_BP_DEFAULT)
    # Alfred's, or a target the member set himself (kept until "let Alfred set it").
    target_set_by = models.TextField(default=TARGET_BY_ALFRED)
    # `programme_change.State.as_dict()`: template, sessions (as inert days), swaps, filters, cardio.
    programme = models.JSONField(default=dict)
    meal_approach = models.TextField(default="saved_meals")
    nudges = models.JSONField(default=dict, blank=True)
    # One line: why this version exists ("first plan", "calorie target 2100 to 1950 kcal: ...").
    reason = models.TextField(default="")
    # Maintenance, its basis, the weight and the deficit the figures were computed from.
    inputs = models.JSONField(default=dict, blank=True)
    # The `meal_targets` row locking wrote, so a later target can be told apart as his own.
    meal_target_origin_guid = models.TextField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    locked_at = models.DateTimeField(null=True, blank=True)
    locked_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )

    class Meta:
        db_table = "body_plans"
        indexes = [
            models.Index(fields=["household", "owner_user", "created_at"], name="body_plans_made_idx"),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=PLAN_STATUSES), name="body_plans_status_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(target_set_by__in=TARGET_SETTERS),
                name="body_plans_target_set_by_valid",
            ),
            models.UniqueConstraint(
                fields=["household", "owner_user"],
                condition=models.Q(status=PLAN_DRAFT),
                name="body_plans_one_draft",
            ),
            models.UniqueConstraint(
                fields=["household", "owner_user"],
                condition=models.Q(status=PLAN_ACTIVE),
                name="body_plans_one_active",
            ),
            models.UniqueConstraint(
                fields=["household", "owner_user", "version"],
                condition=models.Q(version__isnull=False),
                name="body_plans_version_once",
            ),
            models.CheckConstraint(
                condition=models.Q(kcal_target__isnull=True) | models.Q(kcal_target__gt=800),
                name="body_plans_above_800_floor",
            ),
            models.CheckConstraint(
                condition=models.Q(rate_bp__gte=TARGET_RATE_BP_MIN)
                & models.Q(rate_bp__lte=TARGET_RATE_BP_MAX),
                name="body_plans_rate_range",
            ),
        ]

    def __str__(self) -> str:
        return f"body plan {self.id}"
