"""The one place a push leaves the engine.

`push_dispatch` takes a sender as an argument, so tests hand it a fake and
production hands it `webpush_sender(config)`. A sender is called with a
subscription and the payload dict and returns the push service's HTTP
status (201 is the normal success). It never raises for a delivery failure:
a refused push is an answer the dispatcher acts on (404/410 deletes the
subscription; anything else counts toward five strikes).
"""

from __future__ import annotations

import json
from collections.abc import Callable

from push.vapid import VapidConfig

Sender = Callable[[object, dict], int]


def webpush_sender(config: VapidConfig, *, timeout: float = 10.0) -> Sender:
    from pywebpush import WebPushException, webpush

    def send(subscription, payload: dict) -> int:
        try:
            response = webpush(
                subscription_info={
                    "endpoint": subscription.endpoint,
                    "keys": {"p256dh": subscription.p256dh, "auth": subscription.auth},
                },
                data=json.dumps(payload),
                vapid_private_key=config.private_key,
                vapid_claims={"sub": config.subject},
                timeout=timeout,
                ttl=60 * 60,
            )
        except WebPushException as exc:
            response = getattr(exc, "response", None)
            return getattr(response, "status_code", 0) or 0
        except Exception:  # noqa: BLE001 - a network fault is a failed delivery, said as 0
            return 0
        return getattr(response, "status_code", 201)

    return send
