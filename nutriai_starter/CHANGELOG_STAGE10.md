# NutriAI Stage 10 — исправление найденных блокирующих ошибок

## Исправлено

- Исправлена синтаксическая ошибка Kotlin в объявлении состояния `carbs100` в `AddMealDialog`: отсутствовала закрывающая фигурная скобка. Это блокировало компиляцию файла.
- Для профиля пользователя младше 18 лет генератор рецептов и недельного меню больше не отправляет вручную введённую цель калорийности. Поле цели скрыто, отображается пояснение.
- Версия Android-приложения повышена до `0.9.0` (`versionCode = 9`).

## Проверки

- Python backend tests: 23 passed.
- Python syntax check: passed.
- Kotlin parser check with local `kotlinc`: no Kotlin syntax/parser errors after the fix. Full compilation was not possible because Android SDK and Gradle dependencies are not installed in the working environment.
- Android XML parsed successfully.

## Ограничения

Этот архив содержит исходный код, не APK. Полная сборка, Android Lint и проверка на физическом устройстве должны быть выполнены через GitHub Actions или Android Studio.
