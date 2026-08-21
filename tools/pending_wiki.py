"""Regenerates docs/index.html - the progress wiki, and the GitHub Pages home page.

One page showing every OPEN ticket across every map, grouped by map, filterable by
ready / blocked / decision / buildable / built / KIV. Built from the same ticket frontmatter
tools/obsidian_sync.py maintains, so it cannot drift from the board: run

    python tools/pending_wiki.py

after resolving anything. A map with no open tickets disappears from the page entirely.

Palette and type are the app's own mission-control system (ui/theme/Color.kt) rather than
a second visual language: mint for values, amber for what needs Kevin, chrome red for a
fault, on the same near-black ground.
"""
import json, html, re, glob, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
DONE = {"resolved", "closed", "killed", "archived", "graduated", "fixed", "superseded"}
# Two states that are open but are not "go build this", added 2026-08-20 because the page was
# telling Kevin to build ten Spotify tickets that were already built:
#   built - the code exists and the suite is green; what it owes is a run on real hardware.
#           Labelled BUILT, not "test": the chip says what the ticket IS, and the state line
#           beside it says what is left. "build" on a built ticket is just wrong.
#   kiv  - parked on purpose. Still open, still listed, but never counted as ready and always last.
TEST = {"built"}
KIV = {"kiv"}


def _fm(path):
    t = open(path, encoding="utf-8-sig").read()
    if not t.startswith("---" + chr(10)):
        return None
    head = t.split(chr(10) + "---" + chr(10), 1)[0]
    out = {}
    for line in head.split(chr(10)):
        m = re.match(r"^([a-z-]+):\s*(.*)$", line)
        if m:
            out[m.group(1)] = m.group(2).strip().strip('"')
    return out


def collect():
    maps = {}
    for mp in sorted(glob.glob(".scratch/*/map.md")):
        slug = mp.replace(chr(92), "/").split("/")[1]
        f = _fm(mp) or {}
        maps[slug] = {"title": f.get("title", slug), "tickets": []}
    for tp in sorted(glob.glob(".scratch/*/issues/*.md")):
        slug = tp.replace(chr(92), "/").split("/")[1]
        f = _fm(tp)
        if not f:
            continue
        maps.setdefault(slug, {"title": slug, "tickets": []})
        maps[slug]["tickets"].append({
            "num": f.get("ticket", ""), "title": f.get("title", ""),
            "type": f.get("type", "task"), "status": f.get("status", "open"),
            "detail": f.get("status-detail", ""), "ready": f.get("ready") == "true",
            "test": f.get("status", "") in TEST, "kiv": f.get("status", "") in KIV,
            "blockedBy": re.findall(r"\d+", f.get("blockers", "")),
        })
    out = []
    for slug, m in maps.items():
        op = [t for t in m["tickets"] if t["status"] not in DONE]
        out.append({"slug": slug, "title": m["title"], "total": len(m["tickets"]),
                    # "ready" means ready TO BUILD. A built ticket owing a device run and a parked
                    # one are their own buckets, so the four never overlap and the stats add up.
                    "open": len(op),
                    "ready": len([t for t in op if t["ready"] and not t["test"] and not t["kiv"]]),
                    "test": len([t for t in op if t["test"]]),
                    "kiv": len([t for t in op if t["kiv"]]),
                    "tickets": op})
    # A map that is nothing but parked work sinks to the bottom of the page, whatever its size.
    out.sort(key=lambda x: (x["kiv"] == x["open"], -x["open"], x["slug"]))
    return out



# Reads the ticket frontmatter directly - no intermediate file, so the wiki can never
# describe a state the tickets are not actually in.
d = collect()
d = [m for m in d if m['open']]
tot = sum(m['open'] for m in d)
ready = sum(m['ready'] for m in d)
kiv_n = sum(m['kiv'] for m in d)
test_n = sum(m['test'] for m in d)
live = tot - kiv_n                      # everything not parked
dec = sum(1 for m in d for t in m['tickets'] if t['type'] == 'grilling' and not t['kiv'])
build = live - dec - test_n             # genuinely un-built work
blocked_n = live - ready - test_n
E = html.escape
NL = chr(10)
TYPE_LABEL = {'grilling': 'decide', 'task': 'build', 'bug': 'bug',
              'prototype': 'prototype', 'research': 'research'}
# Status wins over type for the chip. A built ticket must never still read "build".
STATUS_LABEL = {'built': 'built', 'kiv': 'KIV'}


def ticket(t):
    cls = ['t']
    if t['kiv']:
        cls.append('is-kiv')
    elif t['test']:
        cls.append('is-built')
    elif t['type'] == 'bug':
        cls.append('is-bug')
    elif t['type'] == 'grilling':
        cls.append('is-decide')
    else:
        cls.append('is-build')
    if not t['ready'] and not t['kiv'] and not t['test']:
        cls.append('is-blocked')

    if t['kiv']:
        state, st, kind, chip = 'parked', 'kiv', 'kiv', 'kiv'
    elif t['test']:
        state, st, kind, chip = 'needs a run on the phone', 'built', 'built', 'built'
    elif t['ready']:
        state, st, chip = 'ready', 'ready', t['type']
        kind = 'decide' if t['type'] == 'grilling' else 'build'
    else:
        state = 'waiting on ' + ', '.join('#' + b for b in t['blockedBy'])
        st, chip = 'blocked', t['type']
        kind = 'decide' if t['type'] == 'grilling' else 'build'

    det = ''
    if t['detail']:
        det = '<span class="det">' + E(t['detail']) + '</span>'
    label = STATUS_LABEL.get(chip) or TYPE_LABEL.get(chip, E(chip))
    return (
        '<li class="' + ' '.join(cls) + '" data-type="' + kind + '" data-state="' + st + '">'
        '<span class="num">' + E(t['num']) + '</span>'
        '<span class="body"><span class="tt">' + E(t['title']) + '</span>' + det + '</span>'
        '<span class="chip c-' + E(chip) + '">' + label + '</span>'
        '<span class="state">' + E(state) + '</span>'
        '</li>'
    )


def rank(t):
    """Ready first, then what owes a test run, then blocked, then parked. Parked is always last."""
    if t['kiv']:
        return 3
    if t['test']:
        return 1
    return 0 if t['ready'] else 2


panels = []
for m in d:
    ts = sorted(m['tickets'], key=lambda t: (rank(t), t['num']))
    rows = NL.join(ticket(t) for t in ts)
    blocked = m['open'] - m['ready'] - m['test'] - m['kiv']
    bar = ('<span class="bar"><i style="flex:' + str(m['ready'] + m['test']) + '"></i>'
           '<b style="flex:' + str(blocked + m['kiv']) + '"></b></span>')
    blocked_bit = ''
    if m['test']:
        blocked_bit += ('<span class="sep">&middot;</span><span class="k mint">'
                        + str(m['test']) + '</span> built')
    if blocked:
        blocked_bit += ('<span class="sep">&middot;</span><span class="k amber">'
                        + str(blocked) + '</span> blocked')
    if m['kiv']:
        blocked_bit += ('<span class="sep">&middot;</span><span class="k dim">'
                        + str(m['kiv']) + '</span> KIV')
    panels.append(
        '<section class="map">' + NL +
        '<header class="mh">' + NL +
        '<span class="kind">effort</span>' + NL +
        '<h2>' + E(m['slug']) + '</h2>' + NL +
        '<span class="counts"><span class="k">' + str(m['open']) + '</span> open'
        '<span class="sep">&middot;</span><span class="k mint">' + str(m['ready']) + '</span> ready'
        + blocked_bit +
        '<span class="sep">&middot;</span><span class="dim">of ' + str(m['total']) + ' tickets</span></span>' + NL +
        bar + NL + '</header>' + NL +
        '<ul class="tl">' + NL + rows + NL + '</ul>' + NL + '</section>'
    )

CSS = '''
:root{
  --ground:#000000; --panel:#05070C;
  --ink:#E4E9EF; --faint:#8E97A3; --ghost:#58606C;
  --rule:#1E2530; --rule-faint:#141A22;
  --mint:#57EFC6; --amber:#FFBA1F; --chrome:#FF5330;
  --mono:"IBM Plex Mono",ui-monospace,SFMono-Regular,Menlo,monospace;
  --sans:"IBM Plex Sans",system-ui,-apple-system,"Segoe UI",sans-serif;
}
*{box-sizing:border-box}
body{margin:0;background:var(--ground);color:var(--ink);font-family:var(--sans);
  font-size:15px;line-height:1.5;-webkit-font-smoothing:antialiased}
.wrap{max-width:1080px;margin:0 auto;padding:40px 20px 96px}
.stamp{font-family:var(--mono);font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--ghost);margin:0}
h1{font-family:var(--mono);font-weight:600;font-size:26px;letter-spacing:-.01em;margin:8px 0 0;text-wrap:balance}
.lede{color:var(--faint);max-width:62ch;margin:14px 0 0}
.lede b{color:var(--ink);font-weight:600}
.tot{display:flex;flex-wrap:wrap;gap:10px;margin:28px 0 0}
.stat{flex:1 1 150px;background:var(--panel);border:1px solid var(--rule);padding:14px 16px}
.stat .n{font-family:var(--mono);font-size:30px;font-weight:600;line-height:1;
  font-variant-numeric:tabular-nums;display:block}
.stat .l{display:block;margin-top:7px}
.n.mint{color:var(--mint)} .n.amber{color:var(--amber)}
.filters{display:flex;flex-wrap:wrap;gap:8px;margin:34px 0 20px;position:sticky;top:0;
  background:var(--ground);padding:12px 0;z-index:5;border-bottom:1px solid var(--rule-faint)}
.f{font-family:var(--mono);font-size:11px;letter-spacing:.1em;text-transform:uppercase;
  background:transparent;color:var(--faint);border:1px solid var(--rule);padding:7px 13px;cursor:pointer}
.f:hover{color:var(--ink);border-color:var(--ghost)}
.f:focus-visible{outline:2px solid var(--mint);outline-offset:2px}
.f[aria-pressed="true"]{color:var(--ground);background:var(--ink);border-color:var(--ink)}
.map{margin:0 0 10px;background:var(--panel);border:1px solid var(--rule)}
.mh{display:flex;align-items:center;gap:14px;flex-wrap:wrap;padding:13px 16px;
  border-bottom:1px solid var(--rule-faint)}
.mh h2{font-family:var(--mono);font-size:14px;font-weight:600;margin:0;letter-spacing:.02em}
.kind{font-family:var(--mono);font-size:9px;letter-spacing:.16em;text-transform:uppercase;
  color:var(--ghost);border:1px solid var(--rule-faint);padding:2px 6px;white-space:nowrap}
.counts{font-family:var(--mono);font-size:11px;color:var(--faint);letter-spacing:.04em}
.counts .k{color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums}
.counts .k.mint{color:var(--mint)} .counts .k.amber{color:var(--amber)}
.counts .sep{margin:0 7px;color:var(--ghost)}
.counts .dim{color:var(--ghost)}
.n.ghost{color:var(--ghost)}
.bar{display:flex;flex:1 1 110px;min-width:80px;height:3px;background:var(--rule-faint);overflow:hidden}
.bar i{background:var(--mint)} .bar b{background:var(--amber)}
.tl{list-style:none;margin:0;padding:0}
.t{display:grid;grid-template-columns:34px 1fr auto auto;align-items:baseline;gap:12px;
  padding:11px 16px 11px 13px;border-top:1px solid var(--rule-faint);border-left:3px solid transparent}
.t:first-child{border-top:0}
.t.is-decide{border-left-color:var(--amber)}
.t.is-build{border-left-color:var(--mint)}
.t.is-bug{border-left-color:var(--chrome)}
.t.is-blocked{border-left-color:var(--rule)}
/* Built, owing a run: mint like buildable work, but dashed - it is not the same job. */
.t.is-built{border-left-color:var(--mint);border-left-style:dashed}
/* Parked: present, readable, and visibly not asking for anything. */
.t.is-kiv{border-left-color:var(--rule-faint)}
.t.is-kiv .tt,.t.is-kiv .num{color:var(--ghost)}
.num{font-family:var(--mono);font-size:12px;color:var(--ghost);font-variant-numeric:tabular-nums}
.tt{display:block}
.det{display:block;font-family:var(--mono);font-size:11px;color:var(--ghost);margin-top:4px;line-height:1.45}
.chip{font-family:var(--mono);font-size:10px;letter-spacing:.1em;text-transform:uppercase;
  padding:2px 8px;border:1px solid var(--rule);color:var(--faint);white-space:nowrap}
.c-grilling{color:var(--amber);border-color:#4A3A12}
.c-task,.c-prototype{color:var(--mint);border-color:#17453A}
.c-bug{color:var(--chrome);border-color:#5A2317}
.c-built{color:var(--mint);border-color:#17453A;border-style:dashed}
.c-kiv{color:var(--ghost);border-color:var(--rule-faint)}
.state{font-family:var(--mono);font-size:11px;color:var(--ghost);white-space:nowrap}
.t:not(.is-blocked):not(.is-kiv) .state{color:var(--mint)}
.t.is-kiv .state{color:var(--ghost)}
.foot{margin-top:40px;color:var(--ghost);font-size:13px;max-width:64ch}
.foot code{font-family:var(--mono);color:var(--faint)}
@media (max-width:640px){
  .t{grid-template-columns:28px 1fr;row-gap:6px}
  .chip,.state{grid-column:2;justify-self:start}
}
@media (prefers-reduced-motion:reduce){*{transition:none !important}}
'''

JS = '''
const btns=[...document.querySelectorAll('.f')];
btns.forEach(b=>b.addEventListener('click',()=>{
  btns.forEach(x=>x.setAttribute('aria-pressed',String(x===b)));
  const f=b.dataset.f;
  document.querySelectorAll('.t').forEach(t=>{
    const ok = f==='all' || t.dataset.state===f || t.dataset.type===f;
    t.style.display = ok ? '' : 'none';
  });
  document.querySelectorAll('.map').forEach(m=>{
    const any=[...m.querySelectorAll('.t')].some(t=>t.style.display!=='none');
    m.style.display = any ? '' : 'none';
  });
}));
'''

doc = (
    '<title>Pending Work</title>' + NL +
    '<link rel="preconnect" href="https://fonts.googleapis.com">' + NL +
    '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>' + NL +
    '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
    'family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&display=swap">' + NL +
    '<style>' + CSS + '</style>' + NL +
    '<div class="wrap">' + NL +
    '<p class="stamp">LEGION &middot; pending work &middot; 20 Aug 2026</p>' + NL +
    '<h1>' + str(tot) + ' tickets open</h1>' + NL +
    '<p class="lede">Across ' + str(len(d)) + ' maps. <b>' + str(dec) + ' of them are decisions</b>, '
    'not code &mdash; they need an answer from you before anyone can build. '
    + str(blocked_n) + ' are waiting on another ticket first. <b>' + str(test_n) + ' are already '
    'built</b> and owe nothing but a run on the phone. ' + str(kiv_n) + ' are parked (KIV) and sit '
    'at the bottom.</p>' + NL +
    '<div class="tot">' + NL +
    '<div class="stat"><span class="n">' + str(tot) + '</span><span class="stamp l">open</span></div>' + NL +
    '<div class="stat"><span class="n mint">' + str(ready) + '</span><span class="stamp l">ready now</span></div>' + NL +
    '<div class="stat"><span class="n mint">' + str(test_n) + '</span><span class="stamp l">built</span></div>' + NL +
    '<div class="stat"><span class="n amber">' + str(blocked_n) + '</span><span class="stamp l">blocked</span></div>' + NL +
    '<div class="stat"><span class="n amber">' + str(dec) + '</span><span class="stamp l">your call</span></div>' + NL +
    '<div class="stat"><span class="n mint">' + str(build) + '</span><span class="stamp l">still to build</span></div>' + NL +
    '<div class="stat"><span class="n ghost">' + str(kiv_n) + '</span><span class="stamp l">KIV</span></div>' + NL +
    '</div>' + NL +
    '<div class="filters" role="group" aria-label="Filter tickets">' + NL +
    '<button class="f" data-f="all" aria-pressed="true">everything</button>' + NL +
    '<button class="f" data-f="ready" aria-pressed="false">ready now</button>' + NL +
    '<button class="f" data-f="blocked" aria-pressed="false">blocked</button>' + NL +
    '<button class="f" data-f="decide" aria-pressed="false">your call</button>' + NL +
    '<button class="f" data-f="build" aria-pressed="false">still to build</button>' + NL +
    '<button class="f" data-f="built" aria-pressed="false">built</button>' + NL +
    '<button class="f" data-f="kiv" aria-pressed="false">KIV</button>' + NL +
    '</div>' + NL +
    NL.join(panels) + NL +
    '<p class="foot">Built from the ticket frontmatter in <code>.scratch/*/issues/</code>. '
    'A map leaves this page when its last ticket resolves &mdash; <code>cyberdeck-ui</code>, '
    '<code>fleet-maintenance</code>, <code>mission-control</code>, <code>legion-shape</code>, '
    '<code>notes-lists-calendar</code> and <code>import-sync-duplication</code> already have.</p>' + NL +
    '</div>' + NL +
    '<script>' + JS + '</script>' + NL
)

open(os.path.join('docs', 'index.html'), 'w', encoding='utf-8').write(doc)
print('docs/index.html:', len(panels), 'maps,', tot, 'open tickets,', ready, 'ready')
