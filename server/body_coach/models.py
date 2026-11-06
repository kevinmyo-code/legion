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

TARGET_RATE_BP_DEFAULT = 75
TARGET_RATE_BP_MIN, TARGET_RATE_BP_MAX = 25, 100
PROTEIN_TENTHS_DEFAULT = 20
PROTEIN_TENTHS_MIN, PROTEIN_TENTHS_MAX = 16, 22
# Plausibility bounds, not targets: a typo (178 typed into a millimetre field) is refused.
HEIGHT_MM_MIN, HEIGHT_MM_MAX = 1000, 2500
BIRTH_YEAR_MIN, BIRTH_YEAR_MAX = 1900, 2100
WAIST_MM_MAX = 3000

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
