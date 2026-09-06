"""The 41 `public` tables, column-exact, as `managed = False` Django
models. Split into one file per domain, matching ticket 02's own table
(django-engine map) - not ten separate Django apps yet, that split happens
only when a domain's tables move from `supabase/migrations/` ownership to
Django's (ADR 0044). Every model is re-exported here so `from legacy.models
import Vehicle` (or any other model) works regardless of which file it
actually lives in - Django's app registry resolves them to the `legacy`
app either way, since they are all submodules of this package.
"""
from __future__ import annotations

from legacy.models.body import (
    BodyweightLog,
    Goal,
    SleepLog,
    SleepTarget,
    WorkoutPlan,
    WorkoutPlanItem,
    WorkoutSetLog,
)
from legacy.models.dates import Event, EventSkip
from legacy.models.fleet import (
    BuildEntry,
    ChassisQuirk,
    CodeClearEvent,
    CodeEvent,
    Drive,
    DriveReassignment,
    MaintenanceSchedule,
    ObdSample,
    OilAnalysis,
    ServiceHistory,
    Vehicle,
    VehicleSpec,
)
from legacy.models.household import LegacyHouseholdMember
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import (
    BudgetTarget,
    Category,
    CategoryRule,
    LedgerTransaction,
    Statement,
)
from legacy.models.memory import (
    CompanionMemory,
    ConversationAudit,
    Memory,
    MemoryAudit,
)
from legacy.models.notes import ItemList, ListItem, VoiceNote
from legacy.models.pantry import GroceryStaple, MealLog, MealTarget, Receipt, ReceiptLineItem
from legacy.models.places import Place

__all__ = [
    "BodyweightLog",
    "BudgetTarget",
    "BuildEntry",
    "Category",
    "CategoryRule",
    "ChassisQuirk",
    "CodeClearEvent",
    "CodeEvent",
    "CompanionMemory",
    "ConversationAudit",
    "Drive",
    "DriveReassignment",
    "Event",
    "EventSkip",
    "Goal",
    "GroceryStaple",
    "IngestedFile",
    "ItemList",
    "LedgerTransaction",
    "LegacyHouseholdMember",
    "ListItem",
    "MaintenanceSchedule",
    "MealLog",
    "MealTarget",
    "Memory",
    "MemoryAudit",
    "ObdSample",
    "OilAnalysis",
    "Place",
    "Receipt",
    "ReceiptLineItem",
    "ServiceHistory",
    "SleepLog",
    "SleepTarget",
    "Statement",
    "Vehicle",
    "VehicleSpec",
    "VoiceNote",
    "WorkoutPlan",
    "WorkoutPlanItem",
    "WorkoutSetLog",
]
