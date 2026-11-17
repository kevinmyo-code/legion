"""`manage.py voice_probe`: stage 0's engine-side numbers (engine-brain ticket 01, plan 4.5).

Run on Cloud Run as a `legion-worker` job execution, so it measures from the engine's own region:

    gcloud run jobs execute legion-worker --region us-south1 --wait \\
        --args=manage.py,voice_probe
    gcloud logging read 'resource.type="cloud_run_job" AND
        labels."run.googleapis.com/execution_name"="<exec>"' --format='value(textPayload)'

It measures, and prints **timings and token counts only** (no prompt, no reply, no row, no key):
1. Postgres: `--pg` (20) `SELECT 1` round trips, and one read of 50 event ids (the shape of a
   `list_events` query) - per-query ms, p50 / p90.
2. Gemini first token and total, `--n` (10) streamed flash-lite calls with the generalist's real
   prefix (the locked text, its spec prompt and today's whole engine tool surface as
   declarations; no household data), a one-word answer, output capped at 16 tokens:
   - uncached;
   - with that prefix in an explicit cache (created for 5 minutes, deleted after).

Writes nothing: no `llm_usage` row, no trace, no table touched. Cost: about 20 calls of the
prefix's size, half of them at the cached rate; printed as an ESTIMATE (a few cents).

Cold start is not measured here (a job is its own container); `tools/agents_hop_probe.py` measures
it, and the hop and SSE, from a real network against the service.
"""

from __future__ import annotations

import statistics
import time

from django.core.management.base import BaseCommand, CommandError
from django.db import connection

from agents.legion.specs import spec as spec_named
from agents.legion.turn import cost_of, system_for
from agents.runtime.model import (
    GeminiClient,
    ModelFailure,
    ModelRequest,
    PartEvent,
    Usage,
)
from assistant import live
from assistant.surface import web_description, web_tools
from ingest.statements import gemini_key

QUESTION = "Reply with the single word: ok."


def _pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, round(p * (len(ordered) - 1))))
    return ordered[k]


def _summary(name: str, values: list[float]) -> str:
    if not values:
        return f"{name}: no samples"
    return (
        f"{name}: n={len(values)} p50={_pct(values, 0.5):.0f}ms p90={_pct(values, 0.9):.0f}ms "
        f"min={min(values):.0f}ms max={max(values):.0f}ms mean={statistics.fmean(values):.0f}ms"
    )


class Command(BaseCommand):
    help = "Measure Postgres and Gemini from the engine's region; prints timings only."

    def add_arguments(self, parser):
        parser.add_argument("--n", type=int, default=10)
        parser.add_argument("--pg", type=int, default=20)
        parser.add_argument("--no-gemini", action="store_true")
        parser.add_argument("--no-cache", action="store_true")

    def handle(self, *args, **opts):
        self._postgres(opts["pg"])
        if not opts["no_gemini"]:
            self._gemini(opts["n"], not opts["no_cache"])

    def _postgres(self, n: int) -> None:
        samples = []
        with connection.cursor() as cursor:
            cursor.execute("select 1")  # the connection itself, not timed
            for _ in range(n):
                t0 = time.perf_counter()
                cursor.execute("select 1")
                cursor.fetchone()
                samples.append((time.perf_counter() - t0) * 1000)
            t0 = time.perf_counter()
            cursor.execute(
                "select id from public.events order by starts_at desc nulls last limit 50"
            )
            cursor.fetchall()
            read_ms = (time.perf_counter() - t0) * 1000
        self.stdout.write("voice_probe " + _summary("pg_select1", samples))
        self.stdout.write(f"voice_probe pg_events_read_50: {read_ms:.0f}ms")

    def _gemini(self, n: int, with_cache: bool) -> None:
        if not gemini_key():
            raise CommandError("LEGION_GEMINI_KEY is not set in this environment; nothing sent.")
        spec = spec_named("generalist")
        system = system_for(spec, None)
        declarations = live.function_declarations(web_tools(), web_description)
        client = GeminiClient(key=gemini_key)
        contents = [{"role": "user", "parts": [{"text": QUESTION}]}]
        total_cost = 0
        rows = []
        for label, cache_name in (("uncached", None), ("cached", "")):
            if label == "cached":
                if not with_cache:
                    break
                try:
                    cache_name = client.create_cache(spec.model, system, declarations, ttl_s=300)
                except ModelFailure as exc:
                    self.stdout.write(f"voice_probe cache: not created ({exc}); cached run skipped")
                    break
            first, total, prompt, cached = [], [], [], []
            try:
                for _ in range(n):
                    req = ModelRequest(
                        model=spec.model,
                        system=system,
                        contents=contents,
                        declarations=[] if cache_name else declarations,
                        allow_tools=False,
                        timeout_s=20,
                        max_output_tokens=16,
                        cached_content=cache_name or None,
                    )
                    t0 = time.perf_counter()
                    first_ms = None
                    usage = Usage()
                    try:
                        for ev in client.stream(req):
                            if isinstance(ev, PartEvent) and first_ms is None:
                                first_ms = (time.perf_counter() - t0) * 1000
                            elif isinstance(ev, Usage):
                                usage = ev
                    except ModelFailure as exc:
                        self.stdout.write(f"voice_probe gemini_{label}: call failed ({exc})")
                        continue
                    total.append((time.perf_counter() - t0) * 1000)
                    if first_ms is not None:
                        first.append(first_ms)
                    prompt.append(usage.prompt_tokens)
                    cached.append(usage.cached_tokens)
                    total_cost += cost_of(usage)
            finally:
                if cache_name:
                    status = client.delete_cache(cache_name)
                    self.stdout.write(f"voice_probe cache: deleted (HTTP {status})")
            rows.append((label, first, total, prompt, cached))
        for label, first, total, prompt, cached in rows:
            self.stdout.write("voice_probe " + _summary(f"gemini_{label}_first_token", first))
            self.stdout.write("voice_probe " + _summary(f"gemini_{label}_total", total))
            if prompt:
                self.stdout.write(
                    f"voice_probe gemini_{label}_tokens: prompt~{statistics.median(prompt):.0f} "
                    f"cached~{statistics.median(cached):.0f}"
                )
        self.stdout.write(
            f"voice_probe est_cost: ${total_cost / 1e6:.4f} ESTIMATE (calls only; cache storage "
            f"for under 5 minutes is extra and tiny)"
        )
