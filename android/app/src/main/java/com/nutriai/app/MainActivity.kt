package com.nutriai.app

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.nutriai.app.data.Meal
import com.nutriai.app.data.NutriDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val Ink = Color(0xFF101216)
private val Panel = Color(0xFF1A1D23)
private val Accent = Color(0xFF8CC8FF)
private val Muted = Color(0xFF9DA5B1)
private const val PREFS = "nutriai_settings"
private const val API_URL_KEY = "api_base_url"
private const val API_TOKEN_KEY = "api_access_token"
private const val DEFAULT_API_URL = "http://10.0.2.2:8000"
private const val PROFILE_KEY = "profile_json"
private const val WEIGHTS_KEY = "weight_history_json"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NutriTheme { NutriHome() } }
    }
}

@Composable
private fun NutriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Ink, background = Ink, surface = Panel,
            onBackground = Color(0xFFF4F6F8), onSurface = Color(0xFFF4F6F8), secondary = Color(0xFFB7E3D0)
        ), content = content
    )
}

@Composable
private fun NutriHome() {
    val context = LocalContext.current
    val db = remember { NutriDatabase.get(context) }
    val meals by db.mealDao().observeMeals().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var showAdd by remember { mutableStateOf(false) }
    var editingMeal by remember { mutableStateOf<Meal?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecipes by remember { mutableStateOf(false) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var apiBaseUrl by remember { mutableStateOf(prefs.getString(API_URL_KEY, DEFAULT_API_URL) ?: DEFAULT_API_URL) }
    var apiToken by remember { mutableStateOf(prefs.getString(API_TOKEN_KEY, "") ?: "") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("Выбери фото еды для анализа. Для физического телефона укажи адрес компьютера с запущенным сервером в настройках.") }
    var pendingAnalysis by remember { mutableStateOf<FoodAnalysis?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var showWeight by remember { mutableStateOf(false) }
    var showBackupInfo by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(loadProfile(prefs)) }
    var weights by remember { mutableStateOf(loadWeights(prefs)) }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val allMeals = withContext(Dispatchers.IO) { db.mealDao().getAllMeals() }
                val payload = buildBackupJson(allMeals, profile, weights)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toString(2).toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Не удалось открыть файл для записи.")
                }
                info = "Резервная копия сохранена."
            } catch (e: Exception) { info = "Ошибка экспорта: ${e.message ?: "неизвестная ошибка"}" }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> JSONObject(input.readBytesLimited(25 * 1024 * 1024).toString(Charsets.UTF_8)) }
                        ?: throw IOException("Не удалось прочитать файл.")
                }
                val restoredMeals = parseBackupMeals(payload)
                val restoredProfile = parseBackupProfile(payload.optJSONObject("profile"))
                val restoredWeights = parseBackupWeights(payload.optJSONArray("weights"))
                withContext(Dispatchers.IO) { db.mealDao().replaceAll(restoredMeals) }
                profile = restoredProfile
                prefs.edit().putString(PROFILE_KEY, profile.toJson().toString()).apply()
                weights = restoredWeights
                prefs.edit().putString(WEIGHTS_KEY, weightsToJson(weights).toString()).apply()
                info = "Восстановлено записей питания: ${restoredMeals.size}; измерений веса: ${restoredWeights.size}."
            } catch (e: Exception) { info = "Не удалось восстановить копию: ${e.message ?: "неверный файл"}" }
        }
    }

    fun analyzeSelectedPhoto(uri: Uri) {
        photoUri = uri
        isAnalyzing = true
        info = "Отправляю фото на анализ…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { analyzeFood(context, uri, apiBaseUrl, apiToken) }
                pendingAnalysis = result
                info = "Анализ готов. Проверь продукты и массу перед сохранением."
            } catch (e: Exception) {
                info = e.message ?: "Не удалось проанализировать фото. Проверь интернет и адрес сервера."
            } finally {
                isAnalyzing = false
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyzeSelectedPhoto(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = photoUri
        if (saved && uri != null) analyzeSelectedPhoto(uri)
        else if (!saved) info = "Фото не было сделано."
    }

    val todayMeals = meals.filter { isToday(it.createdAt) }
    val calories = todayMeals.sumOf { it.calories }
    val protein = todayMeals.sumOf { it.protein }
    val fat = todayMeals.sumOf { it.fat }
    val carbs = todayMeals.sumOf { it.carbs }

    Scaffold(containerColor = Ink, floatingActionButton = {
        FloatingActionButton(onClick = { editingMeal = null; showAdd = true }, containerColor = Accent, contentColor = Ink) {
            Icon(Icons.Default.Add, contentDescription = "Добавить продукт")
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 100.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("NutriAI", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Твой дневник питания", color = Muted, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Restaurant, contentDescription = null, tint = Accent)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки сервера", tint = Muted)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(26.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("СЕГОДНЯ", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$calories", fontSize = 42.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("ккал", color = Muted, modifier = Modifier.padding(bottom = 7.dp))
                        }
                        val calorieTarget = profile.calorieTarget()
                        if (profile.age in 1..17) {
                            Text("Автоматическая цель калорий отключена для пользователей младше 18 лет", color = Muted, fontSize = 12.sp)
                        } else {
                            Text("из $calorieTarget ккал · ${if (profile.isComplete()) "твоя цель" else "предварительная цель"}", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { (calories / calorieTarget.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Accent, trackColor = Color(0xFF303640))
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroCard("Белки", "${protein.pretty()} г", Modifier.weight(1f))
                            MacroCard("Жиры", "${fat.pretty()} г", Modifier.weight(1f))
                            MacroCard("Углеводы", "${carbs.pretty()} г", Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        try {
                            val file = File(context.cacheDir, "nutriai_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            photoUri = uri
                            cameraLauncher.launch(uri)
                        } catch (e: Exception) {
                            info = "Не удалось открыть камеру: ${e.message ?: "неизвестная ошибка"}"
                        }
                    }, enabled = !isAnalyzing, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Камера")
                    }
                    OutlinedButton(onClick = { photoPicker.launch("image/*") }, enabled = !isAnalyzing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Галерея") }
                }
                OutlinedButton(onClick = { editingMeal = null; showAdd = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Ввести продукт вручную") }
                OutlinedButton(onClick = { showRecipes = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Рецепты и меню на неделю") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showProfile = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Person, null); Spacer(Modifier.width(5.dp)); Text("Профиль") }
                    OutlinedButton(onClick = { showWeight = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.MonitorWeight, null); Spacer(Modifier.width(5.dp)); Text("Вес") }
                }
                OutlinedButton(onClick = { showBackupInfo = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Резервная копия") }
                if (isAnalyzing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                }
            }
            item { Text(info, color = Muted, fontSize = 12.sp) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Дневник", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("${todayMeals.size} записей", color = Muted, fontSize = 12.sp)
                }
            }
            if (todayMeals.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Пока пусто", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Добавь первый продукт — и дневная статистика начнёт заполняться.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(todayMeals, key = { it.id }) { meal ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(meal.name, fontWeight = FontWeight.SemiBold)
                                Text("${meal.grams} г · Б ${meal.protein.pretty()} · Ж ${meal.fat.pretty()} · У ${meal.carbs.pretty()}", color = Muted, fontSize = 12.sp)
                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(meal.createdAt)), color = Muted, fontSize = 11.sp)
                            }
                            Text("${meal.calories} ккал", fontWeight = FontWeight.Bold, color = Accent)
                            IconButton(onClick = { editingMeal = meal; showAdd = true }) { Icon(Icons.Default.Edit, contentDescription = "Редактировать запись", tint = Accent) }
                            IconButton(onClick = { scope.launch { db.mealDao().deleteById(meal.id) } }) { Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Muted) }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddMealDialog(
        initial = editingMeal,
        onDismiss = { showAdd = false; editingMeal = null },
        onSave = { meal -> scope.launch {
            if (editingMeal == null) db.mealDao().insert(meal) else db.mealDao().update(meal)
            showAdd = false
            editingMeal = null
            info = if (meal.id == 0L) "Приём пищи сохранён в дневнике." else "Запись питания обновлена."
        } }
    )

    if (showSettings) ServerSettingsDialog(
        initialUrl = apiBaseUrl,
        initialToken = apiToken,
        onDismiss = { showSettings = false },
        onSave = { newUrl, newToken ->
            apiBaseUrl = newUrl.trim().trimEnd('/')
            apiToken = newToken.trim()
            prefs.edit().putString(API_URL_KEY, apiBaseUrl).putString(API_TOKEN_KEY, apiToken).apply()
            showSettings = false
            info = "Адрес сервера сохранён: $apiBaseUrl"
        }
    )

    if (showRecipes) RecipeMenuDialog(
        apiBaseUrl = apiBaseUrl,
        apiToken = apiToken,
        defaultTarget = if (profile.age >= 18) profile.calorieTarget() else null,
        isMinor = profile.age in 1..17,
        onDismiss = { showRecipes = false }
    )

    if (showProfile) ProfileDialog(
        initial = profile,
        onDismiss = { showProfile = false },
        onSave = { updated ->
            profile = updated
            prefs.edit().putString(PROFILE_KEY, updated.toJson().toString()).apply()
            showProfile = false
            info = if (updated.age < 18) "Профиль сохранён. Для возраста младше 18 лет автоматическая цель калорий отключена." else "Профиль сохранён. Дневная цель: ${updated.calorieTarget()} ккал."
        }
    )

    if (showWeight) WeightDialog(
        entries = weights,
        currentWeight = weights.maxByOrNull { it.timestamp }?.weightKg ?: profile.weightKg,
        onDismiss = { showWeight = false },
        onSave = { weight ->
            val updated = (weights + WeightEntry(System.currentTimeMillis(), weight)).sortedBy { it.timestamp }
            weights = updated
            prefs.edit().putString(WEIGHTS_KEY, weightsToJson(updated).toString()).apply()
            showWeight = false
            info = "Вес сохранён. Дневная цель не меняется автоматически."
        },
        onDelete = { timestamp ->
            weights = weights.filterNot { it.timestamp == timestamp }
            prefs.edit().putString(WEIGHTS_KEY, weightsToJson(weights).toString()).apply()
        }
    )

    if (showBackupInfo) AlertDialog(
        onDismissRequest = { showBackupInfo = false },
        title = { Text("Резервная копия") },
        text = { Text("Экспорт сохранит дневник питания, профиль и историю веса в JSON-файл. Восстановление заменит текущий дневник данными из выбранной копии.") },
        confirmButton = { TextButton(onClick = { showBackupInfo = false; exportBackup.launch("nutriai_backup.json") }) { Text("Экспортировать") } },
        dismissButton = { TextButton(onClick = { showBackupInfo = false; importBackup.launch(arrayOf("application/json", "text/*")) }) { Text("Восстановить") } }
    )

    pendingAnalysis?.let { result ->
        AnalysisReviewDialog(
            analysis = result,
            onDismiss = { pendingAnalysis = null },
            onSave = { meal ->
                scope.launch {
                    db.mealDao().insert(meal)
                    pendingAnalysis = null
                    info = "Приём пищи сохранён в дневнике."
                }
            }
        )
    }
}

@Composable
private fun RecipeMenuDialog(apiBaseUrl: String, apiToken: String, defaultTarget: Int?, isMinor: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf("") }
    var target by remember(defaultTarget) { mutableStateOf(defaultTarget?.toString() ?: "") }
    var preferences by remember { mutableStateOf("Недорого, просто, сбалансированно") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun request(path: String) {
        val list = products.split(',', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }
        val calories = if (isMinor) null else target.toIntOrNull()
        if (list.isEmpty()) { error = "Введи хотя бы один продукт через запятую."; return }
        if (!isMinor && target.isNotBlank() && (calories == null || calories !in 1200..6000)) { error = "Цель должна быть от 1200 до 6000 ккал либо оставь поле пустым."; return }
        loading = true
        error = null
        result = ""
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { postJson("${apiBaseUrl.trimEnd('/')}$path", JSONObject().apply {
                    put("products", JSONArray(list))
                    if (calories != null) put("calories_target", calories)
                    put("preferences", preferences)
                    put("servings", 1)
                }, apiToken) }
                result = formatRecipeResult(json, path == "/menu/week")
            } catch (e: Exception) {
                error = e.message ?: "Не удалось получить ответ. Проверь интернет, адрес сервера и настройки ИИ."
            } finally { loading = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Рецепты и недельное меню") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Перечисли продукты, которые у тебя есть. Разделяй их запятыми.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(products, { products = it; error = null }, label = { Text("Например: курица, рис, яйца") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (isMinor) {
                    Text("Для пользователей младше 18 лет меню не будет оптимизироваться под автоматически рассчитанную цель калорий.", color = Muted, fontSize = 11.sp)
                } else {
                    OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text("Цель, ккал/день (необязательно)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                OutlinedTextField(preferences, { preferences = it }, label = { Text("Предпочтения") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { request("/recipes/suggest") }, enabled = !loading, modifier = Modifier.weight(1f)) { Text("Рецепты") }
                    OutlinedButton(onClick = { request("/menu/week") }, enabled = !loading, modifier = Modifier.weight(1f)) { Text("Меню на 7 дней") }
                }
                OutlinedButton(onClick = {
                    loading = true; error = null
                    scope.launch {
                        try {
                            val catalog = withContext(Dispatchers.IO) { getJson("${apiBaseUrl.trimEnd('/')}/products", apiToken) }
                            val arr = catalog.optJSONArray("products") ?: JSONArray()
                            result = buildString {
                                append("КАТАЛОГ ПРОДУКТОВ (на 100 г)\n\n")
                                for (i in 0 until arr.length()) {
                                    val item = arr.getJSONObject(i)
                                    append("• ${item.optString("name")} — ${item.optInt("kcal")} ккал; Б ${item.optDouble("protein").pretty()} г, Ж ${item.optDouble("fat").pretty()} г, У ${item.optDouble("carbs").pretty()} г\n")
                                }
                                append("\nНайди название и добавь его в поле продуктов выше.")
                            }
                        } catch (e: Exception) { error = e.message ?: "Не удалось загрузить каталог." }
                        finally { loading = false }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Показать каталог продуктов") }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (result.isNotBlank()) {
                    HorizontalDivider()
                    Text(result, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Text("КБЖУ рецептов приблизительные. Генерация требует подключения к интернету и настроенного бесплатного API с доступным лимитом.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private fun postJson(url: String, json: JSONObject, apiToken: String = ""): JSONObject {
    val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    val requestBuilder = Request.Builder().url(url).post(body)
    if (apiToken.isNotBlank()) requestBuilder.header("x-client-token", apiToken)
    val request = requestBuilder.build()
    val client = OkHttpClient.Builder().callTimeout(75, TimeUnit.SECONDS).build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = try { JSONObject(raw).optString("detail", "") } catch (_: Exception) { "" }
            throw IOException(if (detail.isNotBlank()) detail else "Ошибка сервера: HTTP ${response.code}")
        }
        return JSONObject(raw)
    }
}

private fun getJson(url: String, apiToken: String = ""): JSONObject {
    val requestBuilder = Request.Builder().url(url).get()
    if (apiToken.isNotBlank()) requestBuilder.header("x-client-token", apiToken)
    val request = requestBuilder.build()
    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("Ошибка сервера: HTTP ${response.code}")
        return JSONObject(raw)
    }
}

private fun formatRecipeResult(json: JSONObject, isWeek: Boolean): String = buildString {
    if (!isWeek) {
        append("ПОДХОДЯЩИЕ РЕЦЕПТЫ\n\n")
        val recipes = json.optJSONArray("recipes") ?: JSONArray()
        for (i in 0 until recipes.length()) {
            val r = recipes.optJSONObject(i) ?: continue
            append("${i + 1}. ${r.optString("name", "Рецепт")}\n")
            append("Время: ${r.optInt("prep_minutes", 0)} мин · порций: ${r.optInt("servings", 1)}\n")
            append("КБЖУ на порцию: ${r.optInt("calories_per_serving")} ккал · Б ${r.optDouble("protein_g").pretty()} г · Ж ${r.optDouble("fat_g").pretty()} г · У ${r.optDouble("carbs_g").pretty()} г\n")
            val ingredients = r.optJSONArray("ingredients") ?: JSONArray()
            append("Ингредиенты: ")
            append((0 until ingredients.length()).mapNotNull { j -> ingredients.optJSONObject(j)?.let { "${it.optString("name")} ${it.optInt("grams")} г${if (it.optBoolean("optional")) " (дополнительно)" else ""}" } }.joinToString(", "))
            append("\n")
            val steps = r.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until steps.length()) append("${j + 1}) ${steps.optString(j)}\n")
            append("\n")
        }
    } else {
        append("МЕНЮ НА 7 ДНЕЙ\n\n")
        val days = json.optJSONArray("days") ?: JSONArray()
        for (i in 0 until minOf(7, days.length())) {
            val day = days.optJSONObject(i) ?: continue
            append("ДЕНЬ ${day.optInt("day", i + 1)} · ${day.optInt("total_calories")} ккал\n")
            val meals = day.optJSONArray("meals") ?: JSONArray()
            for (j in 0 until meals.length()) {
                val meal = meals.optJSONObject(j) ?: continue
                append("• ${meal.optString("type")}: ${meal.optString("name")} — ${meal.optInt("calories")} ккал (Б ${meal.optDouble("protein_g").pretty()}, Ж ${meal.optDouble("fat_g").pretty()}, У ${meal.optDouble("carbs_g").pretty()})\n")
            }
            val extras = day.optJSONArray("additional_products")
            if (extras != null && extras.length() > 0) append("Докупить: ${(0 until extras.length()).joinToString(", ") { extras.optString(it) }}\n")
            append("\n")
        }
        val shopping = json.optJSONArray("shopping_list")
        if (shopping != null && shopping.length() > 0) {
            append("СПИСОК ПОКУПОК\n")
            for (i in 0 until shopping.length()) {
                val item = shopping.optJSONObject(i) ?: continue
                append("• ${item.optString("name")} — ${item.optInt("total_grams")} г\n")
            }
        }
    }
    json.optString("note").takeIf { it.isNotBlank() }?.let { append("\nПримечание: $it") }
}

private data class UserProfile(
    val age: Int = 0,
    val heightCm: Int = 0,
    val weightKg: Double = 0.0,
    val sex: String = "male",
    val activity: String = "light",
    val goal: String = "maintain"
) {
    fun isComplete() = age in 1..100 && heightCm in 100..240 && weightKg in 25.0..350.0

    fun calorieTarget(): Int {
        // Never expose an adult calorie estimate as a target for a minor.
        if (age in 1..17) return 0
        if (!isComplete()) return 2000
        // Mifflin-St Jeor is an estimate, not a medical prescription.
        val bmr = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + if (sex == "male") 5.0 else -161.0
        val multiplier = when (activity) {
            "moderate" -> 1.55
            "high" -> 1.725
            else -> 1.375
        }
        val maintenance = bmr * multiplier
        val estimate = when (goal) {
            "lose" -> maintenance - 250.0
            "gain" -> maintenance + 200.0
            else -> maintenance
        }
        return estimate.roundToInt().coerceIn(1200, 5000)
    }

    fun toJson() = JSONObject().apply {
        put("age", age); put("height_cm", heightCm); put("weight_kg", weightKg)
        put("sex", sex); put("activity", activity); put("goal", goal)
    }
}

private data class WeightEntry(val timestamp: Long, val weightKg: Double)

private fun loadProfile(prefs: android.content.SharedPreferences): UserProfile {
    return try { parseBackupProfile(JSONObject(prefs.getString(PROFILE_KEY, "{}") ?: "{}")) }
    catch (_: Exception) { UserProfile() }
}

private fun parseBackupProfile(json: JSONObject?): UserProfile {
    if (json == null) return UserProfile()
    return UserProfile(
        age = json.optInt("age", 0).coerceIn(0, 100),
        heightCm = json.optInt("height_cm", 0).coerceIn(0, 250),
        weightKg = json.optDouble("weight_kg", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 400.0) ?: 0.0,
        sex = json.optString("sex", "male").takeIf { it in setOf("male", "female") } ?: "male",
        activity = json.optString("activity", "light").takeIf { it in setOf("light", "moderate", "high") } ?: "light",
        goal = json.optString("goal", "maintain").takeIf { it in setOf("lose", "maintain", "gain") } ?: "maintain"
    )
}

private fun loadWeights(prefs: android.content.SharedPreferences): List<WeightEntry> = try {
    val arr = JSONArray(prefs.getString(WEIGHTS_KEY, "[]") ?: "[]")
    parseBackupWeights(arr)
} catch (_: Exception) { emptyList() }

private fun weightsToJson(weights: List<WeightEntry>) = JSONArray().apply {
    weights.forEach { put(JSONObject().apply { put("timestamp", it.timestamp); put("weight_kg", it.weightKg) }) }
}

private fun parseBackupWeights(arr: JSONArray?): List<WeightEntry> {
    if (arr == null) return emptyList()
    if (arr.length() > 50000) throw IOException("В копии слишком много измерений веса.")
    return (0 until arr.length()).map { i ->
        val item = arr.optJSONObject(i) ?: throw IOException("Измерение веса №${i + 1} повреждено.")
        val timestamp = item.optLong("timestamp", 0L)
        val kg = item.optDouble("weight_kg", -1.0)
        if (timestamp > 0L && kg.isFinite() && kg in 25.0..400.0) WeightEntry(timestamp, kg)
        else throw IOException("Измерение веса №${i + 1} содержит некорректные значения.")
    }.sortedBy { it.timestamp }
}

private fun buildBackupJson(meals: List<Meal>, profile: UserProfile, weights: List<WeightEntry>) = JSONObject().apply {
    put("app", "NutriAI"); put("backup_version", 1); put("exported_at", System.currentTimeMillis())
    put("profile", profile.toJson()); put("weights", weightsToJson(weights))
    put("meals", JSONArray().apply {
        meals.forEach { meal -> put(JSONObject().apply {
            put("id", meal.id); put("name", meal.name); put("grams", meal.grams)
            put("calories", meal.calories); put("protein", meal.protein); put("fat", meal.fat)
            put("carbs", meal.carbs); put("created_at", meal.createdAt)
        }) }
    })
}

private fun parseBackupMeals(root: JSONObject): List<Meal> {
    if (root.optString("app") != "NutriAI" || root.optInt("backup_version", -1) != 1) {
        throw IOException("Файл не является поддерживаемой резервной копией NutriAI.")
    }
    val arr = root.optJSONArray("meals") ?: throw IOException("В копии нет списка приёмов пищи.")
    if (arr.length() > 50000) throw IOException("В копии слишком много записей.")
    return (0 until arr.length()).map { i ->
        val item = arr.optJSONObject(i) ?: throw IOException("Запись №${i + 1} в копии повреждена.")
        val name = item.optString("name").trim()
        val grams = item.optInt("grams", 0)
        val kcal = item.optInt("calories", -1)
        val protein = item.optDouble("protein", -1.0)
        val fat = item.optDouble("fat", -1.0)
        val carbs = item.optDouble("carbs", -1.0)
        val created = item.optLong("created_at", 0L)
        if (name.isBlank() || grams !in 1..100000 || kcal !in 0..1000000 || !protein.isFinite() || !fat.isFinite() || !carbs.isFinite() || minOf(protein, fat, carbs) < 0 || maxOf(protein, fat, carbs) > 10000.0 || created <= 0) {
            throw IOException("Запись №${i + 1} содержит некорректные значения.")
        } else Meal(
            id = item.optLong("id", 0L).coerceAtLeast(0L), name = name.take(200), grams = grams.coerceAtMost(100000),
            calories = kcal.coerceAtMost(1000000), protein = protein.coerceAtMost(10000.0), fat = fat.coerceAtMost(10000.0),
            carbs = carbs.coerceAtMost(10000.0), createdAt = created
        )
    }
}

@Composable
private fun ProfileDialog(initial: UserProfile, onDismiss: () -> Unit, onSave: (UserProfile) -> Unit) {
    var age by remember(initial) { mutableStateOf(if (initial.age == 0) "" else initial.age.toString()) }
    var height by remember(initial) { mutableStateOf(if (initial.heightCm == 0) "" else initial.heightCm.toString()) }
    var weight by remember(initial) { mutableStateOf(if (initial.weightKg == 0.0) "" else initial.weightKg.toStringOneDecimal()) }
    var sex by remember(initial) { mutableStateOf(initial.sex) }
    var activity by remember(initial) { mutableStateOf(initial.activity) }
    var goal by remember(initial) { mutableStateOf(initial.goal) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = UserProfile(age.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0, weight.toDoubleOrNullFlexible() ?: 0.0, sex, activity, goal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Личный профиль") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(age, { age = it.filter(Char::isDigit) }, label = { Text("Возраст") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(height, { height = it.filter(Char::isDigit) }, label = { Text("Рост, см") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(weight, { weight = it }, label = { Text("Вес, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Пол для формулы расчёта", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sex == "male", onClick = { sex = "male" }, label = { Text("Мужской") })
                    FilterChip(selected = sex == "female", onClick = { sex = "female" }, label = { Text("Женский") })
                }
                Text("Активность", color = Muted, fontSize = 12.sp)
                listOf("light" to "Невысокая", "moderate" to "Средняя", "high" to "Высокая").forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = activity == key, onClick = { activity = key })
                        Text(label, fontSize = 14.sp)
                    }
                }
                Text("Цель", color = Muted, fontSize = 12.sp)
                listOf("lose" to "Снижение веса", "maintain" to "Поддержание", "gain" to "Набор массы").forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = goal == key, onClick = { goal = key })
                        Text(label, fontSize = 14.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242A33))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Оценочная дневная норма", color = Muted, fontSize = 12.sp)
                        if (preview.age in 1..17) {
                            Text("Не рассчитывается автоматически", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Accent)
                            Text("Для пользователей младше 18 лет приложение не устанавливает цель калорий по взрослой формуле. Обсуди потребности и цель с врачом или квалифицированным диетологом.", color = Muted, fontSize = 11.sp)
                        } else {
                            Text("${preview.calorieTarget()} ккал", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Accent)
                            Text("Расчёт приблизительный и не является медицинской рекомендацией.", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (!preview.isComplete() || preview.age !in 1..100 || preview.heightCm !in 100..240 || preview.weightKg !in 25.0..350.0) error = "Укажи корректные возраст, рост и вес."
            else onSave(preview)
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun WeightDialog(entries: List<WeightEntry>, currentWeight: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit, onDelete: (Long) -> Unit) {
    var weight by remember { mutableStateOf(if (currentWeight > 0) currentWeight.toStringOneDecimal() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("История веса") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(weight, { weight = it; error = null }, label = { Text("Текущий вес, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    val parsed = weight.toDoubleOrNullFlexible()
                    if (parsed == null || !parsed.isFinite() || parsed !in 25.0..400.0) error = "Введи вес от 25 до 400 кг."
                    else onSave(parsed)
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить измерение") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                HorizontalDivider()
                Text("Последние измерения", fontWeight = FontWeight.SemiBold)
                if (entries.isEmpty()) Text("Пока нет измерений.", color = Muted, fontSize = 13.sp)
                entries.sortedByDescending { it.timestamp }.take(30).forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${entry.weightKg.pretty()} кг", fontWeight = FontWeight.SemiBold)
                            Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp)), color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onDelete(entry.timestamp) }) { Text("Удалить") }
                    }
                }
                if (entries.size >= 2) {
                    val oldest = entries.minByOrNull { it.timestamp }
                    val latest = entries.maxByOrNull { it.timestamp }
                    if (oldest != null && latest != null && oldest.timestamp != latest.timestamp) {
                        val delta = latest.weightKg - oldest.weightKg
                        Text("Изменение за период: ${if (delta > 0) "+" else ""}${delta.pretty()} кг", color = Accent, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun MacroCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF242A33)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddMealDialog(initial: Meal? = null, onDismiss: () -> Unit, onSave: (Meal) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var grams by remember(initial) { mutableStateOf(initial?.grams?.toString() ?: "100") }
    var kcal100 by remember(initial) { mutableStateOf(initial?.let { (it.calories * 100.0 / it.grams.coerceAtLeast(1)).toStringOneDecimal() } ?: "") }
    var protein100 by remember(initial) { mutableStateOf(initial?.let { (it.protein * 100.0 / it.grams.coerceAtLeast(1)).toStringOneDecimal() } ?: "0") }
    var fat100 by remember(initial) { mutableStateOf(initial?.let { (it.fat * 100.0 / it.grams.coerceAtLeast(1)).toStringOneDecimal() } ?: "0") }
    var carbs100 by remember(initial) { mutableStateOf(initial?.let { (it.carbs * 100.0 / it.grams.coerceAtLeast(1)).toStringOneDecimal() } ?: "0") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Добавить продукт" else "Редактировать запись") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(grams, { grams = it.filter(Char::isDigit) }, label = { Text("Масса, г") }, singleLine = true)
                Text("Пищевая ценность на 100 г", color = Muted, fontSize = 12.sp)
                OutlinedTextField(kcal100, { kcal100 = it }, label = { Text("Ккал") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(protein100, { protein100 = it }, label = { Text("Белки") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(fat100, { fat100 = it }, label = { Text("Жиры") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(carbs100, { carbs100 = it }, label = { Text("Углеводы") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val g = grams.toIntOrNull()
                val kcal = kcal100.toDoubleOrNullFlexible()
                val p = protein100.toDoubleOrNullFlexible()
                val f = fat100.toDoubleOrNullFlexible()
                val c = carbs100.toDoubleOrNullFlexible()
                if (name.isBlank() || g == null || g !in 1..100000 || kcal == null || p == null || f == null || c == null ||
                    listOf(kcal, p, f, c).any { !it.isFinite() || it < 0 } || kcal > 1000 || maxOf(p, f, c) > 100) {
                    error = "Проверь название и числа. Масса — 1–100000 г, Ккал — до 1000, каждый макронутриент — до 100 г на 100 г продукта."
                } else {
                    val factor = g / 100.0
                    onSave(Meal(id = initial?.id ?: 0L, name = name.trim(), grams = g,
                        calories = (kcal * factor).roundToInt(), protein = p * factor, fat = f * factor, carbs = c * factor,
                        createdAt = initial?.createdAt ?: System.currentTimeMillis()))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ServerSettingsDialog(initialUrl: String, initialToken: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключение ИИ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Адрес FastAPI-сервера без / в конце.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(value = url, onValueChange = { url = it; error = null }, label = { Text("Адрес сервера") }, singleLine = true)
                OutlinedTextField(value = token, onValueChange = { token = it; error = null }, label = { Text("Токен сервера (если настроен)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Для эмулятора: http://10.0.2.2:8000. Для телефона укажи IP компьютера в той же Wi-Fi сети. Если на сервере задан API_ACCESS_TOKEN, введи здесь тот же токен. Используй HTTPS для публичного сервера.", color = Muted, fontSize = 12.sp)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleaned = url.trim().trimEnd('/')
                if (!(cleaned.startsWith("http://") || cleaned.startsWith("https://")) || cleaned.length < 10) {
                    error = "Укажи полный адрес, начинающийся с http:// или https://"
                } else onSave(cleaned, token)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AnalysisReviewDialog(analysis: FoodAnalysis, onDismiss: () -> Unit, onSave: (Meal) -> Unit) {
    var dishName by remember(analysis) { mutableStateOf(analysis.dishName) }
    val ingredients = remember(analysis) {
        mutableStateListOf<IngredientDraft>().apply {
            analysis.ingredients.forEach { ingredient ->
                val grams = ingredient.grams.coerceAtLeast(1.0)
                add(IngredientDraft(
                    name = ingredient.name,
                    grams = ingredient.grams.roundToInt().coerceAtLeast(1).toString(),
                    kcal100 = (ingredient.calories * 100.0 / grams).toStringOneDecimal(),
                    protein100 = (ingredient.proteinG * 100.0 / grams).toStringOneDecimal(),
                    fat100 = (ingredient.fatG * 100.0 / grams).toStringOneDecimal(),
                    carbs100 = (ingredient.carbsG * 100.0 / grams).toStringOneDecimal()
                ))
            }
        }
    }
    var error by remember(analysis) { mutableStateOf<String?>(null) }
    val calculated = ingredients.mapNotNull { it.toNutrition() }
    val totalKcal = calculated.sumOf { (it.grams * it.kcal100 / 100.0) }.roundToInt()
    val totalProtein = calculated.sumOf { it.grams * it.protein100 / 100.0 }
    val totalFat = calculated.sumOf { it.grams * it.fat100 / 100.0 }
    val totalCarbs = calculated.sumOf { it.grams * it.carbs100 / 100.0 }
    val totalGrams = calculated.sumOf { it.grams }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверь результат ИИ") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = dishName, onValueChange = { dishName = it }, label = { Text("Название приёма пищи") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Оценка ИИ: ${analysis.estimateRangeCalories.getOrNull(0)?.roundToInt() ?: 0}–${analysis.estimateRangeCalories.getOrNull(1)?.roundToInt() ?: 0} ккал", color = Muted, fontSize = 12.sp)
                Text(analysis.uncertaintyNote, color = Muted, fontSize = 12.sp)
                analysis.followUpQuestion?.takeIf { it.isNotBlank() }?.let { Text("Уточнение: $it", color = Accent, fontSize = 12.sp) }
                HorizontalDivider()
                ingredients.forEachIndexed { index, item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF242A33)), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(value = item.name, onValueChange = { ingredients[index] = item.copy(name = it) }, label = { Text("Продукт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = item.grams, onValueChange = { ingredients[index] = item.copy(grams = it.filter { ch -> ch.isDigit() }) }, label = { Text("Масса, г") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("Пищевая ценность на 100 г", color = Muted, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                OutlinedTextField(value = item.kcal100, onValueChange = { ingredients[index] = item.copy(kcal100 = it) }, label = { Text("Ккал") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = item.protein100, onValueChange = { ingredients[index] = item.copy(protein100 = it) }, label = { Text("Б") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                OutlinedTextField(value = item.fat100, onValueChange = { ingredients[index] = item.copy(fat100 = it) }, label = { Text("Ж") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = item.carbs100, onValueChange = { ingredients[index] = item.copy(carbs100 = it) }, label = { Text("У") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            TextButton(onClick = { ingredients.removeAt(index) }, enabled = ingredients.size > 1) { Text("Убрать продукт") }
                        }
                    }
                }
                OutlinedButton(onClick = { ingredients.add(IngredientDraft("Новый продукт", "100", "0", "0", "0", "0")) }, modifier = Modifier.fillMaxWidth()) { Text("Добавить ингредиент") }
                HorizontalDivider()
                Text("После исправлений", fontWeight = FontWeight.SemiBold)
                Text("$totalKcal ккал · ${totalGrams.roundToInt()} г", color = Accent, fontWeight = FontWeight.Bold)
                Text("Белки ${totalProtein.pretty()} г · жиры ${totalFat.pretty()} г · углеводы ${totalCarbs.pretty()} г", fontSize = 12.sp)
                Text("Измени массу и пищевую ценность на 100 г — итог пересчитается автоматически.", color = Muted, fontSize = 11.sp)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val valid = ingredients.mapNotNull { it.toNutrition() }
                if (dishName.isBlank() || valid.isEmpty() || valid.size != ingredients.size || valid.any { !it.grams.isFinite() || it.grams !in 1.0..100000.0 || listOf(it.kcal100, it.protein100, it.fat100, it.carbs100).any { n -> !n.isFinite() || n < 0 } || it.kcal100 > 1000 || maxOf(it.protein100, it.fat100, it.carbs100) > 100 }) {
                    error = "Проверь название, массу и значения КБЖУ."
                } else {
                    val grams = valid.sumOf { it.grams }
                    val kcal = valid.sumOf { it.grams * it.kcal100 / 100.0 }
                    val p = valid.sumOf { it.grams * it.protein100 / 100.0 }
                    val f = valid.sumOf { it.grams * it.fat100 / 100.0 }
                    val c = valid.sumOf { it.grams * it.carbs100 / 100.0 }
                    onSave(Meal(name = dishName.trim(), grams = grams.roundToInt(), calories = kcal.roundToInt(), protein = p, fat = f, carbs = c))
                }
            }) { Text("Сохранить в дневник") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private data class IngredientDraft(
    val name: String,
    val grams: String,
    val kcal100: String,
    val protein100: String,
    val fat100: String,
    val carbs100: String
) {
    fun toNutrition(): Nutrition? {
        val g = grams.toDoubleOrNullFlexible() ?: return null
        val k = kcal100.toDoubleOrNullFlexible() ?: return null
        val p = protein100.toDoubleOrNullFlexible() ?: return null
        val f = fat100.toDoubleOrNullFlexible() ?: return null
        val c = carbs100.toDoubleOrNullFlexible() ?: return null
        if (name.isBlank() || !g.isFinite() || g !in 1.0..100000.0 || listOf(k, p, f, c).any { !it.isFinite() || it < 0 } || k > 1000 || maxOf(p, f, c) > 100) return null
        return Nutrition(name, g, k, p, f, c)
    }
}

private data class Nutrition(val name: String, val grams: Double, val kcal100: Double, val protein100: Double, val fat100: Double, val carbs100: Double)

private data class FoodIngredient(
    val name: String,
    val grams: Double,
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val confidence: Double,
    val notes: String
)

private data class FoodAnalysis(
    val dishName: String,
    val ingredients: List<FoodIngredient>,
    val estimateRangeCalories: List<Double>,
    val uncertaintyNote: String,
    val followUpQuestion: String?
)

private suspend fun analyzeFood(context: Context, uri: Uri, baseUrl: String, apiToken: String): FoodAnalysis {
    val original = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(40 * 1024 * 1024) }
            ?: throw IOException("Не удалось прочитать изображение.")
    }
    if (original.isEmpty()) throw IOException("Выбранный файл пустой.")
    val bytes = withContext(Dispatchers.IO) { compressForUpload(original) }
    if (bytes.size > 12 * 1024 * 1024) throw IOException("Не удалось сжать фото до допустимого размера. Попробуй другой снимок.")
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("image", "food-image.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
        .build()
    val requestBuilder = Request.Builder().url("${baseUrl.trim().trimEnd('/')}/analyze-food").post(body)
    if (apiToken.isNotBlank()) requestBuilder.header("x-client-token", apiToken)
    val request = requestBuilder.build()
    val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    return withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(responseText).optString("detail", "") } catch (_: Exception) { "" }
                throw IOException(if (detail.isNotBlank()) detail else "Ошибка сервера: HTTP ${response.code}. Проверь адрес и запущен ли сервер.")
            }
            parseFoodAnalysis(JSONObject(responseText))
        }
    }
}

private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Файл превышает допустимый размер. Выбери файл поменьше.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun compressForUpload(input: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Файл не похож на изображение.")
    val maxSide = 1600
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options)
        ?: throw IOException("Не удалось обработать изображение.")
    var bitmap = decoded
    return try {
        val exif = try { ExifInterface(ByteArrayInputStream(input)) } catch (_: Exception) { null }
        val degrees = exif?.rotationDegrees?.toFloat() ?: 0f
        val flipped = exif?.isFlipped == true
        if (degrees != 0f || flipped) {
            val matrix = Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                if (degrees != 0f) postRotate(degrees)
            }
            val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (transformed !== decoded) {
                decoded.recycle()
                bitmap = transformed
            }
        }
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) throw IOException("Не удалось сжать фото.")
            output.toByteArray()
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun parseFoodAnalysis(json: JSONObject): FoodAnalysis {
    val jsonIngredients = json.optJSONArray("ingredients") ?: JSONArray()
    val ingredients = (0 until jsonIngredients.length()).map { index ->
        val item = jsonIngredients.getJSONObject(index)
        FoodIngredient(
            name = item.optString("name", "Продукт"),
            grams = item.optDouble("grams", 100.0).coerceAtLeast(0.0),
            calories = item.optDouble("calories", 0.0).coerceAtLeast(0.0),
            proteinG = item.optDouble("protein_g", 0.0).coerceAtLeast(0.0),
            fatG = item.optDouble("fat_g", 0.0).coerceAtLeast(0.0),
            carbsG = item.optDouble("carbs_g", 0.0).coerceAtLeast(0.0),
            confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            notes = item.optString("notes", "")
        )
    }
    if (ingredients.isEmpty()) throw IOException("ИИ не смог выделить продукты на фото. Попробуй другой снимок.")
    if (ingredients.any { ingredient ->
        ingredient.name.isBlank() || !ingredient.grams.isFinite() || ingredient.grams !in 1.0..5000.0 ||
            listOf(ingredient.calories, ingredient.proteinG, ingredient.fatG, ingredient.carbsG).any { !it.isFinite() || it < 0 } ||
            maxOf(ingredient.calories, ingredient.proteinG, ingredient.fatG, ingredient.carbsG) > 100000.0
    }) throw IOException("ИИ вернул некорректные значения. Попробуй повторить анализ.")
    val rangeJson = json.optJSONArray("estimate_range_calories") ?: JSONArray()
    val range = (0 until rangeJson.length()).map { rangeJson.optDouble(it, 0.0).coerceAtLeast(0.0) }
    return FoodAnalysis(
        dishName = json.optString("dish_name", "Приём пищи").ifBlank { "Приём пищи" },
        ingredients = ingredients,
        estimateRangeCalories = range,
        uncertaintyNote = json.optString("uncertainty_note", "Оценка приблизительная; проверь массу и состав."),
        followUpQuestion = json.optString("follow_up_question").takeIf { it.isNotBlank() && it != "null" }
    )
}

private fun String.toDoubleOrNullFlexible(): Double? = trim().replace(',', '.').toDoubleOrNull()
private fun Double.toStringOneDecimal(): String = String.format(Locale.US, "%.1f", this)
private fun Double.pretty(): String = if (this % 1.0 == 0.0) toInt().toString() else String.format(Locale.getDefault(), "%.1f", this)

private fun isToday(time: Long): Boolean {
    val today = java.util.Calendar.getInstance()
    val date = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return today.get(java.util.Calendar.YEAR) == date.get(java.util.Calendar.YEAR) && today.get(java.util.Calendar.DAY_OF_YEAR) == date.get(java.util.Calendar.DAY_OF_YEAR)
}
