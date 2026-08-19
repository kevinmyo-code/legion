"""Regenerates docs/pending.html - the progress wiki.

One page showing every OPEN ticket across every map, grouped by map, filterable by
ready / blocked / decision / buildable. Built from the same ticket frontmatter
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
            "blockedBy": re.findall(r"\d+", f.get("blockers", "")),
        })
    out = []
    for slug, m in maps.items():
        op = [t for t in m["tickets"] if t["status"] not in DONE]
        out.append({"slug": slug, "title": m["title"], "total": len(m["tickets"]),
                    "open": len(op), "ready": len([t for t in op if t["ready"]]),
                    "tickets": op})
    out.sort(key=lambda x: (-x["open"], x["slug"]))
    return out



# Reads the ticket frontmatter directly - no intermediate file, so the wiki can never
# describe a state the tickets are not actually in.
d = collect()
d = [m for m in d if m['open']]
tot = sum(m['open'] for m in d)
ready = sum(m['ready'] for m in d)
dec = sum(1 for m in d for t in m['tickets'] if t['type'] == 'grilling')
build = tot - dec
E = html.escape
NL = chr(10)
TYPE_LABEL = {'grilling': 'decide', 'task': 'build', 'bug': 'bug',
              'prototype': 'prototype', 'research': 'research'}


def ticket(t):
    cls = ['t']
    if t['type'] == 'bug':
        cls.append('is-bug')
    elif t['type'] == 'grilling':
        cls.append('is-decide')
    else:
        cls.append('is-build')
    if not t['ready']:
        cls.append('is-blocked')
    if t['ready']:
        state = 'ready'
    else:
        state = 'waiting on ' + ', '.join('#' + b for b in t['blockedBy'])
    det = ''
    if t['detail']:
        det = '<span class="det">' + E(t['detail']) + '</span>'
    kind = 'decide' if t['type'] == 'grilling' else 'build'
    st = 'ready' if t['ready'] else 'blocked'
    return (
        '<li class="' + ' '.join(cls) + '" data-type="' + kind + '" data-state="' + st + '">'
        '<span class="num">' + E(t['num']) + '</span>'
        '<span class="body"><span class="tt">' + E(t['title']) + '</span>' + det + '</span>'
        '<span class="chip c-' + E(t['type']) + '">' + TYPE_LABEL.get(t['type'], E(t['type'])) + '</span>'
        '<span class="state">' + E(state) + '</span>'
        '</li>'
    )


panels = []
for m in d:
    ts = sorted(m['tickets'], key=lambda t: (not t['ready'], t['num']))
    rows = NL.join(ticket(t) for t in ts)
    blocked = m['open'] - m['ready']
    bar = ('<span class="bar"><i style="flex:' + str(m['ready']) + '"></i>'
           '<b style="flex:' + str(blocked) + '"></b></span>')
    blocked_bit = ''
    if blocked:
        blocked_bit = ('<span class="sep">&middot;</span><span class="k amber">'
                       + str(blocked) + '</span> blocked')
    panels.append(
        '<section class="map">' + NL +
        '<header class="mh">' + NL +
        '<h2>' + E(m['slug']) + '</h2>' + NL +
        '<span class="counts"><span class="k">' + str(m['open']) + '</span> open'
        '<span class="sep">&middot;</span><span class="k mint">' + str(m['ready']) + '</span> ready'
        + blocked_bit +
        '<span class="sep">&middot;</span><span class="dim">' + str(m['total']) + ' total</span></span>' + NL +
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
.counts{font-family:var(--mono);font-size:11px;color:var(--faint);letter-spacing:.04em}
.counts .k{color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums}
.counts .k.mint{color:var(--mint)} .counts .k.amber{color:var(--amber)}
.counts .sep{margin:0 7px;color:var(--ghost)}
.counts .dim{color:var(--ghost)}
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
.num{font-family:var(--mono);font-size:12px;color:var(--ghost);font-variant-numeric:tabular-nums}
.tt{display:block}
.det{display:block;font-family:var(--mono);font-size:11px;color:var(--ghost);margin-top:4px;line-height:1.45}
.chip{font-family:var(--mono);font-size:10px;letter-spacing:.1em;text-transform:uppercase;
  padding:2px 8px;border:1px solid var(--rule);color:var(--faint);white-space:nowrap}
.c-grilling{color:var(--amber);border-color:#4A3A12}
.c-task,.c-prototype{color:var(--mint);border-color:#17453A}
.c-bug{color:var(--chrome);border-color:#5A2317}
.state{font-family:var(--mono);font-size:11px;color:var(--ghost);white-space:nowrap}
.t:not(.is-blocked) .state{color:var(--mint)}
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
    '<p class="stamp">LEGION &middot; pending work &middot; 19 Aug 2026</p>' + NL +
    '<h1>' + str(tot) + ' tickets open</h1>' + NL +
    '<p class="lede">Across ' + str(len(d)) + ' maps. <b>' + str(dec) + ' of them are decisions</b>, '
    'not code &mdash; they need an answer from you before anyone can build. '
    + str(tot - ready) + ' are waiting on another ticket first.</p>' + NL +
    '<div class="tot">' + NL +
    '<div class="stat"><span class="n">' + str(tot) + '</span><span class="stamp l">open</span></div>' + NL +
    '<div class="stat"><span class="n mint">' + str(ready) + '</span><span class="stamp l">ready now</span></div>' + NL +
    '<div class="stat"><span class="n amber">' + str(tot - ready) + '</span><span class="stamp l">blocked</span></div>' + NL +
    '<div class="stat"><span class="n amber">' + str(dec) + '</span><span class="stamp l">your call</span></div>' + NL +
    '<div class="stat"><span class="n mint">' + str(build) + '</span><span class="stamp l">buildable</span></div>' + NL +
    '</div>' + NL +
    '<div class="filters" role="group" aria-label="Filter tickets">' + NL +
    '<button class="f" data-f="all" aria-pressed="true">everything</button>' + NL +
    '<button class="f" data-f="ready" aria-pressed="false">ready now</button>' + NL +
    '<button class="f" data-f="blocked" aria-pressed="false">blocked</button>' + NL +
    '<button class="f" data-f="decide" aria-pressed="false">your call</button>' + NL +
    '<button class="f" data-f="build" aria-pressed="false">buildable</button>' + NL +
    '</div>' + NL +
    NL.join(panels) + NL +
    '<p class="foot">Built from the ticket frontmatter in <code>.scratch/*/issues/</code>. '
    'A map leaves this page when its last ticket resolves &mdash; <code>cyberdeck-ui</code>, '
    '<code>fleet-maintenance</code>, <code>mission-control</code>, <code>legion-shape</code>, '
    '<code>notes-lists-calendar</code> and <code>import-sync-duplication</code> already have.</p>' + NL +
    '</div>' + NL +
    '<script>' + JS + '</script>' + NL
)

open(os.path.join('docs', 'pending.html'), 'w', encoding='utf-8').write(doc)
print('docs/pending.html:', len(panels), 'maps,', tot, 'open tickets,', ready, 'ready')
