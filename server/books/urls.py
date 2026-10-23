from django.urls import path

from books.views import BookConvertView

urlpatterns = [
    path("convert", BookConvertView.as_view(), name="books-convert"),
]
