# Ktor Tasks API

Учебный REST API личных задач на **Kotlin + Ktor + PostgreSQL** с регистрацией и JWT-аутентификацией.
Каждый пользователь видит и изменяет только свои задачи. Данные хранятся в PostgreSQL и сохраняются после перезапуска.

## Соответствие заданию

| Требование | Реализация |
|---|---|
| Сервер Ktor | Netty, `Main.kt`, `Application.kt` |
| GET | Список задач, задача по ID, профиль, проверка состояния |
| POST | Регистрация, вход, создание и выполнение задачи |
| DELETE | Удаление задачи |
| JSON | `ContentNegotiation`, `kotlinx.serialization`, DTO с `@Serializable` |
| Query-параметры | `q`, `completed`, `limit`, `offset` |
| Path-параметры | `{id}` с проверкой положительного `Long` |
| HTTP-коды | `200`, `201`, `204`, `400`, `401`, `404`, `409`, `415`, `500`, `503` |
| PostgreSQL | JDBC, HikariCP, таблицы `app_users` и `tasks`, внешний ключ, индекс |
| Аутентификация | Регистрация, вход, Bearer JWT сроком на час |
| Git-репозиторий | Исходники, Maven Wrapper, инструкция, GitHub Actions |

Версии зафиксированы в `pom.xml`: Kotlin 2.2.10, Ktor 3.2.3, Java 17.
Gradle и отдельная установка Maven не требуются: в проекте есть Maven Wrapper.

## Открытие в IntelliJ IDEA

1. **File → Open** → выбрать папку проекта или `pom.xml` → **Open as Project**.
2. Выбрать **Project SDK: JDK 17** (более новый JDK тоже подходит).
3. Дождаться импорта Maven. Если IDEA предложит загрузить изменения Maven — согласиться.
4. Запустить PostgreSQL одним из способов ниже.
5. Открыть `src/main/kotlin/ru/student/tasks/Main.kt` и нажать зелёную кнопку рядом с `fun main()`.
   Также добавлена конфигурация запуска **Ktor Tasks API**.

Адрес API: **http://localhost:8090**. Сервер слушает только локальный интерфейс.
`GET /health` проверяет доступность и сервера, и БД.

## PostgreSQL: установленный локально сервер на Windows

Скрипт использует бинарные файлы установленного PostgreSQL, создаёт отдельный кластер
в `.local/postgres` и запускает его на **127.0.0.1:55434**. Права администратора не нужны.
Данные существующих баз и системная служба PostgreSQL не изменяются.

В терминале проекта:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Database.ps1
```

Установка ищется в `C:\Program Files\PostgreSQL\<версия>\bin`. Другой путь:

```powershell
.\scripts\Start-Database.ps1 -PostgresBin 'D:\PostgreSQL\17\bin'
```

Параметры для IDEA Database / pgAdmin / DBeaver:

| Поле | Значение |
|---|---|
| Host | `localhost` |
| Port | `55434` |
| Database | `ktor_tasks` |
| User | `tasks_app` |
| Password | `tasks_local_password` |
| JDBC URL | `jdbc:postgresql://localhost:55434/ktor_tasks` |

Это учебные локальные реквизиты. Таблицы создаются автоматически при запуске приложения.
Данные и логи из `.local` игнорируются Git.

Остановка с сохранением данных:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Database.ps1
```

## PostgreSQL: альтернатива через Docker

Если PostgreSQL не установлен, запустить Docker Desktop и выполнить:

```shell
docker compose up -d --wait
```

Этот вариант использует те же реквизиты и порт. Выберите один способ запуска БД.
Остановка: `docker compose down`. Данные остаются в Docker volume `tasks_pgdata`.

## Сборка, тестирование и запуск из терминала

Нужны JDK 17+, интернет для первого скачивания зависимостей и запущенная PostgreSQL.
Переменная `JAVA_HOME` должна указывать на JDK.

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
java -jar .\target\ktor-tasks-api.jar
```

Linux / macOS (БД можно поднять через Docker):

```shell
chmod +x mvnw
./mvnw clean verify
java -jar target/ktor-tasks-api.jar
```

Для разработки можно выполнить `mvnw.cmd compile exec:java` (Windows) или `./mvnw compile exec:java`.
Чтобы остановить сервер в терминале, нажмите Ctrl+C; в IDEA — Stop.

Интеграционные тесты работают с **настоящей PostgreSQL**, создают отдельную случайную схему
на каждый тест и удаляют только эту схему после завершения. Проверяются вход, хеширование,
CRUD, поиск и пагинация, разделение данных пользователей, ошибки JSON, параметры и JWT.
GitHub Actions запускает эти же тесты с PostgreSQL 17.

## Маршруты

Все тела запросов и ответы с телом имеют JSON-формат. Для POST с телом указывать `Content-Type: application/json`.
Маршруты со знаком ✓ требуют заголовок `Authorization: Bearer <token>`.

| Метод | URL | JWT | Результат |
|---|---|---|---|
| GET | `/health` | | `200`: сервер и БД доступны |
| POST | `/api/auth/register` | | `201`: создан пользователь; `409`: логин занят |
| POST | `/api/auth/login` | | `200`: JWT; `401`: неверный логин или пароль |
| GET | `/api/me` | ✓ | `200`: мой ID и логин |
| GET | `/api/tasks` | ✓ | `200`: список моих задач |
| GET | `/api/tasks/{id}` | ✓ | `200`: задача; `404`: нет такой задачи у пользователя |
| POST | `/api/tasks` | ✓ | `201`: задача, заголовок `Location` |
| POST | `/api/tasks/{id}/complete` | ✓ | `200`: выполненная задача |
| DELETE | `/api/tasks/{id}` | ✓ | `204`: удалено, без тела; `404`: задача не найдена |

Пример query: `/api/tasks?completed=false&q=kotlin&limit=10&offset=0`.
`completed` — строго `true`/`false`; `q` — поиск подстроки в названии и описании без учёта регистра;
`limit` — 1–100 (по умолчанию 20); `offset` — от 0 (по умолчанию 0).
Порядок: новые задачи первыми. Ответ списка: `{"items": [...], "limit": 10, "offset": 0}`.

Неверные параметры/поля — `400`, нет/неверен/просрочен токен — `401`, неизвестный маршрут — `404`,
неподходящий Content-Type — `415`, ошибка БД — `503`, неожиданная ошибка — `500`.
Ошибка имеет вид `{"code":"validation_error","message":"id должен быть положительным целым числом"}`.
Для чужой задачи возвращается `404`.

## Пример полного сценария в PowerShell

```powershell
$base = 'http://localhost:8090'
$credentials = @{ username = 'student'; password = 'StrongPass123!' } | ConvertTo-Json
Invoke-RestMethod "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $credentials
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $credentials
$headers = @{ Authorization = "Bearer $($login.token)" }
$body = @{ title = 'Learn Kotlin'; description = 'Ktor and PostgreSQL' } | ConvertTo-Json
$task = Invoke-RestMethod "$base/api/tasks" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/api/tasks?completed=false&q=Kotlin&limit=10" -Headers $headers
Invoke-RestMethod "$base/api/tasks/$($task.id)" -Headers $headers
Invoke-RestMethod "$base/api/tasks/$($task.id)/complete" -Method Post -Headers $headers
Invoke-RestMethod "$base/api/tasks/$($task.id)" -Method Delete -Headers $headers
```

Готовые запросы для HTTP Client в IntelliJ IDEA Ultimate находятся в `requests.http`.
В Community Edition можно использовать сценарий PowerShell выше или Postman.

## Конфигурация

| Переменная окружения | Значение по умолчанию |
|---|---|
| `PORT` | `8090` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:55434/ktor_tasks` |
| `DATABASE_USER` | `tasks_app` |
| `DATABASE_PASSWORD` | `tasks_local_password` |
| `JWT_SECRET` | Генерируется случайно и хранится в `.local/jwt-secret` |
| `TEST_DATABASE_URL` | `jdbc:postgresql://localhost:55434/ktor_tasks` |
| `TEST_DATABASE_USER` | `tasks_app` |
| `TEST_DATABASE_PASSWORD` | `tasks_local_password` |

`JWT_SECRET`, если задан, должен содержать минимум 32 символа. При смене ключа старые токены перестают работать.
Переменные можно задать через **Run → Edit Configurations → Environment variables**.
Файлы `.env` автоматически не загружаются.

Пароли: PBKDF2-HMAC-SHA256, 600 000 итераций, случайная соль 16 байт, сравнение хешей через `MessageDigest.isEqual`.
JWT: HS256 с проверкой подписи, издателя, аудитории, срока и существования пользователя.
SQL использует prepared statements. JDBC и хеширование выполняются вне потока обработки запросов.
Это учебный локальный сервер: refresh-токены, восстановление пароля и публичный HTTPS-деплой не входят в реализацию.

## Структура

```text
src/main/kotlin/ru/student/tasks/
  Main.kt             — запуск Netty
  Application.kt      — плагины, маршруты, валидация, HTTP-ошибки
  Models.kt           — JSON DTO
  Settings.kt         — конфигурация окружения и локальный JWT-ключ
  Database.kt         — HikariCP и инициализация схемы
  Repositories.kt     — SQL-запросы к пользователям и задачам
  Security.kt         — хеширование паролей и выдача JWT
src/main/resources/db/schema.sql
src/test/kotlin/ru/student/tasks/ApiTest.kt
scripts/              — запуск и остановка локальной PostgreSQL
requests.http         — сценарий демонстрации
```

## Публикация на GitHub

Если remote ещё не настроен, после входа в GitHub CLI:

```shell
gh auth login
gh repo create KtorTasksApi --private --source=. --remote=origin --push
```

Чтобы преподаватель получил доступ, добавьте его в **Settings → Collaborators** либо смените видимость на public.
Передайте ссылку на репозиторий вместе с этой инструкцией. Не публикуйте `.local`, `.env` и реальные секреты.

## Документация использованных механизмов

- [Ktor: ContentNegotiation и JSON](https://ktor.io/docs/server-serialization.html)
- [Ktor: JWT-аутентификация](https://ktor.io/docs/server-jwt.html)
- [PostgreSQL: initdb](https://www.postgresql.org/docs/17/app-initdb.html)
- [OWASP: хранение паролей и параметры PBKDF2](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
