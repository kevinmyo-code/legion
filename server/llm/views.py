from __future__ import annotations

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.views import APIView

from llm.config import MODELS, model_config


class ModelConfigSerializer(serializers.Serializer):
    live = serializers.CharField(help_text=MODELS["live"][1])
    text = serializers.CharField(help_text=MODELS["text"][1])
    chat = serializers.CharField(help_text=MODELS["chat"][1])
    schema_generator = serializers.CharField(help_text=MODELS["schema_generator"][1])


class ModelConfigView(APIView):
    @extend_schema(
        operation_id="api_config_models_retrieve",
        tags=["config"],
        summary="The Gemini model ids clients should use",
        responses={
            200: OpenApiResponse(
                response=ModelConfigSerializer,
                description="Every key always present. A client that cannot reach this, or "
                "meets a key it does not know, uses its own bundled constant.",
            )
        },
    )
    def get(self, request):
        return Response(model_config(), headers={"Cache-Control": "private, max-age=300"})
