# What does Home Assistant's local API actually offer a phone voice client?

Type: research
Status: open
Blocked by: -

## Question

Settled decision 1: LEGION fronts Home Assistant rather than rebuilding device integrations. Before
[Home control scope](03-home-control-scope.md) can be argued, surface the facts from HA's own docs
(developers.home-assistant.io, home-assistant.io):

1. **Auth.** Long-lived access tokens: lifetime, revocation, scope granularity. Is there anything
   narrower than full-API access for a token? What does HA recommend for third-party clients?
2. **API surface.** REST vs WebSocket: which supports calling services (`light.turn_on`,
   `lock.lock`), reading entity states, and subscribing to state changes? What does a minimal
   "get states + call service" client need? Does the WebSocket API require keepalive that fights
   Android Doze?
3. **Discovery and reachability.** mDNS discovery on the LAN; what happens off-LAN - is Nabu Casa
   remote access the only sanctioned path, does it change auth, what does it cost? (A Kevin-paid
   subscription for Kevin's own hub is BYO, not a backend - but say what it costs.)
4. **The conversation/Assist layer.** HA has its own Assist API (intent handling, exposed
   entities). Could LEGION hand a raw utterance to HA's conversation endpoint instead of building
   service-call tools - and what would that lose (LEGION's own context, confirm turns)?
5. **Entity exposure model.** How does HA scope what a voice assistant may touch ("exposed
   entities")? Does that machinery work for a custom client, or is it Assist-only?
6. **Hardware floor.** Cheapest sanctioned ways to run HA today (Green, Yellow, Pi, container) and
   their prices, from HA's own store/docs.

Write findings to `research/02-ha-api.md`, cite every claim to the owning URL, then append the
Answer here and set Status: resolved.
