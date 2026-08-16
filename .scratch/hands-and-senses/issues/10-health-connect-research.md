# What does Health Connect actually expose, and on what terms?

Type: research
Status: open
Blocked by: -

## Question

Health Connect is Android's on-device health data broker: steps, sleep, heart rate, workouts from
any watch/app, no cloud, no key. Before [the scope ticket](11-health-connect-scope.md) can be
argued, surface the facts from Android's own docs (developer.android.com/health-and-fitness):

1. **Data types.** The record types relevant to LEGION: sleep sessions/stages, steps, heart rate,
   exercise sessions, active/total calories, weight. What granularity and provenance metadata
   (which app wrote the record) come with each?
2. **Permissions model.** Per-record-type read permissions, the consent UI, background-read
   permission (added in API 35 era - current rules), and the 30-day read window folklore: is
   "apps can only read records from the last 30 days" still true, under what conditions?
3. **Play Store / declaration requirements.** Health permissions trigger Play policy declarations
   for PUBLISHED apps - what applies to a sideloaded clone-and-run app? Any manifest-level gates?
4. **Min SDK and device reality.** Health Connect as platform component vs the separate APK on
   older Android; what does LEGION's current minSdk mean for it? Galaxy A25 (Kevin's device)
   ships which path?
5. **Freshness.** When a watch syncs through its vendor app into Health Connect, what latency is
   documented? Is "how did I sleep" answerable at 7am from last night's data in practice, per
   docs or the API's own sync semantics?
6. **Aggregation API.** Does the SDK aggregate (daily step totals, sleep duration) natively, or
   does LEGION sum raw records - and cite the API shape.

Write findings to `research/10-health-connect.md`, cite every claim to the owning URL, then append
the Answer here and set Status: resolved.
