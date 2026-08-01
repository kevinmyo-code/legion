# Run the 15-minute SAF probe on a real device

Type: task
Status: open
Blocked by: (none)

## Question

Nothing to decide. This is the manual work that converts the access model's central claim from
`traced` to `tested`, and it blocks nothing except confidence.

[Can SAF actually read a Google Drive folder?](01-saf-drive-folder-feasibility.md) resolved to
"build on SAF", but its crux - **does a picked tree's `listFiles()` include files added AFTER the
grant** - was answered by tracing four layers of framework and Drive bytecode, not by running
anything. `adb devices` was empty for the whole research session. The entire Drive-folder feature
rests on that one behavior.

`../research/01-saf-drive-folder-findings.md` section "The minimal experiment that settles #2"
contains paste-ready probe code and the exact steps. In outline:

1. Install the probe on the Oppo A17K. **Check its Android version first** - the provider only
   advertises tree support at `SDK_INT >= 30`, so a device below that proves nothing about the
   crux, only about the gate.
2. `ACTION_OPEN_DOCUMENT_TREE`, pick a Drive folder, `takePersistableUriPermission`.
3. `listFiles()` and record the count.
4. Upload a new PDF to that folder from another device or the web.
5. `listFiles()` again **without re-picking**. Record whether the new file appears, and how long it
   takes to show up.
6. Reboot the phone. Confirm the persisted grant still resolves and still enumerates.
7. Read bytes from one PDF via `openInputStream`. Confirm it works, and repeat with the device in
   airplane mode to observe the offline failure mode.

Record per-document metadata actually returned (id, size, last-modified, and whether anything
hash-like appears), since the ingested-file ledger's identity choice depends on it.

**Deliverable:** append the observed results to the findings file under a `## Device probe` heading,
tagged `tested`, and correct any `traced` claim the device contradicts. If the crux comes back NO,
say so loudly - that invalidates the access model and reopens the resolved research ticket.
