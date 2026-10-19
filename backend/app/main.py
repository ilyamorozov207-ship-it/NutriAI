import base64
import json
import os
import secrets
from typing import Any

import httpx
from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

app = FastAPI(title="NutriAI API", version="0.4.0")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.8-flash").strip()
API_ACCESS_TOKEN = os.getenv("API_ACCESS_TOKEN", "").strip()
NUTRIAI_ENV = os.getenv("NUTRIAI_ENV", "development").strip().lower()
MAX_IMAGE_BYTES = 12 * 1024 * 1024

class Ingredient(BaseModel):
    name: str
    grams: float = Field(ge=0)
    calories: float = Field(ge=0)
    protein_g: float = Field(ge=0)
    fat_g: float = Field(ge=0)
    carbs_g: float = Field(ge=0)
    confidence: float = Field(default=0.5, ge=0, le=1)
    notes: str = ""

class FoodAnalysis(BaseModel):
    dish_name: str
    ingredients: list[Ingredient]
    total_calories: float
    total_protein_g: float
    total_fat_g: float
    total_carbs_g: float
    estimate_range_calories: list[float] = Field(default_factory=list)
    uncertainty_note: str = "Оценка приблизительная; проверь массу и состав."
    follow_up_question: str | None = None

@app.middleware("http")
async def optional_api_token(request: Request, call_next):
    """Protect API routes when API_ACCESS_TOKEN is configured.

    Keep /health public for deployment checks. For any network-accessible
    deployment, configure a long random token and HTTPS.
    """
    if request.url.path == "/health":
        return await call_next(request)
    # Fail closed in production: a forgotten token must never expose a public API.
    if NUTRIAI_ENV == "production" and not API_ACCESS_TOKEN:
        return JSONResponse(status_code=503, content={"detail": "Сервер не настроен: задайте API_ACCESS_TOKEN."})
    if API_ACCESS_TOKEN:
        supplied = request.headers.get("x-client-token", "")
        if not secrets.compare_digest(supplied, API_ACCESS_TOKEN):
            return JSONResponse(status_code=401, content={"detail": "Нет доступа. Проверь токен подключения к серверу."})
    return await call_next(request)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "NutriAI API", "ai_configured": str(bool(GEMINI_API_KEY)).lower()}

@app.post("/analyze-food", response_model=FoodAnalysis)
async def analyze_food(image: UploadFile = File(...)) -> Any:
    if not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=415, detail="Загрузите изображение")
    # Read in bounded chunks so an oversized request cannot allocate an
    # unbounded byte array before the size check.
    buffer = bytearray()
    while True:
        chunk = await image.read(64 * 1024)
        if not chunk:
            break
        buffer.extend(chunk)
        if len(buffer) > MAX_IMAGE_BYTES:
            await image.close()
            raise HTTPException(status_code=413, detail="Изображение должно быть меньше 12 МБ")
    data = bytes(buffer)
    if not data:
        raise HTTPException(status_code=400, detail="Файл пустой")
    # The Android client uploads JPEG after compression. Reject obvious
    # non-image payloads even if a client lies about Content-Type.
    valid_signature = data.startswith(b"\xff\xd8\xff") or data.startswith(b"\x89PNG\r\n\x1a\n") or (data.startswith(b"RIFF") and data[8:12] == b"WEBP")
    if not valid_signature:
        raise HTTPException(status_code=415, detail="Файл не похож на JPEG, PNG или WebP изображение")
    if not GEMINI_API_KEY:
        raise HTTPException(status_code=503, detail="ИИ не настроен. Добавьте GEMINI_API_KEY в окружение сервера.")

    detected_mime = (
        "image/jpeg" if data.startswith(b"\xff\xd8\xff") else
        "image/png" if data.startswith(b"\x89PNG\r\n\x1a\n") else
        "image/webp"
    )
    prompt = """Проанализируй фотографию еды для дневника питания. Верни только валидный JSON без markdown в следующей структуре:
    {"dish_name":"...","ingredients":[{"name":"...","grams":100,"calories":150,"protein_g":5,"fat_g":3,"carbs_g":20,"confidence":0.6,"notes":"почему вес приблизительный"}],"total_calories":150,"total_protein_g":5,"total_fat_g":3,"total_carbs_g":20,"estimate_range_calories":[100,220],"uncertainty_note":"...","follow_up_question":null}
    Оцени только то, что можно обоснованно предположить по изображению. Не утверждай, что точно знаешь массу, состав, масло или соус, если это не видно. Учитывай, что масса и калории приблизительные. Для каждого ингредиента оцени КБЖУ для указанной массы. Диапазон калорийности должен отражать неопределённость. Если неясно, что за блюдо, укажи это и задай короткий уточняющий вопрос. Числа должны быть числами, не строками. Ответ на русском языке."""
    payload = {
        "contents": [{"parts": [
            {"text": prompt},
            {"inline_data": {"mime_type": detected_mime, "data": base64.b64encode(data).decode("ascii")}}
        ]}],
        "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json"}
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
    try:
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.post(url, json=payload, headers={"x-goog-api-key": GEMINI_API_KEY})
            response.raise_for_status()
            raw = response.json()["candidates"][0]["content"]["parts"][0]["text"]
            parsed = json.loads(raw)
            analysis = FoodAnalysis.model_validate(parsed)
            if not analysis.ingredients or len(analysis.ingredients) > 40:
                raise ValueError("invalid ingredient count")
            if any(not item.name.strip() or item.grams <= 0 or item.grams > 5000 for item in analysis.ingredients):
                raise ValueError("invalid ingredient values")
            # Treat ingredient estimates as the source of totals so inconsistent
            # model-generated summary numbers do not silently override them.
            totals = {
                "total_calories": round(sum(item.calories for item in analysis.ingredients), 1),
                "total_protein_g": round(sum(item.protein_g for item in analysis.ingredients), 1),
                "total_fat_g": round(sum(item.fat_g for item in analysis.ingredients), 1),
                "total_carbs_g": round(sum(item.carbs_g for item in analysis.ingredients), 1),
            }
            return analysis.model_copy(update=totals)
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        if status in (401, 403):
            raise HTTPException(status_code=502, detail="ИИ-сервис отклонил ключ или доступ. Проверьте настройки API.") from exc
        if status == 429:
            raise HTTPException(status_code=429, detail="Лимит бесплатного тарифа исчерпан. Попробуйте позже.") from exc
        raise HTTPException(status_code=502, detail=f"Ошибка ИИ-сервиса ({status}).") from exc
    except (KeyError, IndexError, json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail="ИИ вернул ответ в неожиданном формате. Попробуйте другое фото.") from exc
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail="Не удалось связаться с ИИ-сервисом.") from exc


# Базовый каталог нужен для поиска даже без обращения к ИИ.
# Значения ориентировочные на 100 г; для упакованных продуктов лучше использовать этикетку.
PRODUCTS = [
    {"name":"Куриная грудка, приготовленная","kcal":165,"protein":31.0,"fat":3.6,"carbs":0.0,"category":"Мясо"},
    {"name":"Куриная грудка, сырая","kcal":120,"protein":22.5,"fat":2.6,"carbs":0.0,"category":"Мясо"},
    {"name":"Яйцо куриное","kcal":143,"protein":12.6,"fat":9.5,"carbs":0.7,"category":"Яйца"},
    {"name":"Рис белый, сухой","kcal":365,"protein":7.1,"fat":0.7,"carbs":80.0,"category":"Крупы"},
    {"name":"Рис белый, варёный","kcal":130,"protein":2.7,"fat":0.3,"carbs":28.2,"category":"Крупы"},
    {"name":"Гречка, сухая","kcal":343,"protein":13.3,"fat":3.4,"carbs":71.5,"category":"Крупы"},
    {"name":"Гречка, варёная","kcal":110,"protein":3.6,"fat":1.1,"carbs":21.3,"category":"Крупы"},
    {"name":"Овсяные хлопья, сухие","kcal":366,"protein":12.3,"fat":6.1,"carbs":59.5,"category":"Крупы"},
    {"name":"Молоко 3,2%","kcal":60,"protein":3.0,"fat":3.2,"carbs":4.7,"category":"Молочные"},
    {"name":"Творог 5%","kcal":121,"protein":17.0,"fat":5.0,"carbs":1.8,"category":"Молочные"},
    {"name":"Йогурт натуральный","kcal":60,"protein":4.0,"fat":3.0,"carbs":5.0,"category":"Молочные"},
    {"name":"Банан","kcal":89,"protein":1.1,"fat":0.3,"carbs":22.8,"category":"Фрукты"},
    {"name":"Яблоко","kcal":52,"protein":0.3,"fat":0.2,"carbs":13.8,"category":"Фрукты"},
    {"name":"Помидор","kcal":18,"protein":0.9,"fat":0.2,"carbs":3.9,"category":"Овощи"},
    {"name":"Огурец","kcal":15,"protein":0.7,"fat":0.1,"carbs":3.6,"category":"Овощи"},
    {"name":"Картофель варёный","kcal":87,"protein":1.9,"fat":0.1,"carbs":20.1,"category":"Овощи"},
    {"name":"Хлеб пшеничный","kcal":265,"protein":8.9,"fat":3.2,"carbs":49.0,"category":"Хлеб"},
    {"name":"Масло подсолнечное","kcal":884,"protein":0.0,"fat":100.0,"carbs":0.0,"category":"Масла"},
    {"name":"Лосось приготовленный","kcal":206,"protein":22.1,"fat":12.4,"carbs":0.0,"category":"Рыба"},
    {"name":"Чечевица варёная","kcal":116,"protein":9.0,"fat":0.4,"carbs":20.1,"category":"Бобовые"},
]

class RecipeRequest(BaseModel):
    products: list[str] = Field(min_length=1, max_length=40)
    calories_target: int | None = Field(default=None, ge=1200, le=6000)
    preferences: str = Field(default="Недорогие, простые и сбалансированные блюда", max_length=500)
    servings: int = Field(default=1, ge=1, le=12)

    @field_validator("products")
    @classmethod
    def normalize_products(cls, values: list[str]) -> list[str]:
        cleaned = []
        seen = set()
        for value in values:
            if not isinstance(value, str):
                raise ValueError("Каждый продукт должен быть строкой.")
            name = value.strip()
            if not name:
                raise ValueError("Название продукта не может быть пустым.")
            if len(name) > 80:
                raise ValueError("Название продукта не должно превышать 80 символов.")
            key = name.casefold().replace("ё", "е")
            if key not in seen:
                seen.add(key)
                cleaned.append(name)
        if not cleaned:
            raise ValueError("Укажи хотя бы один продукт.")
        return cleaned

    @field_validator("preferences")
    @classmethod
    def normalize_preferences(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Предпочтения не могут быть пустыми.")
        return value


def _gemini_text(prompt: str) -> str:
    if not GEMINI_API_KEY:
        raise HTTPException(status_code=503, detail="ИИ не настроен. Для генерации рецептов добавьте GEMINI_API_KEY на сервере.")
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.35, "responseMimeType": "application/json"},
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
    try:
        with httpx.Client(timeout=60) as client:
            response = client.post(url, json=payload, headers={"x-goog-api-key": GEMINI_API_KEY})
            response.raise_for_status()
            return response.json()["candidates"][0]["content"]["parts"][0]["text"]
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 429:
            raise HTTPException(status_code=429, detail="Лимит бесплатного тарифа ИИ исчерпан. Попробуйте позже.") from exc
        if exc.response.status_code in (401, 403):
            raise HTTPException(status_code=502, detail="ИИ-сервис отклонил ключ или доступ. Проверьте API-ключ и доступную модель.") from exc
        raise HTTPException(status_code=502, detail=f"Ошибка ИИ-сервиса ({exc.response.status_code}).") from exc
    except (httpx.HTTPError, KeyError, IndexError) as exc:
        raise HTTPException(status_code=502, detail="Не удалось получить корректный ответ от ИИ.") from exc


@app.get("/products")
def search_products(q: str = "", limit: int = 20) -> dict[str, Any]:
    """Search the starter food catalog; nutrition values are per 100 g."""
    term = q.strip().casefold().replace("ё", "е")
    limit = max(1, min(limit, 50))
    aliases = {
        "курица": ("куриная грудка",), "курицу": ("куриная грудка",), "курицы": ("куриная грудка",),
        "рис": ("рис",), "гречка": ("греч",), "овсянка": ("овсян",),
        "молоко": ("молок",), "яйца": ("яйц",), "яйцо": ("яйц",),
        "помидоры": ("помидор",), "огурцы": ("огур",), "творог": ("творог",),
        "рыба": ("лосос",), "масло": ("масло",), "яблоки": ("яблок",), "бананы": ("банан",)
    }
    terms = aliases.get(term, (term,)) if term else ()
    matches = [item for item in PRODUCTS if not term or any(token in item["name"].casefold().replace("ё", "е") or token in item["category"].casefold().replace("ё", "е") for token in terms)]
    return {"count": len(matches), "products": matches[:limit], "note": "Значения ориентировочные на 100 г; проверяйте этикетку и способ приготовления."}


def _validate_recipe_payload(data: Any) -> dict[str, Any]:
    """Reject malformed model output before it reaches the Android UI."""
    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail="ИИ вернул ответ в неожиданном формате. Повтори запрос.")
    recipes = data.get("recipes")
    if not isinstance(recipes, list) or not recipes:
        raise HTTPException(status_code=502, detail="ИИ не вернул список рецептов. Повтори запрос.")
    valid = []
    for recipe in recipes[:3]:
        if not isinstance(recipe, dict) or not isinstance(recipe.get("name"), str) or not recipe["name"].strip():
            raise HTTPException(status_code=502, detail="ИИ вернул рецепт без корректного названия. Повтори запрос.")
        if not isinstance(recipe.get("ingredients", []), list) or not isinstance(recipe.get("steps", []), list):
            raise HTTPException(status_code=502, detail="ИИ вернул рецепт с некорректными ингредиентами или шагами.")
        for key in ("calories_per_serving", "protein_g", "fat_g", "carbs_g", "prep_minutes"):
            value = recipe.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0):
                raise HTTPException(status_code=502, detail=f"ИИ вернул некорректное значение {key}.")
        valid.append(recipe)
    data["recipes"] = valid
    return data


@app.post("/recipes/suggest")
def suggest_recipes(request: RecipeRequest) -> dict[str, Any]:
    products = [p.strip() for p in request.products if p.strip()]
    if not products:
        raise HTTPException(status_code=422, detail="Укажи хотя бы один продукт.")
    prompt = f'''Составь 3 недорогих, простых и сбалансированных рецепта на русском языке. По возможности используй эти продукты: {json.dumps(products, ensure_ascii=False)}. Цель пользователя: {f"около {request.calories_target} ккал в сутки" if request.calories_target is not None else "не подгоняй меню под заданное число калорий; предложи обычные сбалансированные блюда"}. Порций в каждом рецепте: {request.servings}. Предпочтения: {request.preferences}. Можно предложить несколько дополнительных ингредиентов, но явно пометь их как дополнительные. Верни JSON: {{"recipes":[{{"name":"...","servings":{request.servings},"prep_minutes":20,"ingredients":[{{"name":"...","grams":100,"optional":false}}],"steps":["..."],"calories_per_serving":350,"protein_g":25,"fat_g":10,"carbs_g":40,"used_selected_products":["..."]}}]}}. КБЖУ приблизительные. Только JSON.'''
    try:
        data = json.loads(_gemini_text(prompt))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=502, detail="ИИ вернул рецепт в неожиданном формате. Повтори запрос.") from exc
    data = _validate_recipe_payload(data)
    return {"recipes": data["recipes"], "note": "КБЖУ и время приготовления приблизительные; проверь состав и массу ингредиентов."}


@app.post("/menu/week")
def generate_week_menu(request: RecipeRequest) -> dict[str, Any]:
    products = [p.strip() for p in request.products if p.strip()]
    if not products:
        raise HTTPException(status_code=422, detail="Укажи хотя бы один продукт.")
    prompt = f'''Составь разнообразное меню на 7 дней на русском языке. Продукты, которые уже есть: {json.dumps(products, ensure_ascii=False)}. Целевая калорийность: {f"около {request.calories_target} ккал в день" if request.calories_target is not None else "не задана; не подгоняй меню под фиксированную калорийность"}. Предпочтения: {request.preferences}. Используй имеющиеся продукты по максимуму, но разрешай дополнительные продукты и явно перечисляй их. На каждый день укажи завтрак, обед, ужин и при необходимости перекус. Для каждого блюда дай название, ингредиенты с массой, примерные ккал и Б/Ж/У. Верни только JSON: {{"days":[{{"day":1,"meals":[{{"type":"Завтрак","name":"...","ingredients":[{{"name":"...","grams":100,"optional":false}}],"calories":400,"protein_g":20,"fat_g":12,"carbs_g":50}}],"total_calories":1900,"protein_g":100,"fat_g":60,"carbs_g":220,"additional_products":["..."]}}],"shopping_list":[{{"name":"...","total_grams":500}}]}}. Должно быть ровно 7 дней. КБЖУ приблизительные.'''
    try:
        data = json.loads(_gemini_text(prompt))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=502, detail="ИИ вернул меню в неожиданном формате. Повтори запрос.") from exc
    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail="ИИ вернул меню в неожиданном формате. Повтори запрос.")
    days = data.get("days")
    if not isinstance(days, list) or len(days) != 7:
        raise HTTPException(status_code=502, detail="ИИ вернул неполное меню или не ровно 7 дней. Повтори запрос.")
    for index, day in enumerate(days, start=1):
        if not isinstance(day, dict) or not isinstance(day.get("meals"), list):
            raise HTTPException(status_code=502, detail=f"ИИ вернул некорректную структуру для дня {index}.")
        for key in ("total_calories", "protein_g", "fat_g", "carbs_g"):
            value = day.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0):
                raise HTTPException(status_code=502, detail=f"ИИ вернул некорректное значение {key} для дня {index}.")
    shopping_list = data.get("shopping_list", [])
    if not isinstance(shopping_list, list):
        raise HTTPException(status_code=502, detail="ИИ вернул некорректный список покупок.")
    data["days"] = days
    data["note"] = "План ориентировочный. Проверь порции, пищевые ограничения и наличие продуктов перед использованием."
    return data
