# КТ3: документация и тестирование

КТ3 расширяет этот же проект КТ2: Ktor, PostgreSQL, JWT и веб-интерфейс «Фокус».

## Что добавлено

| Требование | Реализация |
|---|---|
| Swagger UI | `GET /swagger`, Ktor Swagger plugin; CSS/JS включены в JAR через WebJar |
| OpenAPI | `GET /openapi.json`; спецификация OpenAPI 3.0.3 в `src/main/resources/openapi/openapi.json` |
| Документация маршрутов | Все API-операции, фронтенд, документация и статические ресурсы; схемы, ограничения, примеры, HTTP-коды, JWT |
| Тесты API | `ApiTest`: реальные запросы к Ktor с настоящей PostgreSQL |
| Тесты StatusPages и middleware | `HttpPluginsTest`: обработка 404/405/409/500/503, request ID и безопасное логирование |
| Проверка OpenAPI | `OpenApiTest`: Swagger Parser проверяет спецификацию и ссылки; проверяются схемы и доступность Swagger |
| Обработка ошибок | `StatusPages` в `HttpPlugins.kt`, единый JSON `{code,message}` |
| Логирование | `CallLogging` + `CallId`; метод, путь, код, время и UUID запроса |
| Автоматическая проверка в GitHub | `.github/workflows/ci.yml`: PostgreSQL 17 + JDK 17 + Maven verify |

## 1. Подготовка

Нужны JDK 17+, PostgreSQL или Docker Desktop, интернет для первого скачивания зависимостей.
Maven отдельно устанавливать не нужно — используйте Wrapper из проекта.

Откройте папку проекта либо `pom.xml` в IntelliJ IDEA как Maven-проект и выберите JDK 17.
В PowerShell при необходимости задайте `JAVA_HOME` на свой установленный JDK:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
```

Если `java -version` работает с подходящей Java и Wrapper запускается, дополнительная настройка не нужна.

## 2. Запуск PostgreSQL

Windows с установленной PostgreSQL:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Database.ps1
```

Скрипт создаёт отдельный кластер в `.local/postgres`, не изменяя существующие базы.
Повторный запуск использует уже созданную БД.
Другой путь к бинарным файлам можно передать через `-PostgresBin 'D:\PostgreSQL\17\bin'`.

Альтернатива для Windows/Linux/macOS с Docker:

```shell
docker compose up -d --wait
```

Выберите один способ запуска. Оба используют `localhost:55434`, базу `ktor_tasks`,
учебного пользователя `tasks_app`, пароль `tasks_local_password`.
Таблицы создаются автоматически при запуске приложения.

Остановка с сохранением данных:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Stop-Database.ps1
```

Для Docker: `docker compose down` (данные сохраняются в volume).

## 3. Автоматические тесты

Из корня проекта, с запущенной БД:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```shell
chmod +x mvnw
./mvnw clean verify
```

Ожидаемый итог: **BUILD SUCCESS**, `Failures: 0`, `Errors: 0`, `Skipped: 0`.
Команда собирает исполняемый файл `target/ktor-tasks-api.jar`.
Отчёты JUnit находятся в `target/surefire-reports/`.

Отдельные группы (на Linux/macOS заменить `mvnw.cmd` на `./mvnw`):

```powershell
.\mvnw.cmd '-Dtest=ApiTest' test
.\mvnw.cmd '-Dtest=HttpPluginsTest,OpenApiTest' test
```

`ApiTest` проверяет регистрацию, конфликт логина, вход, профиль, хеширование пароля,
JWT (отсутствующий, повреждённый, просроченный, поддельный), разделение данных пользователей,
CRUD задач, поиск/фильтры/пагинацию, статистику, идемпотентность выполнения,
ошибки JSON/полей/query/path, статические ресурсы и реальную недоступность пула БД.

Каждый тест API создаёт отдельную случайную схему PostgreSQL и удаляет только её после завершения.
Пользователь тестовой БД должен иметь право создавать схемы; при стандартном локальном запуске оно есть.
Пользовательские таблицы и данные не удаляются. `HttpPluginsTest` и `OpenApiTest` запускаются без БД.
Маршруты, искусственно выбрасывающие исключения для тестирования 500/503, существуют только в тестовом сервере.

Для своей тестовой PostgreSQL задайте:

| Переменная | По умолчанию |
|---|---|
| `TEST_DATABASE_URL` | `jdbc:postgresql://localhost:55434/ktor_tasks` |
| `TEST_DATABASE_USER` | `tasks_app` |
| `TEST_DATABASE_PASSWORD` | `tasks_local_password` |

Используйте обычный JDBC URL без `currentSchema`: тесты сами добавляют изолированную схему.

## 4. Запуск приложения и Swagger UI

После успешной сборки:

```powershell
java -jar .\target\ktor-tasks-api.jar
```

Либо запустите `Main.kt` в IDEA. Если сервер уже был запущен, перезапустите его.

- Фронтенд: <http://localhost:8090/>
- Swagger UI: <http://localhost:8090/swagger>
- OpenAPI JSON: <http://localhost:8090/openapi.json>
- Проверка сервера и БД: <http://localhost:8090/health>

Swagger загружает свои ресурсы локально и работает без CDN.
URL сервера в спецификации относительный (`/`), поэтому Try it out работает и при другом `PORT`.

При необходимости конфигурация приложения задаётся переменными `PORT`, `DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET` (минимум 32 символа).
Без `JWT_SECRET` случайный ключ создаётся в `.local/jwt-secret` и сохраняется между перезапусками.

## 5. Проверка через Swagger: полный сценарий

1. Откройте `/swagger` и разверните **Authentication → POST /api/auth/register**.
2. Нажмите **Try it out**, передайте уникальный логин и пароль:

   ```json
   {"username":"student_kt3","password":"StrongPass123!"}
   ```

   Нажмите **Execute**: ожидается `201`. Повторите запрос: ожидается `409`.
3. В **POST /api/auth/login** передайте те же данные: ожидается `200`. Скопируйте поле `token`.
4. Нажмите **Authorize** сверху, вставьте только токен (без слова `Bearer`), подтвердите и закройте окно.
5. **GET /api/me** должен вернуть ваш ID и логин, без пароля и хеша.
6. **POST /api/tasks** с `{"title":"Проверить КТ3","description":"Swagger и тесты"}` вернёт `201`.
   Сохраните ID. В заголовке `Location` будет адрес созданной задачи.
7. **GET /api/tasks**: задайте `q=КТ3`, `completed=false`, `limit=10`, `offset=0`. Ожидается `200` и созданная задача.
8. **GET /api/tasks/{id}** вернёт задачу, **POST /api/tasks/{id}/complete** — выполненную задачу (`200`).
   Повторный complete также возвращает `200`.
9. **GET /api/tasks/stats** показывает счётчики только вашего пользователя.
10. **DELETE /api/tasks/{id}** возвращает `204` с пустым телом; последующий GET — `404`.
11. Укажите `id=abc` либо `limit=0`: ожидается `400` и JSON ошибки.
12. В **Authorize → Logout** удалите JWT и вызовите защищённый маршрут: ожидается `401`.

Готовые запросы для IntelliJ HTTP Client находятся в `requests.http`.

## 6. Ошибки и журнал запросов

| HTTP | Ситуация | `code` |
|---|---|---|
| 400 | Некорректный JSON / типы полей | `bad_request` |
| 400 | Неверные значения полей / query / path | `validation_error` |
| 401 | Нет/неверен/истёк JWT | `unauthorized` |
| 401 | Неверный логин или пароль | `invalid_credentials` |
| 404 | Неизвестный URL | `not_found` |
| 404 | Задача отсутствует или принадлежит другому пользователю | `task_not_found` |
| 405 | Неподдерживаемый HTTP-метод | `method_not_allowed` |
| 409 | Логин занят | `username_taken` |
| 415 | Неподходящий Content-Type тела | `unsupported_media_type` |
| 500 | Неожиданное исключение | `internal_error` |
| 503 | Ошибка PostgreSQL | `database_unavailable` |

Пример тела ошибки:

```json
{"code":"validation_error","message":"id должен быть положительным целым числом"}
```

В ответах есть заголовок `X-Request-ID` — UUID, который всегда генерирует сервер.
Найдите его в консоли IDEA или терминала, чтобы связать ответ с записью журнала:

```text
INFO http.access [requestId=...] - method=GET path=/api/tasks status=200 durationMs=12 requestId=...
```

Плагин логирует все запросы, в том числе неуспешные. В журнал доступа не записываются тела,
заголовки Authorization, пароли, JWT и query-параметры. Сообщения исключений и стек остаются
только в серверном журнале, клиент получает общее сообщение и request ID.

## 7. Проверка в GitHub

После push откройте репозиторий → **Actions → Build and test**.
Workflow поднимает PostgreSQL 17, устанавливает JDK 17 и выполняет `./mvnw --batch-mode verify`.
Результат последнего коммита должен быть зелёным.
В разделе **Artifacts → test-reports** можно скачать отчёты JUnit; они сохраняются на 7 дней,
включая результаты неуспешного запуска, если тесты успели сформировать отчёты.
Файлы `.local`, `.idea`, `.env`, логи и собранные JAR не загружаются в Git.
