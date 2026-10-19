# Как получить APK NutriAI 0.9.0 (Stage 10)

## Вариант 1 — собрать APK через GitHub Actions (без Android SDK на своём ПК)

Workflow устанавливает Java 17, Android SDK Platform 35 и Build Tools 35.0.0, принимает лицензии, запускает backend-тесты, Android unit tests, lint и сборку debug APK, а затем проверяет APK с помощью `aapt`.

1. Распакуй архив Stage 10.
2. Создай пустой репозиторий на GitHub.
3. Загрузи **содержимое папки `nutriai_starter`** в корень репозитория. Обязательно загрузи скрытую папку `.github`.
4. Открой вкладку **Actions** и выбери workflow **NutriAI checks**.
5. Нажми **Run workflow**. Также сборка запускается автоматически при push и pull request.
6. Дождись завершения job `android-build`. Если он успешен, открой запуск workflow и скачай artifact `nutriai-debug-apk`. Внутри находится `app-debug.apk`.
7. Перенеси APK на Huawei nova Y91 и разреши установку из выбранного источника, если Android это запросит. Устанавливай только APK из собственного репозитория.

Если сборка упала, открой логи job `android-build`. При наличии отчётов они будут прикреплены отдельным artifact `nutriai-android-build-reports`. Не считай APK готовым, пока workflow не завершился успешно.

## Вариант 2 — Android Studio

1. Установи Android Studio и Android SDK Platform 35.
2. Открой папку `android` как Gradle-проект.
3. Дождись Gradle Sync.
4. Выбери **Build → Build APK(s)**.
5. После успешной сборки APK обычно находится в `android/app/build/outputs/apk/debug/app-debug.apk`.

## Сервер ИИ

APK само по себе не содержит сервер или ключ ИИ. Для распознавания фото и генерации рецептов отдельно запусти `backend` по инструкции в `README.md` и укажи адрес сервера и клиентский токен в настройках приложения. На публичном сервере установи `NUTRIAI_ENV=production`, задай длинный случайный `API_ACCESS_TOKEN` и используй HTTPS. Не добавляй `GEMINI_API_KEY` в Kotlin-код, APK или публичный репозиторий.

## Что означает debug APK

Debug APK предназначен для тестирования и ручной установки, а не для публикации в Google Play. До подтверждённой сборки и проверки на устройстве приложение не следует считать полностью протестированным.
