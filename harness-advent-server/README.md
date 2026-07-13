# Сервер Harness Advent

`harness-advent-server` — локальный однопользовательский API для заданий над явно разрешёнными рабочими копиями. Долгая работа выполняется фоновым coroutine-исполнителем: HTTP-запрос только создаёт задание и возвращает его состояние.

## Что реализовано

- `SQLite` + `Exposed`: версионируемая схема для проектов, заданий, шагов, событий, артефактов, approvals и локальных RAG-источников;
- регистрация только каталогов из `HARNESS_ALLOWED_PROJECTS`, безопасное сканирование без `.git`, `.env`, ключей, build-артефактов и бинарных файлов;
- состояния задания `QUEUED`, `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`, отмена и ключ идемпотентности;
- события в SQLite и поток `/events` в формате SSE;
- артефакты RAG с точными путями, диапазонами строк и хешами;
- Koin-границы для репозиториев, исполнителя агента, моделей, MCP и Git.

В текущем безопасном локальном режиме CodeAgent, MCP, Git-публикация и сетевые вызовы моделей отключены. Read-only задача создаёт проверяемый отчёт без запуска команд. Изменяющая задача останавливается в `WAITING_APPROVAL`; даже после подтверждения сервер не меняет проект, пока не будет добавлен отдельный разрешённый адаптер.

## Конфигурация

Скопируй только нужные значения из [.env.example](.env.example) в локальное окружение. Файл `.env` сервер сам не читает: значения нужно экспортировать в процесс запуска или передать менеджером секретов.

| Переменная | Назначение |
| --- | --- |
| `HARNESS_DATABASE_URL` | JDBC URL локальной SQLite, по умолчанию `jdbc:sqlite:./harness-advent.db` |
| `HARNESS_ALLOWED_PROJECTS` | Абсолютные каталоги через запятую, которые разрешено зарегистрировать |
| `HARNESS_*_ENDPOINT`, `HARNESS_*_MODELS` | Безопасный каталог профилей Local, DeepSeek и OpenRouter; ключи не принимаются и не возвращаются API |

## API

Все рабочие маршруты начинаются с `/api/v1`.

- `GET /health`;
- `GET, POST /projects`, `GET /projects/{id}`, `POST /projects/{id}/scan`;
- `GET, POST /tasks`, `GET /tasks/{id}`, `POST /tasks/{id}/cancel`;
- `GET /tasks/{id}/events` (SSE), `GET /tasks/{id}/artifacts`, `POST /tasks/{id}/approvals`;
- `GET /model-profiles`, `GET /model-profiles/{id}/models`.

Создание задания принимает `projectId`, `scenario` (`ragQuestion`, `codeReview`, `agentWorkflow`), `mode` (`readOnly`, `mayModify`) и `input`. Состояния возвращаются как `queued`, `running`, `waitingApproval`, `completed`, `failed` или `cancelled`. Повторная отправка с одинаковым заголовком `Idempotency-Key` возвращает существующее задание. `X-Actor` — только локальная аудиторская метка, а не механизм аутентификации.

## Запуск и проверка

Требование: JDK 21.

```bash
./gradlew test build
./gradlew run
```

Перед публикацией проверь, что `.env`, SQLite-базы, `rag-index/`, `logs/` и артефакты сборки не попали в Git. Полные границы и модель безопасности описаны в [архитектуре](../docs/architecture.md) и [правилах безопасности](../docs/security.md).
