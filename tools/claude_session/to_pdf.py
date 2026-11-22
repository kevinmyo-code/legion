"""Turn an HTML or Markdown file in the task's work folder into a PDF beside it (2026-11-22).

    python to_pdf.py --task 42 --src garden-walk.html [--out garden-walk.pdf]

A task asked for a plan "as a PDF" (2026-11-22). It had read the web, so it had no shell
(`containment.py`), and with no PDF helper it left an HTML page and said "print it yourself".
This script, this folder's own copy, is the PDF path a tainted task may still run, alone, like
`to_xlsx.py`: both files sit directly in `work/task-<N>/`, the PDF is new (never an overwrite),
and it goes on the written list.

The page is the task's own text, and a task that read the web may have copied anything into it,
so the renderer is boxed in three ways:
- **Sanitised copy.** Scripts, frames, objects, forms, links, `<meta>` and `<base>` are dropped
  with their contents, event handlers and `href`s go, and an image is kept only as a `data:` URI.
  The copy is written to a fresh temp folder, never into the work folder.
- **A Content-Security-Policy first in the page**: `default-src 'none'`, inline styles and
  `data:` images and fonts only. Nothing on disk or on the network can be loaded into the PDF.
- **No network for the browser**: every host name resolves to nothing, the proxy is a dead port,
  a throwaway profile, extensions, sync and background fetches off, and a 90 s cap that stops the
  whole process tree.

The renderer is the laptop's own Edge (or Chrome), headless `--print-to-pdf`, with a fixed argv.
Markdown is turned into HTML with the `markdown` package when it is installed, else shown as
preformatted text. Prints one line in words.
"""
from __future__ import annotations

import argparse
import html
import shutil
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import containment as C  # noqa: E402
from common import Paths, is_under  # noqa: E402

MAX_SRC_BYTES = 5 * 1024 * 1024
RENDER_TIMEOUT_S = 90
SOURCES = (".html", ".htm", ".md", ".markdown")
CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:"

BROWSERS = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
)

# Dropped with everything inside them.
_DROP_WITH_CONTENT = frozenset({
    "script", "noscript", "iframe", "frame", "frameset", "object", "embed", "applet", "form",
    "template", "svg", "math", "video", "audio", "canvas", "portal", "fencedframe"})
# Dropped, contents kept (or void: nothing to keep).
_DROP_TAG = frozenset({"html", "head", "body", "meta", "link", "base", "input", "button",
                       "select", "option", "textarea", "source", "track", "picture", "param"})
_VOID = frozenset({"area", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
                   "source", "track", "wbr", "base"})
_KEEP_ATTRS = frozenset({"class", "id", "style", "colspan", "rowspan", "alt", "title", "width",
                         "height", "align", "valign", "lang", "dir", "start", "type", "scope"})


class _Sanitiser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.out: list[str] = []
        self.skip = 0  # depth inside a dropped-with-content element
        self.in_style = False  # CSS is kept as written (the CSP stops any url() in it loading)

    def handle_starttag(self, tag, attrs):
        if self.skip:
            if tag in _DROP_WITH_CONTENT and tag not in _VOID:
                self.skip += 1
            return
        if tag in _DROP_WITH_CONTENT:
            if tag not in _VOID:
                self.skip = 1
            return
        if tag in _DROP_TAG:
            return
        kept = []
        for name, value in attrs:
            name = name.lower()
            value = value or ""
            if name in _KEEP_ATTRS:
                kept.append((name, value))
            elif name == "src" and tag == "img" and value.strip().lower().startswith("data:image/"):
                kept.append((name, value))
        text = "".join(f' {n}="{html.escape(v, quote=True)}"' for n, v in kept)
        self.out.append(f"<{tag}{text}>")
        self.in_style = tag == "style"

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if self.skip:
            if tag in _DROP_WITH_CONTENT:
                self.skip -= 1
            return
        if tag in _DROP_WITH_CONTENT or tag in _DROP_TAG or tag in _VOID:
            return
        self.in_style = False
        self.out.append(f"</{tag}>")

    def handle_data(self, data):
        if self.skip:
            return
        if self.in_style:
            self.out.append(data.replace("<", "\\3c "))  # cannot close the element early
        else:
            self.out.append(html.escape(data, quote=False))

    def handle_entityref(self, name):
        if not self.skip:
            self.out.append(f"&{name};")

    def handle_charref(self, name):
        if not self.skip:
            self.out.append(f"&#{name};")

    # Comments, doctypes, processing instructions and CDATA are dropped.
    def handle_comment(self, data):
        return

    def handle_decl(self, decl):
        return

    def handle_pi(self, data):
        return

    def unknown_decl(self, data):
        return


def sanitise(page: str) -> str:
    """The page as a standalone document that loads nothing: CSP first, then the kept markup."""
    p = _Sanitiser()
    p.feed(page)
    p.close()
    return ("<!doctype html>\n<meta charset=\"utf-8\">\n"
            f"<meta http-equiv=\"Content-Security-Policy\" content=\"{CSP}\">\n"
            + "".join(p.out) + "\n")


def markdown_to_html(text: str) -> str:
    try:
        import markdown  # noqa: PLC0415 (optional dependency)
    except ImportError:
        return ("<pre style=\"white-space:pre-wrap;font-family:sans-serif\">"
                + html.escape(text) + "</pre>")
    return markdown.markdown(text, extensions=["tables", "sane_lists"])


def find_browser() -> str | None:
    for b in BROWSERS:
        if Path(b).is_file():
            return b
    return None


def browser_argv(exe: str, profile: Path, page: Path, out: Path) -> list[str]:
    return [exe, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
            "--disable-extensions", "--disable-sync", "--disable-background-networking",
            "--disable-component-update", "--disable-default-apps", "--no-pings",
            "--host-resolver-rules=MAP * ~NOTFOUND", "--proxy-server=127.0.0.1:9",
            "--proxy-bypass-list=<-loopback>", f"--user-data-dir={profile}",
            "--no-pdf-header-footer", f"--print-to-pdf={out}", page.as_uri()]


def edge_render(page: Path, out: Path, scratch: Path) -> None:
    """Prints [page] to [out] with the laptop's own headless browser. Raises on any failure."""
    exe = find_browser()
    if exe is None:
        raise RuntimeError("no Edge or Chrome on this laptop to print with")
    argv = browser_argv(exe, scratch / "profile", page, out)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    proc = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=flags)
    try:
        proc.wait(timeout=RENDER_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        from session import kill_tree  # noqa: PLC0415
        kill_tree(proc.pid)
        raise RuntimeError("the browser took too long to print") from None


def convert(paths: Paths, task: int, src: str, out: str | None, render=edge_render) -> Path:
    work = paths.task_work(task)
    s = (work / src) if not Path(src).is_absolute() else Path(src)
    o = Path(out) if out and Path(out).is_absolute() else work / (out or s.with_suffix(".pdf").name)
    for p in (s, o):
        if not is_under(p, work) or p.parent.resolve() != work.resolve():
            raise ValueError("both files must sit directly in the task's work folder")
    if s.suffix.lower() not in SOURCES or o.suffix.lower() != ".pdf":
        raise ValueError("the input must be .html, .htm or .md and the output a .pdf")
    if not s.is_file():
        raise ValueError("there is no such file in the work folder")
    if s.stat().st_size > MAX_SRC_BYTES:
        raise ValueError("the page is over 5 MB")
    if o.exists():
        raise ValueError("a PDF with that name is already there")
    text = s.read_text(encoding="utf-8", errors="replace")
    if s.suffix.lower() in (".md", ".markdown"):
        text = markdown_to_html(text)
    scratch = Path(tempfile.mkdtemp(prefix="legion-to-pdf-"))
    try:
        page = scratch / "page.html"
        page.write_text(sanitise(text), encoding="utf-8")
        render(page, o, scratch)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    if not o.is_file() or o.stat().st_size == 0:
        o.unlink(missing_ok=True)
        raise ValueError("the browser made no PDF")
    C.record_written(paths, str(o))
    return o


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="to_pdf.py")
    ap.add_argument("--task", type=int, required=True)
    ap.add_argument("--src", required=True)
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    try:
        o = convert(Paths(), a.task, a.src, a.out)
    except (ValueError, RuntimeError, OSError) as e:
        print(f"Not converted: {e}. The page itself is still in the work folder.")
        return 1
    print(f"Saved {o.name} in the work folder: {o}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
