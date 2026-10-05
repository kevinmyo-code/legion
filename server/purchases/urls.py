from django.urls import path

from purchases.views import LastBoughtView, PurchaseDetailView, PurchaseListCreateView

urlpatterns = [
    path("", PurchaseListCreateView.as_view(), name="purchase-list-create"),
    path("last-bought", LastBoughtView.as_view(), name="purchase-last-bought"),
    path("<uuid:purchase_id>", PurchaseDetailView.as_view(), name="purchase-detail"),
]
