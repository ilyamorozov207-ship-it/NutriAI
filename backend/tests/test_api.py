import json

from fastapi.testclient import TestClient

from app import main

client = TestClient(main.app)


def test_health_reports_missing_ai_key(monkeypatch):
    monkeypatch.setattr(main, "GEMINI_API_KEY", "")
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["ai_configured"] == "false"


def test_product_catalog_search_and_limit():
    response = client.get("/products", params={"q": "курица", "limit": 10})
    assert response.status_code == 200
    payload = response.json()
    assert payload["count"] >= 1
    assert all("kcal" in item and "protein" in item for item in payload["products"])
    limited = client.get("/products", params={"limit": 1})
    assert len(limited.json()["products"]) == 1


def test_photo_endpoint_rejects_non_image_before_calling_ai(monkeypatch):
    monkeypatch.setattr(main, "GEMINI_API_KEY", "")
    response = client.post("/analyze-food", files={"image": ("note.txt", b"not an image", "text/plain")})
    assert response.status_code == 415


def test_photo_endpoint_explains_missing_ai_key(monkeypatch):
    monkeypatch.setattr(main, "GEMINI_API_KEY", "")
    response = client.post("/analyze-food", files={"image": ("food.jpg", b"\xff\xd8\xffjpeg-bytes", "image/jpeg")})
    assert response.status_code == 503
    assert "GEMINI_API_KEY" in response.json()["detail"]


def test_recipe_suggestion_parses_ai_json(monkeypatch):
    expected = {"recipes": [{"name": "Рис с курицей", "servings": 1, "ingredients": [], "steps": ["Приготовить"], "calories_per_serving": 400, "protein_g": 30, "fat_g": 10, "carbs_g": 45}]}
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(expected, ensure_ascii=False))
    response = client.post("/recipes/suggest", json={"products": ["курица", "рис"]})
    assert response.status_code == 200
    assert response.json()["recipes"][0]["name"] == "Рис с курицей"


def test_week_menu_requires_seven_days(monkeypatch):
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps({"days": [{"day": 1}]}, ensure_ascii=False))
    response = client.post("/menu/week", json={"products": ["гречка"]})
    assert response.status_code == 502
    assert "неполное меню" in response.json()["detail"]


def test_week_menu_returns_seven_days(monkeypatch):
    expected = {"days": [{"day": i, "meals": []} for i in range(1, 8)], "shopping_list": []}
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(expected, ensure_ascii=False))
    response = client.post("/menu/week", json={"products": ["яйца", "помидоры"]})
    assert response.status_code == 200
    assert len(response.json()["days"]) == 7


def test_recipe_request_rejects_empty_products():
    response = client.post("/recipes/suggest", json={"products": []})
    assert response.status_code == 422


def test_gemini_key_is_sent_in_header_not_url(monkeypatch):
    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"candidates": [{"content": {"parts": [{"text": "{\"ok\": true}"}]}}]}

    class FakeClient:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json, headers):
            captured.update(url=url, headers=headers, payload=json)
            return FakeResponse()

    monkeypatch.setattr(main, "GEMINI_API_KEY", "test-secret-key")
    monkeypatch.setattr(main.httpx, "Client", FakeClient)

    result = main._gemini_text("test prompt")

    assert result == '{"ok": true}'
    assert "test-secret-key" not in captured["url"]
    assert captured["headers"]["x-goog-api-key"] == "test-secret-key"


def test_recipe_ai_json_must_contain_recipes_list(monkeypatch):
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps({"unexpected": []}))
    response = client.post("/recipes/suggest", json={"products": ["рис"]})
    assert response.status_code == 502
    assert "список рецептов" in response.json()["detail"]


def test_photo_endpoint_rejects_fake_image_signature(monkeypatch):
    monkeypatch.setattr(main, "GEMINI_API_KEY", "test-key")
    response = client.post("/analyze-food", files={"image": ("fake.jpg", b"not-really-an-image", "image/jpeg")})
    assert response.status_code == 415


def test_api_token_protects_routes_when_configured(monkeypatch):
    monkeypatch.setattr(main, "API_ACCESS_TOKEN", "expected-token")
    denied = client.get("/products")
    allowed = client.get("/products", headers={"x-client-token": "expected-token"})
    health = client.get("/health")
    assert denied.status_code == 401
    assert allowed.status_code == 200
    assert health.status_code == 200


def test_recipe_target_rejects_extremely_low_calorie_value():
    response = client.post("/recipes/suggest", json={"products": ["рис"], "calories_target": 800})
    assert response.status_code == 422


def test_analysis_totals_are_derived_from_ingredients(monkeypatch):
    payload = {
        "dish_name": "Тест", "ingredients": [{"name": "рис", "grams": 100, "calories": 130, "protein_g": 2.7, "fat_g": 0.3, "carbs_g": 28.2}],
        "total_calories": 9999, "total_protein_g": 999, "total_fat_g": 999, "total_carbs_g": 999
    }
    class FakeResponse:
        def raise_for_status(self): return None
        def json(self): return {"candidates": [{"content": {"parts": [{"text": json.dumps(payload)}]}}]}
    class FakeClient:
        def __init__(self, timeout): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return False
        async def post(self, *args, **kwargs): return FakeResponse()
    monkeypatch.setattr(main, "GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(main.httpx, "AsyncClient", FakeClient)
    response = client.post("/analyze-food", files={"image": ("food.jpg", b"\xff\xd8\xffjpeg-bytes", "image/jpeg")})
    assert response.status_code == 200
    assert response.json()["total_calories"] == 130


def test_recipe_endpoint_handles_non_object_ai_json(monkeypatch):
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(["not", "an", "object"]))
    response = client.post("/recipes/suggest", json={"products": ["рис"]})
    assert response.status_code == 502
    assert "неожиданном формате" in response.json()["detail"]


def test_week_menu_handles_non_object_ai_json(monkeypatch):
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(["not", "an", "object"]))
    response = client.post("/menu/week", json={"products": ["рис"]})
    assert response.status_code == 502
    assert "неожиданном формате" in response.json()["detail"]


def test_photo_mime_is_detected_from_file_signature(monkeypatch):
    captured = {}
    payload = {
        "dish_name": "Тест", "ingredients": [{"name": "рис", "grams": 100, "calories": 130, "protein_g": 2.7, "fat_g": 0.3, "carbs_g": 28.2}],
        "total_calories": 130, "total_protein_g": 2.7, "total_fat_g": 0.3, "total_carbs_g": 28.2
    }
    class FakeResponse:
        def raise_for_status(self): return None
        def json(self): return {"candidates": [{"content": {"parts": [{"text": json.dumps(payload)}]}}]}
    class FakeClient:
        def __init__(self, timeout): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return False
        async def post(self, url, json, headers):
            captured.update(url=url, payload=json, headers=headers)
            return FakeResponse()
    monkeypatch.setattr(main, "GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(main.httpx, "AsyncClient", FakeClient)
    response = client.post("/analyze-food", files={"image": ("photo.png", b"\xff\xd8\xffjpeg-bytes", "image/png")})
    assert response.status_code == 200
    assert captured["payload"]["contents"][0]["parts"][1]["inline_data"]["mime_type"] == "image/jpeg"


def test_production_mode_fails_closed_without_server_token(monkeypatch):
    monkeypatch.setattr(main, "NUTRIAI_ENV", "production")
    monkeypatch.setattr(main, "API_ACCESS_TOKEN", "")
    protected = client.get("/products")
    health = client.get("/health")
    assert protected.status_code == 503
    assert "API_ACCESS_TOKEN" in protected.json()["detail"]
    assert health.status_code == 200


def test_recipe_request_trims_and_deduplicates_products(monkeypatch):
    captured = {}
    # Return valid JSON while preserving the prompt for assertions.
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: (captured.update(prompt=prompt) or json.dumps({"recipes": [{"name": "Омлет", "ingredients": [], "steps": [], "calories_per_serving": 200}]})))
    response = client.post("/recipes/suggest", json={"products": [" яйцо ", "Яйцо", "помидор"]})
    assert response.status_code == 200
    assert '"яйцо", "помидор"' in captured["prompt"]


def test_recipe_request_rejects_blank_product_names():
    response = client.post("/recipes/suggest", json={"products": [" ", "\t"]})
    assert response.status_code == 422


def test_recipe_response_rejects_negative_calories(monkeypatch):
    payload = {"recipes": [{"name": "Неверный рецепт", "ingredients": [], "steps": [], "calories_per_serving": -100}]}
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(payload, ensure_ascii=False))
    response = client.post("/recipes/suggest", json={"products": ["рис"]})
    assert response.status_code == 502
    assert "calories_per_serving" in response.json()["detail"]


def test_week_menu_rejects_malformed_day(monkeypatch):
    payload = {"days": [{"day": i, "meals": []} for i in range(1, 7)] + [{"day": 7, "meals": "not-a-list"}], "shopping_list": []}
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(payload, ensure_ascii=False))
    response = client.post("/menu/week", json={"products": ["гречка"]})
    assert response.status_code == 502
    assert "некорректную структуру" in response.json()["detail"]


def test_week_menu_rejects_negative_nutrition(monkeypatch):
    payload = {"days": [{"day": i, "meals": [], "total_calories": 1900} for i in range(1, 8)], "shopping_list": []}
    payload["days"][2]["total_calories"] = -10
    monkeypatch.setattr(main, "_gemini_text", lambda prompt: json.dumps(payload, ensure_ascii=False))
    response = client.post("/menu/week", json={"products": ["гречка"]})
    assert response.status_code == 502
    assert "total_calories" in response.json()["detail"]
