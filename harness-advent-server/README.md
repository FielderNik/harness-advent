# Сервер Harness Advent

`harness-advent-server` — локальный однопользовательский API для заданий над явно разрешёнными рабочими копиями. Долгая работа выполняется фоновым coroutine-исполнителем: HTTP-запрос только создаёт задание и возвращает его состояние.

## Что реализовано

- `SQLite` + `Exposed`: версионируемая схема для проектов, заданий, шагов, событий, артефактов, approvals и локальных RAG-фрагментов;
- регистрация только каталогов из `HARNESS_ALLOWED_PROJECTS`, безопасное сканирование README, `docs/`, API-описаний и исходного кода без `.git`, `.env`, ключей, build-артефактов и бинарных файлов;
- состояния задания `QUEUED`, `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`, отмена и ключ идемпотентности;
- события в SQLite и поток `/events` в формате SSE;
- артефакты RAG с точными путями, диапазонами строк и хешами;
- файловый агент для `agentWorkflow` в режиме `mayModify`: поиск, чтение и запись разрешённых текстовых файлов без shell-команд, с журналом операций и списком изменённых файлов;
- Koin-границы для репозиториев, исполнителя агента, моделей, MCP и Git; локальный реестр нескольких stdio MCP-серверов.

CodeAgent и Git-публикация по умолчанию отключены. MCP без записи в `mcp.servers` также отключён. Задачи получают ответы через отдельный `ModelProvider`, а не через Codex CLI: локальный OpenAI-compatible сервер, DeepSeek и OpenRouter подключаются одинаковым адаптером. Изменяющая задача останавливается в `WAITING_APPROVAL`; облачный профиль дополнительно требует подтверждения передачи контекста проекта.

## Конфигурация

Скопируй [harness.local.properties.example](harness.local.properties.example) в `harness.local.properties` рядом с `build.gradle.kts` и задай endpoint, каталог моделей и токены. Этот файл игнорируется Git. При необходимости указать другой путь используй только переменную `HARNESS_CONFIG_FILE`; значения моделей и токены остаются в properties-файле.

| Свойство | Назначение |
| --- | --- |
| `server.databaseUrl` | JDBC URL локальной SQLite, по умолчанию `jdbc:sqlite:./harness-advent.db` |
| `HARNESS_ALLOWED_PROJECTS` | Абсолютные каталоги через запятую, которые разрешено зарегистрировать |
| `models.<id>.endpoint` | Базовый OpenAI-compatible URL, например `https://api.deepseek.com/v1` |
| `models.<id>.models` | Разрешённые модели через запятую |
| `models.<id>.token` | Токен доступа. Нельзя добавлять в example-файл, логи или Git |
| `models.<id>.timeoutSeconds` | Таймаут одного обращения, от 1 до 120 секунд |
| `mcp.servers` | Идентификаторы разрешённых stdio MCP через запятую |
| `mcp.<id>.command`, `arguments` | Команда и аргументы MCP без shell-интерпретации |
| `mcp.<id>.allowedTools` | Точный allowlist MCP-инструментов |
| `mcp.<id>.readOnly` | Обязательный read-only режим для GitHub MCP |
| `codeReview.apiToken` | Секрет Bearer-аутентификации endpoint для GitHub Action |
| `codeReview.autoApproveContextProfiles` | Облачные профили через запятую, которым code review может передавать контекст без ручного approval |

Переменные `HARNESS_ALLOWED_PROJECTS` и `HARNESS_DATABASE_URL`, если они заданы окружением процесса, имеют приоритет над одноимёнными значениями properties-файла. Это используется Docker-конфигурацией, чтобы файл с настройками модели не мог расширить область разрешённого проекта.

## API

Все рабочие маршруты начинаются с `/api/v1`.

- `GET /health`;
- `GET, POST /projects`, `GET /projects/{id}`, `POST /projects/{id}/scan`;
- `GET, POST /tasks`, `GET /tasks/{id}`, `POST /tasks/{id}/cancel`;
- `GET /tasks/{id}/events` (SSE), `GET /tasks/{id}/artifacts`, `POST /tasks/{id}/approvals`;
- `GET /model-profiles`, `GET /model-profiles/{id}/models`, `GET /model-profiles/{id}/health`;
- `POST /model-profiles/{id}/completions` для OpenAI-compatible `chat/completions`.
- `POST /assistant/commands` для `/help <вопрос>`;
- `GET /mcp/servers`, `GET /mcp/servers/{id}/tools`, `POST /mcp/servers/{id}/tools/{toolName}`.
- `POST /code-reviews` для CI-ревью; требует `Authorization: Bearer <codeReview.apiToken>`.

Создание задания принимает `projectId`, `scenario` (`ragQuestion`, `codeReview`, `agentWorkflow`), `mode` (`readOnly`, `mayModify`), `modelProfileId` и `input`. Состояния возвращаются как `queued`, `running`, `waitingApproval`, `completed`, `failed` или `cancelled`. Повторная отправка с одинаковым заголовком `Idempotency-Key` возвращает существующее задание. `X-Actor` — только локальная аудиторская метка, а не механизм аутентификации.

Для completion отправь `model` (необязательно, берётся первая разрешённая модель), `prompt` и `externalContextApproved`. Для облачных профилей последнее поле обязательно должно быть `true`; сервер отказывается отправлять строку, похожую на API-ключ или приватный ключ, и никогда не возвращает токен в API или логи.

### `/help` и MCP

`POST /assistant/commands` принимает `projectId`, `command` и `modelProfileId`; в текущей версии поддерживается только `/help`. Команда создаёт обычную `ragQuestion` в режиме `readOnly`. Ответ модели должен ссылаться на переданные фрагменты документации, а точные источники остаются в `ragSources`-артефакте.

MCP остаётся отдельной read-only интеграцией и не вызывается автоматически из `/help`. Для GitHub используй официальный образ, `GITHUB_READ_ONLY=1`, один разрешённый репозиторий и fine-grained PAT с доступом только к нему. Полный пример находится в `harness.local.properties.example`, правила — в [docs/mcp.md](../docs/mcp.md).

### CI code review

`POST /code-reviews` принимает `projectId`, `modelProfileId`, `repository`, данные PR, `diff` и `changedFiles`. Сервер создаёт фоновую read-only задачу `codeReview`, сохраняет входные данные и RAG-контекст как артефакты, а итог модели — как `codeReviewReport`. Endpoint отключён, пока в конфигурации отсутствует `codeReview.apiToken`. Шаблон workflow копируется в целевой GitHub-репозиторий; полный способ настройки находится в [docs/code-review.md](../docs/code-review.md).

### Выполнение задачи через модель и файлы

Перед созданием задачи выбери настроенный профиль модели. Сервер берёт только источники, собранные безопасным сканером, и добавляет их в запрос модели как отдельные фрагменты. Локальному профилю контекст остаётся в локальной сети; для DeepSeek и OpenRouter сервер сначала запросит `contextTransfer` approval.

`agentWorkflow` в режиме `mayModify` после `fileModification` approval запускает ограниченный цикл `search → read → write → finish`. Модель возвращает одну структурированную операцию за шаг, а сервер сам выполняет её только относительно корня зарегистрированного проекта. Абсолютные пути, `..`, символьные ссылки, `.git`, `.env*`, бинарные файлы, ключи и build-каталоги отклоняются. В результате создаются артефакты `fileInventory`, `fileOperations`, `fileChanges` и `modelReport`; изменения сохраняются в рабочей копии. Shell и произвольные команды агенту не предоставляются.

## Логирование HTTP

Для каждого завершённого HTTP-запроса сервер пишет в стандартный лог метод, путь без query-параметров, статус ответа и `requestId`. Тела запросов и ответов, заголовки и query-параметры не логируются, чтобы не сохранять токены и другие чувствительные данные. `requestId` возвращается клиенту в заголовке `X-Request-ID`.

## Запуск и проверка

Требование: JDK 21.

```bash
./gradlew test build
./gradlew run
```

Перед публикацией проверь, что `.env`, SQLite-базы, `rag-index/`, `logs/` и артефакты сборки не попали в Git. Полные границы и модель безопасности описаны в [архитектуре](../docs/architecture.md) и [правилах безопасности](../docs/security.md).

## Docker и ngrok

Для запуска одного выбранного проекта в изолированном контейнере используй [инструкцию Docker](../docs/docker.md). Compose публикует API на `127.0.0.1:8095`; ngrok на той же машине подключается командой `ngrok http 8095`.
