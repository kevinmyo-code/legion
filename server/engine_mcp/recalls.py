"""NHTSA's public recall list, by model year, make and model (parity quick
wins, 2026-11-02; the phone's `check_recalls`, `vehicle/VinDecoder.kt`).

Keyless and public: `https://api.nhtsa.gov/recalls/recallsByVehicle`. Nothing
of the household's is sent but the three words that name the car, and the
answer is read through and never stored (the phone keeps none either).

`fetch_recalls` is the one network call, so tests replace it and never
reach the network. A call that does not complete, or an answer that is not
the shape NHTSA sends, raises `RecallLookupFailed`: "could not check" is a
different sentence from "NHTSA lists none" (fleet-maintenance ticket 12).
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

RECALLS_URL = "https://api.nhtsa.gov/recalls/recallsByVehicle"
TIMEOUT_SECONDS = 8
USER_AGENT = "LEGION engine (household assistant)"


class RecallLookupFailed(Exception):
    """NHTSA was not reached, or answered in a shape this cannot read."""


def parse_recalls(raw: bytes | str) -> list[dict]:
    try:
        body = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise RecallLookupFailed("the answer was not JSON") from exc
    results = body.get("results") if isinstance(body, dict) else None
    if not isinstance(results, list):
        raise RecallLookupFailed("the answer had no results list")
    recalls = []
    for row in results:
        if not isinstance(row, dict):
            continue
        recalls.append(
            {
                "campaign": str(row.get("NHTSACampaignNumber") or "").strip(),
                "component": str(row.get("Component") or "").strip(),
                "summary": str(row.get("Summary") or "").strip(),
                "remedy": str(row.get("Remedy") or "").strip(),
                "report_received": str(row.get("ReportReceivedDate") or "").strip(),
            }
        )
    return recalls


def fetch_recalls(make: str, model: str, year: int) -> list[dict]:
    query = urllib.parse.urlencode({"make": make, "model": model, "modelYear": year})
    request = urllib.request.Request(
        f"{RECALLS_URL}?{query}", headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            raw = response.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise RecallLookupFailed(f"NHTSA could not be reached ({type(exc).__name__})") from exc
    return parse_recalls(raw)
