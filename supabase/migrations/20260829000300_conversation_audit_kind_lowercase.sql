-- LEGION backend-erp: correct conversation_audit's kind check to the values actually stored.
-- Ticket: .scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md
--
-- 20260829000100 declared `check (kind in ('USER', 'COMPANION', 'TOOL_RESULT'))`, written from
-- ConversationAudit.kt's doc comment, which describes the three kinds in prose capitals. **The
-- column stores lowercase** - 'user', 'companion', 'tool_result' - which a single query against the
-- device would have shown and which the doc comment never claimed either way.
--
-- The first real upload attempt was rejected outright:
--   new row for relation "conversation_audit" violates check constraint
--   "conversation_audit_kind_check"
--
-- Nothing was written, and the phone reported that correctly rather than half-committing.
--
-- **Lowercase is the right direction to fix this**, not an uppercasing translation on the client.
-- The values are the app's own vocabulary; keeping them byte-identical on both sides means there is
-- no mapping layer to drift, and a mapping layer is exactly the sort of thing that would be correct
-- today and quietly wrong after someone adds a fourth kind.
--
-- Found by running it. The suite could not have caught this: the tests use fakes, and the constraint
-- only exists on the server.
--
-- UNAPPLIED as of this commit.
alter table public.conversation_audit
    drop constraint if exists conversation_audit_kind_check;

alter table public.conversation_audit
    add constraint conversation_audit_kind_check
    check (kind in ('user', 'companion', 'tool_result'));
