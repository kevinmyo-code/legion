from django.urls import path

from grades.views import GradeDetailView, GradeListView, WhatINeedView

urlpatterns = [
    path("", GradeListView.as_view(), name="grades-list"),
    path("<int:course_id>", GradeDetailView.as_view(), name="grades-detail"),
    path("<int:course_id>/what-i-need", WhatINeedView.as_view(), name="grades-what-i-need"),
]
