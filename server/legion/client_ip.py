"""The caller's address for per-address throttles, which a caller cannot choose (blind review
2026-11-17, finding 1).

DRF's default `get_ident` uses the whole `X-Forwarded-For` header when `NUM_PROXIES` is unset, and
that header starts with whatever the caller sent: every made-up value was a fresh throttle key.
Here the address is the last hop Google's front end recorded (it appends the peer that connected
to it, so a caller cannot forge that entry). When that peer is a Cloudflare edge, the address is
Cloudflare's own `CF-Connecting-IP`, trusted only then: a caller reaching the Cloud Run URL
directly cannot pass a forged one.

Cloudflare's ranges are its published list (cloudflare.com/ips, read 2026-11-17). A new range
makes those visitors share their edge's address (stricter, never looser) until it is added.
"""

from __future__ import annotations

import ipaddress

CLOUDFLARE_RANGES = tuple(
    ipaddress.ip_network(net)
    for net in (
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
        "2400:cb00::/32",
        "2606:4700::/32",
        "2803:f800::/32",
        "2405:b500::/32",
        "2405:8100::/32",
        "2a06:98c0::/29",
        "2c0f:f248::/32",
    )
)


def _ip(text: str | None):
    try:
        return ipaddress.ip_address((text or "").strip())
    except ValueError:
        return None


def from_cloudflare(address) -> bool:
    return address is not None and any(address in net for net in CLOUDFLARE_RANGES)


def client_ip(request) -> str:
    """The address a per-address throttle counts."""
    meta = request.META
    hops = [h.strip() for h in (meta.get("HTTP_X_FORWARDED_FOR") or "").split(",") if h.strip()]
    peer = _ip(hops[-1]) if hops else _ip(meta.get("REMOTE_ADDR"))
    if from_cloudflare(peer):
        forwarded = _ip(meta.get("HTTP_CF_CONNECTING_IP"))
        if forwarded is not None:
            return str(forwarded)
    if peer is not None:
        return str(peer)
    return meta.get("REMOTE_ADDR") or "unknown"


class ClientIpThrottleMixin:
    """Mixed into a DRF throttle: its per-address key is `client_ip`, never the raw header."""

    def get_ident(self, request):
        return client_ip(request)
