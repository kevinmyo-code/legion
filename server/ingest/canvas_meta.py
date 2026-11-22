"""What a Canvas task row's `structured_meta` says, read without the poller.

Kept apart from `ingest.canvas` (which makes outbound calls) so the read paths in a turn, the read
twin, the watches and the greeting, can import it without pulling the poller into a turn.
"""

from __future__ import annotations

DISCUSSION_TYPE = "discussion_topic"

# Said of a discussion whose first post Canvas has while its replies are still due.
FIRST_POST_IN = "The first post is in; the replies are still due."


def _discussion_row(meta: dict) -> bool:
    """A discussion's own row (its due date is the replies'), not its `first_post` sub-row."""
    if "sub_deadline" in meta or "parent_canvas_assignment_id" in meta:
        return False
    types = str(meta.get("submission_types") or "").split(",")
    return DISCUSSION_TYPE in (t.strip() for t in types)


def first_post_only(meta) -> bool:
    """A discussion's own row Canvas calls submitted: the first post is in, the replies due."""
    return isinstance(meta, dict) and meta.get("canvas_submitted") is True and _discussion_row(meta)


def handed_in(meta, *, past_due: bool = False) -> bool:
    """Whether a Canvas task row's `structured_meta` says the work is in, so it drops out of "due".

    `canvas_submitted` (`is_submitted`), a `submitted_at`, or excused. **Except a discussion's
    own row before it is `past_due`**: Canvas marks a discussion submitted at the
    FIRST post, but that row's due date is the replies' (`initial_post_deadline`), so until then
    only excused or his tick settles it. Past it, the replies can no longer be done and Canvas's
    word holds again (no overdue nag for posted work). Its `first_post` sub-row is the first post
    and always takes Canvas's word. The phone's twin is `DueReader.handedIn`.
    """
    if not isinstance(meta, dict):
        return False
    if meta.get("excused"):
        return True
    if not past_due and _discussion_row(meta):
        return False
    submitted = meta.get("submitted_at")
    return bool(meta.get("canvas_submitted") or (isinstance(submitted, str) and submitted.strip()))
