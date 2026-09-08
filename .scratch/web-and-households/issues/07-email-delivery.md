---
map: web-and-households
ticket: "07"
title: "Email delivery: invites by mail, address verification, password reset"
type: decision
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Email delivery

Nothing in tickets 03 or 05 needs mail: an invite is a code Kevin sends however he likes, and a
forgotten password is reset by an owner in admin. That is fine for a family of six and this ticket
can stay open. It stops being fine the first time a parent locks themself out at 9 pm.

## Options

| Provider | Free tier | Against |
|---|---|---|
| Resend | 3,000/month, a domain to verify | A vendor; dead-simple API |
| Gmail SMTP with an app password | Google's sending limits | Ties the engine to Kevin's personal account; app passwords are a legacy feature |
| Postmark / Brevo | Similar | Same as Resend, fewer reasons |

Cloud Run cannot send mail itself; any pick is an outbound HTTPS or SMTP call with a secret in
Secret Manager, `EMAIL_BACKEND` in settings, and one Django template per message.

## What deciding produces

`EMAIL_BACKEND` + provider secret, `POST /api/auth/password-reset` + `/confirm`, "email this
invite" on the household screen, and address verification on signup (a signed token, 24 h). Until
then `/login` shows "Forgot your password? Ask the household owner."
