"""The store-rep demo (Kevin, 2026-11-22): on /try, Alfred can play the rep of a made-up shop.

The visitor picks it from the page's chip or asks for it ("pretend you're a store rep"). The rep
finds products, recommends, suggests one add-on that goes with what they chose, answers "too
expensive" with a real cheaper option or the real bundle, reads an order back and places it only
on their yes in a later turn. Then the page shows an after-call record built by code from the
conversation's own record.

- `catalogue`: the made-up shop. Invented names, SKUs and prices; never a real brand.
- `scenario`: which scenario a visit plays, on its `DemoVisit` row.
- `tools`: the rep's tools, cascade only and recruiter only (`door_tools`).
- `guards`: what a rep's sentence may not claim (a price, stock or discount no tool backs).
- `prompt`: the rep's frame, in place of the guide's.
- `callback`: "let me get someone": a form, a strict cap, a Web Push to the engine's owner,
  behind `LEGION_DEMO_HANDOFF` (unset: off).
- `call_card`: the after-call record.
- `views`: the page's routes (`/api/demo/shop/...`).
"""
