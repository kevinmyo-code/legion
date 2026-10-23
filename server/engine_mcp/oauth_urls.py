"""The OAuth routes for `/mcp` (ai-upgrade ticket 03), mounted at the site root.

Under DOT's own namespace (`oauth2_provider`), because DOT reverses its routes
by those names to build the metadata documents. Only the routes LEGION uses:
no application-management pages, no OIDC, no device flow, no introspection.

- `/.well-known/oauth-authorization-server` (RFC 8414), at the root because
  the issuer is the site root;
- `/.well-known/oauth-protected-resource[/mcp]` (RFC 9728);
- `/oauth/authorize/`, `/oauth/token/`, `/oauth/revoke_token/`;
- `/oauth/register/` and `/oauth/register/<client_id>/` (RFC 7591/7592).
"""

from django.urls import path

from engine_mcp import oauth

app_name = "oauth2_provider"

urlpatterns = [
    path(
        ".well-known/oauth-authorization-server",
        oauth.LegionServerMetadataView.as_view(),
        name="oauth-server-metadata",
    ),
    path(
        ".well-known/oauth-protected-resource",
        oauth.LegionResourceMetadataView.as_view(),
        name="oauth-resource-metadata",
    ),
    path(
        ".well-known/oauth-protected-resource/<path:resource_path>",
        oauth.LegionResourceMetadataView.as_view(),
        name="oauth-resource-metadata-path",
    ),
    path("oauth/authorize/", oauth.LegionAuthorizationView.as_view(), name="authorize"),
    path("oauth/token/", oauth.LegionTokenView.as_view(), name="token"),
    path("oauth/revoke_token/", oauth.LegionRevokeTokenView.as_view(), name="revoke-token"),
    path("oauth/register/", oauth.LegionDCRView.as_view(), name="dcr-register"),
    path(
        "oauth/register/<str:client_id>/",
        oauth.LegionDCRManagementView.as_view(),
        name="dcr-register-management",
    ),
]
