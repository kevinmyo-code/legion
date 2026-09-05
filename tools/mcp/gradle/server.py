"""MCP server over the LEGION Gradle build.

Three tools. `run_task` is the only one that changes anything on disk, and what it changes is
Gradle's own output under `app/build/`. `test_totals` and `detekt_summary` read reports that a
previous run left behind; they never start Gradle, so they are safe to call while another agent
owns the build.

Totals come from the JUnit XML under `app/build/test-results/`, never from the console summary
(CLAUDE.md section 6). One Gradle writer at a time: two agents running Gradle in one tree corrupt
each other's builds, and contention can fake a passing run.

    uv run --project tools/mcp/gradle tools/mcp/gradle/server.py
"""
import glob
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from mcp.server.fastmcp import FastMCP

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
TEST_RESULTS = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest")
DETEKT_XML = os.path.join(ROOT, "app", "build", "reports", "detekt", "detekt.xml")
DETEKT_BASELINE = os.path.join(ROOT, "config", "detekt", "baseline.xml")
LOG_DIR = os.path.join(ROOT, "build", "mcp-logs")
TAIL_LINES = 40

mcp = FastMCP("legion-gradle")


def _gradlew() -> str:
    return os.path.join(ROOT, "gradlew.bat" if sys.platform.startswith("win") else "gradlew")


@mcp.tool()
def run_task(task: str, args: str = "", timeout_seconds: int = 1800) -> dict:
    """Runs one Gradle task from the repo root and reports how it ended.

    THIS STARTS GRADLE. Only one agent may run Gradle in this tree at a time; check that nobody
    else owns the build before calling. `task` is the task name (`compileDebugKotlin`,
    `testDebugUnitTest`, `assembleDebug`); `args` is extra flags, space-separated (`-Pnokey`).
    Full stdout and stderr stream to a log file under build/mcp-logs/; the result carries the
    exit code, the log path and the last 40 lines. Exit code 0 means Gradle reported success;
    anything else means the task did NOT complete and the tail says why.
    """
    if not task or task.startswith("-"):
        return {"ran": False, "error": "task must be a Gradle task name, not a flag"}
    gradlew = _gradlew()
    if not os.path.isfile(gradlew):
        return {"ran": False, "error": "gradlew not found at %s" % gradlew}
    os.makedirs(LOG_DIR, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log_path = os.path.join(LOG_DIR, "%s-%s.log" % (task.replace(":", "_"), stamp))
    cmd = [gradlew, task] + [a for a in args.split() if a]
    started = time.time()
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        log.write("$ %s\n" % " ".join(cmd))
        log.flush()
        try:
            proc = subprocess.run(
                cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                timeout=timeout_seconds, check=False,
            )
            code = proc.returncode
            timed_out = False
        except subprocess.TimeoutExpired:
            code = None
            timed_out = True
    with open(log_path, encoding="utf-8", errors="replace") as fh:
        tail = fh.read().splitlines()[-TAIL_LINES:]
    return {
        "ran": True,
        "task": task,
        "command": " ".join(cmd),
        "exit_code": code,
        "success": code == 0,
        "timed_out": timed_out,
        "seconds": round(time.time() - started, 1),
        "log": log_path,
        "tail": tail,
        "note": None if code == 0 else "Gradle did not finish successfully; nothing downstream of this task should be assumed built.",
    }


@mcp.tool()
def test_totals() -> dict:
    """Sums the JUnit XML under app/build/test-results/testDebugUnitTest/ from the LAST run.

    Does not run Gradle. Returns tests / failures / errors / skipped / files, the names of every
    failing or erroring test, and the newest report timestamp so a stale run is visible. If no
    XML exists the result says so rather than reporting zero.
    """
    files = sorted(glob.glob(os.path.join(TEST_RESULTS, "*.xml")))
    if not files:
        return {"present": False, "error": "no JUnit XML under %s; the suite has not run in this tree" % TEST_RESULTS}
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "files": 0}
    failing: list[dict] = []
    unparsed: list[str] = []
    newest_mtime = 0.0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            unparsed.append("%s: %s" % (os.path.basename(path), exc))
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(suite.get(key, "0") or 0)
            for case in suite.findall("testcase"):
                failure = case.find("failure")
                error = case.find("error")
                if failure is not None or error is not None:
                    node = failure if failure is not None else error
                    failing.append({
                        "test": "%s.%s" % (case.get("classname", ""), case.get("name", "")),
                        "kind": "failure" if failure is not None else "error",
                        "message": (node.get("message") or "")[:300],
                    })
        totals["files"] += 1
        newest_mtime = max(newest_mtime, os.path.getmtime(path))
    return {
        "present": True,
        **totals,
        "green": totals["failures"] == 0 and totals["errors"] == 0 and not unparsed,
        "failing": failing,
        "unparsed": unparsed,
        "newest_report": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(newest_mtime)),
        "source": TEST_RESULTS,
    }


@mcp.tool()
def detekt_summary(limit: int = 50) -> dict:
    """Reads app/build/reports/detekt/detekt.xml (checkstyle format) from the last detekt run.

    Does not run Gradle. Returns the finding count, counts per rule and per file, and up to
    `limit` findings with location and message. Findings suppressed by config/detekt/baseline.xml
    are already absent from the report; the result says whether a baseline exists so zero is not
    misread as clean. If the report is missing the result says so.
    """
    if not os.path.isfile(DETEKT_XML):
        return {"present": False, "error": "no detekt report at %s; run `detekt` first" % DETEKT_XML}
    try:
        root = ET.parse(DETEKT_XML).getroot()
    except ET.ParseError as exc:
        return {"present": True, "error": "detekt.xml did not parse: %s" % exc}
    by_rule: dict[str, int] = {}
    by_file: dict[str, int] = {}
    findings: list[dict] = []
    for file_node in root.findall("file"):
        name = os.path.relpath(file_node.get("name", ""), ROOT) if file_node.get("name") else ""
        for err in file_node.findall("error"):
            rule = (err.get("source") or "").rsplit(".", 1)[-1]
            by_rule[rule] = by_rule.get(rule, 0) + 1
            by_file[name] = by_file.get(name, 0) + 1
            if len(findings) < limit:
                findings.append({
                    "file": name, "line": err.get("line"), "rule": rule,
                    "severity": err.get("severity"), "message": (err.get("message") or "")[:300],
                })
    total = sum(by_rule.values())
    return {
        "present": True,
        "findings": total,
        "by_rule": dict(sorted(by_rule.items(), key=lambda kv: -kv[1])),
        "by_file": dict(sorted(by_file.items(), key=lambda kv: -kv[1])),
        "sample": findings,
        "truncated": total > len(findings),
        "baseline_present": os.path.isfile(DETEKT_BASELINE),
        "report_time": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(os.path.getmtime(DETEKT_XML))),
        "source": DETEKT_XML,
    }


if __name__ == "__main__":
    mcp.run()
