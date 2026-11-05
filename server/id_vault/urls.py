from django.urls import path

from id_vault.views import VaultDetailView, VaultListCreateView, VaultRevealView

urlpatterns = [
    path("items", VaultListCreateView.as_view(), name="vault-items"),
    path("items/<uuid:item_id>", VaultDetailView.as_view(), name="vault-item"),
    path("items/<uuid:item_id>/reveal", VaultRevealView.as_view(), name="vault-item-reveal"),
]
