#!/usr/bin/env python3
"""Generate `docs/voice.html` and `ui/help/VoiceGuideData.kt` - the user-facing and in-app guides
to what LEGION can do by voice, from ONE source (command-center ticket 09).

**Why this is generated rather than written.** `service/LiveToolbox.kt` declares 97+ voice tools
and grows most weeks. A hand-written page listing them is stale the day after it is written, and a
stale "what can I ask it" page is worse than none: it teaches phrases that no longer work and omits
the ones that do. The in-app screen is the same problem twice over - it is also the more urgent
copy, because ADR 0035 (`docs/adr/0035-every-voice-capability-has-a-hands-path.md`) means every
entry now also claims a HANDS path, and a stale claim there is not just outdated, it is a lie about
where a button is.

**How drift is made impossible rather than unlikely.** The tool NAMES come from the Kotlin source, so
nothing can be missed. The user-facing COPY lives in `tools/voice_guide_copy.py`, keyed by tool name,
now a 3-tuple of (say, does, hands). If a tool exists in the code with no copy - or copy exists for a
tool that has been deleted - this script **exits non-zero and names it**. Adding a voice tool
therefore forces a decision about how to explain it AND where its hands path is, in the same way
`docs_check.py` forces a decision about a moved source file.

**`ui/help/VoiceGuideData.kt` is generated from the exact same `COPY`/`GROUPS`/`INTRO`/`GROUP_BLURBS`
that `docs/voice.html` reads.** There is no second copy to fall out of sync - `write_kotlin()` below
renders the same Python dict into a Kotlin object literal. If that file is stale relative to
`voice_guide_copy.py` (hand-edited, or the source changed and the generator did not rerun), `--check`
fails and says so, exactly like the HTML/Kotlin's-own drift check already does for the tool list.

The model-facing `description` in the Kotlin `LiveToolbox` is deliberately NOT used anywhere in this
pipeline. It is written to steer a language model - dense, full of internal caveats, and often about
what the model must not do. It reads badly to a human and would leak implementation reasoning onto a
public page or an in-app screen.

    python tools/voice_guide.py          # write docs/voice.html + ui/help/VoiceGuideData.kt
    python tools/voice_guide.py --check  # report drift only, write nothing
"""
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLBOX = ROOT / 'app/src/main/java/com/kevin/legion/service/LiveToolbox.kt'
OUT = ROOT / 'docs/voice.html'
KOTLIN_OUT = ROOT / 'app/src/main/java/com/kevin/legion/ui/help/VoiceGuideData.kt'

sys.path.insert(0, str(Path(__file__).resolve().parent))
from voice_guide_copy import GROUPS, COPY, INTRO, GROUP_BLURBS  # noqa: E402

# `name = "..."` inside a fn(...) declaration. Param names use a different shape
# ("x" to schema(...)), so this cannot pick them up by accident.
NAME_RE = re.compile(r'name = "([a-z_]+)"')


def declared_tools():
    src = TOOLBOX.read_text(encoding='utf-8')
    return sorted(set(NAME_RE.findall(src)))


def check(tools):
    """Returns a list of drift problems. Empty means the copy and the code agree."""
    problems = []
    documented = set(COPY)
    for t in tools:
        if t not in documented:
            problems.append(f'tool "{t}" is declared in LiveToolbox.kt but has no entry in voice_guide_copy.py')
    for t in sorted(documented - set(tools)):
        problems.append(f'copy exists for "{t}" but no such tool is declared - was it renamed or removed?')
    grouped = {t for g in GROUPS.values() for t in g}
    for t in tools:
        if t in documented and t not in grouped:
            problems.append(f'tool "{t}" has copy but is in no group, so it would not render')
    return problems


def render(tools):
    parts = [
        '<title>Talking to LEGION</title>',
        STYLE,
        '<main>',
        '<h1>Talking to LEGION</h1>',
        f'<p class="intro">{INTRO}</p>',
        f'<p class="count">{len(tools)} things it can do by voice.</p>',
    ]
    for group, names in GROUPS.items():
        live = [n for n in names if n in tools]
        if not live:
            continue
        parts.append(f'<section><h2>{html.escape(group)}</h2>')
        blurb = GROUP_BLURBS.get(group)
        if blurb:
            parts.append(f'<p class="blurb">{blurb}</p>')
        parts.append('<dl>')
        for n in live:
            say, does, hands = COPY[n]
            parts.append(
                f'<dt>&ldquo;{html.escape(say)}&rdquo;</dt>'
                f'<dd>{html.escape(does)}<span class="hands">By hand: {html.escape(hands)}</span></dd>'
            )
        parts.append('</dl></section>')
    parts.append(FOOTER)
    parts.append('</main>')
    return '\n'.join(parts)


STYLE = """<style>
:root{--bg:#0f1113;--fg:#e8e6e3;--dim:#9aa0a6;--accent:#d4a24c;--line:#24282c;--card:#15181b}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
 font:16px/1.6 ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:820px;margin:0 auto;padding:48px 20px 96px}
h1{font-size:1.9rem;letter-spacing:.02em;margin:0 0 .3em}
h2{font-size:1.05rem;text-transform:uppercase;letter-spacing:.12em;color:var(--accent);
 margin:2.6em 0 .2em;border-bottom:1px solid var(--line);padding-bottom:.4em}
p.intro{color:var(--fg);margin:0 0 .6em}
p.count{color:var(--dim);font-size:.85rem;margin:0 0 1em}
p.blurb{color:var(--dim);font-size:.9rem;margin:.6em 0 1.2em}
dl{margin:0}
dt{margin-top:1.1em;color:var(--fg);font-weight:600}
dd{margin:.15em 0 0;color:var(--dim);font-size:.93rem}
dd .hands{display:block;margin-top:.25em;color:var(--accent);font-size:.85rem}
footer{margin-top:4em;padding-top:1.4em;border-top:1px solid var(--line);
 color:var(--dim);font-size:.85rem}
footer strong{color:var(--fg)}
code{background:var(--card);padding:.1em .35em;border-radius:3px;font-size:.9em}
@media(prefers-color-scheme:light){
 :root{--bg:#faf9f7;--fg:#1a1c1e;--dim:#5f6368;--card:#eeece8;--line:#dcd8d2;--accent:#8a5a12}}
</style>"""

FOOTER = """<footer>
<p><strong>You do not have to say these exactly.</strong> Ask in your own words - the phrases above
are examples, not commands. If it cannot do something, it says so rather than pretending.</p>
<p><strong>Some of these need a key or a permission.</strong> Music needs Spotify connected, mail and
calendar need a Google account, caller ID needs phone permissions, and everything spoken needs a
Gemini key. The Setup screen says which are missing.</p>
<p><strong>Every entry says where the same thing is reachable by hand</strong>, or says
&ldquo;Voice only.&rdquo; when there is not yet a button for it - <a href="https://github.com/kevinmyo-code/legion/blob/main/docs/adr/0035-every-voice-capability-has-a-hands-path.md">ADR 0035</a>.
The app's own Setup &gt; Assistant &gt; &ldquo;What can I do&rdquo; screen shows the same list,
searchable, without leaving the app.</p>
<p>Generated from <code>LiveToolbox.kt</code> by <code>tools/voice_guide.py</code> - if a tool exists
in the app it is on this page, because the build fails otherwise.</p>
</footer>"""


README = ROOT / 'README.md'
README_START = '<!-- VOICE-SURFACE:START -->'
README_END = '<!-- VOICE-SURFACE:END -->'
NL = chr(10)


def readme_block(tools):
    """The condensed table for README.md - a different audience from docs/voice.html.

    That page teaches someone how to USE the app. This block is read by someone assessing the
    ENGINEERING: a recruiter or an interviewer skimming for scope and judgement. So it leads with
    shape and volume rather than instructions, and links out for the full list.

    Generated from the same source for the same reason: a capability table in a README is exactly
    the kind of thing that quietly rots into a lie about the project.
    """
    rows = []
    for group, names in GROUPS.items():
        live = [n for n in names if n in tools]
        if not live:
            continue
        example = COPY[live[0]][0]
        rows.append(f'| {group} | {len(live)} | &ldquo;{example}&rdquo; |')
    return NL.join([
        README_START,
        '',
        f'**{len(tools)} voice tools across {len(rows)} domains**, dispatched from one Gemini Live',
        'socket. Every one is declared with a schema and a description written to constrain the',
        'model rather than to sell the feature.',
        '',
        '| Domain | Tools | Something you would say |',
        '|---|---|---|',
        *rows,
        '',
        'Full list with plain-language descriptions: **[docs/voice.html](docs/voice.html)** -',
        'generated from `LiveToolbox.kt`, so a tool that exists is on the page or the build fails.',
        '',
        README_END,
    ])


def write_readme(tools):
    text = README.read_text(encoding='utf-8')
    block = readme_block(tools)
    if README_START in text and README_END in text:
        pre = text.split(README_START)[0]
        post = text.split(README_END)[1]
        README.write_text(pre + block + post, encoding='utf-8')
        return 'updated'
    return 'markers-missing'


KOTLIN_HEADER = """// GENERATED FILE - DO NOT HAND EDIT.
//
// Produced by `python tools/voice_guide.py` from `tools/voice_guide_copy.py` - the exact same
// source `docs/voice.html` is generated from (command-center ticket 09, ADR 0035's in-app hands
// path). Edit the copy there and rerun the script; a hand edit here is overwritten on the next run
// and, if it diverges first, `voice_guide.py --check` fails the build and names the drift - same
// posture as the tool-list check this file's generator already enforces.
"""


def _kt_string(s: str) -> str:
    """Escapes a Python string for a Kotlin double-quoted string literal. `$` is escaped too -
    Kotlin treats it as template interpolation, and several hands-path/description strings in
    `voice_guide_copy.py` are free text that could pick one up by accident later."""
    return (
        s.replace('\\', '\\\\')
        .replace('"', '\\"')
        .replace('$', '\\$')
        .replace('\n', '\\n')
    )


def kotlin_source(tools) -> str:
    """Renders `ui/help/VoiceGuideData.kt` - one `Group` per live [docs/voice.html] section, each
    holding the tool's `say`/`does`/`hands` triple verbatim from `voice_guide_copy.COPY`. Kept
    intentionally dumb (a flat object literal, no logic) so a diff against the previous run is
    exactly a diff of the copy that changed.
    """
    lines = [KOTLIN_HEADER, 'package com.kevin.legion.ui.help', '']
    lines.append('/**')
    lines.append(' * Data for the in-app "What can I do" screen (`ui/help/VoiceGuideScreen.kt`).')
    lines.append(' * See the header above: this whole file is generated, never hand edit it.')
    lines.append(' */')
    lines.append('object VoiceGuideData {')
    lines.append('')
    lines.append('    /**')
    lines.append('     * One voice capability. [hands] is ADR 0035\'s field: where the same')
    lines.append('     * capability is reachable without voice, or "Voice only." when it is not yet.')
    lines.append('     */')
    lines.append('    data class Entry(')
    lines.append('        val name: String,')
    lines.append('        val say: String,')
    lines.append('        val does: String,')
    lines.append('        val hands: String,')
    lines.append('    )')
    lines.append('')
    lines.append('    data class Group(')
    lines.append('        val title: String,')
    lines.append('        val blurb: String?,')
    lines.append('        val entries: List<Entry>,')
    lines.append('    )')
    lines.append('')
    lines.append(f'    val INTRO: String = "{_kt_string(INTRO)}"')
    lines.append('')
    lines.append('    val GROUPS: List<Group> = listOf(')
    total = 0
    for group, names in GROUPS.items():
        live = [n for n in names if n in tools]
        if not live:
            continue
        blurb = GROUP_BLURBS.get(group)
        blurb_kt = f'"{_kt_string(blurb)}"' if blurb else 'null'
        lines.append(f'        Group(')
        lines.append(f'            title = "{_kt_string(group)}",')
        lines.append(f'            blurb = {blurb_kt},')
        lines.append(f'            entries = listOf(')
        for n in live:
            say, does, hands = COPY[n]
            lines.append(
                f'                Entry(name = "{n}", say = "{_kt_string(say)}", '
                f'does = "{_kt_string(does)}", hands = "{_kt_string(hands)}"),'
            )
            total += 1
        lines.append('            ),')
        lines.append('        ),')
    lines.append('    )')
    lines.append('')
    lines.append(f'    /** Total entries across every group above - {total} as of the last regeneration. */')
    lines.append(f'    val TOOL_COUNT: Int = {total}')
    lines.append('}')
    lines.append('')
    return '\n'.join(lines)


def write_kotlin(tools) -> str:
    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    src = kotlin_source(tools)
    if KOTLIN_OUT.exists() and KOTLIN_OUT.read_text(encoding='utf-8') == src:
        return 'unchanged'
    KOTLIN_OUT.write_text(src, encoding='utf-8')
    return 'written'


def kotlin_drift_problems(tools):
    """`--check`'s half of the Kotlin generator: the generated file must equal what the copy
    source would produce RIGHT NOW. A missing file is drift too (never generated at all), not a
    pass - the whole point is that the in-app screen cannot silently fall behind the copy that
    feeds `docs/voice.html`."""
    expected = kotlin_source(tools)
    if not KOTLIN_OUT.exists():
        return [f'{KOTLIN_OUT.relative_to(ROOT)} does not exist - run `python tools/voice_guide.py` to generate it']
    actual = KOTLIN_OUT.read_text(encoding='utf-8')
    if actual != expected:
        return [
            f'{KOTLIN_OUT.relative_to(ROOT)} is stale relative to tools/voice_guide_copy.py - '
            'rerun `python tools/voice_guide.py`'
        ]
    return []


def main():
    tools = declared_tools()
    problems = check(tools)
    if '--check' in sys.argv:
        problems += kotlin_drift_problems(tools)
    if problems:
        print(f'voice_guide: {len(problems)} problem(s)')
        for p in problems:
            print('  ' + p)
        return 1
    if '--check' in sys.argv:
        print(f'voice_guide: {len(tools)} tools, copy complete, no drift')
        return 0
    OUT.write_text(render(tools), encoding='utf-8')
    status = write_readme(tools)
    kotlin_status = write_kotlin(tools)
    print(f'{OUT.relative_to(ROOT)}: {len(tools)} tools across {len(GROUPS)} groups')
    print(f'README.md: {status}')
    print(f'{KOTLIN_OUT.relative_to(ROOT)}: {kotlin_status}')
    if status == 'markers-missing':
        print('  add <!-- VOICE-SURFACE:START --> and <!-- VOICE-SURFACE:END --> to README.md')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
