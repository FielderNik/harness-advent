# MCP-интеграции

## Назначение

Harness Advent подключает MCP-серверы только через серверную локальную конфигурацию. Браузер видит безопасный статус, имя и разрешённый репозиторий, но никогда не получает команду запуска, токены или переменные окружения MCP.

Каждая запись реестра указывается в `harness.local.properties` через `mcp.servers=<id>,<id>`. Для неё задаются команда, аргументы, переменные окружения, read-only режим, allowlist инструментов и таймаут. Это позволяет добавлять другие stdio MCP-серверы без изменения доменной модели.

## GitHub MCP: чтение контекста GitHub

Первая конфигурация использует официальный образ [GitHub MCP Server](https://github.com/github/github-mcp-server):

```properties
mcp.servers=github
mcp.github.command=docker
mcp.github.arguments=run,-i,--rm,-e,GITHUB_PERSONAL_ACCESS_TOKEN,-e,GITHUB_READ_ONLY,-e,GITHUB_TOOLSETS,ghcr.io/github/github-mcp-server
mcp.github.readOnly=true
mcp.github.allowedRepositories=owner/repository
mcp.github.allowedTools=get_file_contents,search_code,issue_read,pull_request_read
mcp.github.env.GITHUB_PERSONAL_ACCESS_TOKEN=
mcp.github.env.GITHUB_READ_ONLY=1
mcp.github.env.GITHUB_TOOLSETS=repos,issues,pull_requests
```

Значение `owner/repository` замени на единственный разрешённый репозиторий. Для токена создай fine-grained PAT GitHub, выбери только этот репозиторий и выдай исключительно права на чтение, нужные для выбранных инструментов. Токен указывается только в игнорируемом `harness.local.properties`.

Защита двойная: GitHub MCP запускается с `GITHUB_READ_ONLY=1`, а Harness допускает только перечисленные инструменты и принудительно подставляет разрешённые `owner` и `repo` в вызов. Значения для другого репозитория отклоняются до обращения к MCP. Это не отменяет ограничения самого PAT.

После запуска сервера реестр можно проверить через `GET /api/v1/mcp/servers`; список безопасно опубликованных инструментов — через `GET /api/v1/mcp/servers/github/tools`.

GitHub MCP остаётся read-only. Создание PR для `testGeneration` выполняется отдельным GitHub REST-адаптером после `externalPublication`: он использует `testGeneration.githubToken`, допускает только `testGeneration.allowedRepository`, ветку `harness/tests/*` и настроенную базовую ветку. Это не требует Docker socket или Docker CLI внутри контейнера Harness.

## YouTrack для ассистента поддержки

Сценарий `supportAnswer` использует отдельный stdio MCP только для одного инструмента `youtrack_get_issue`. Он получает тикет, очищает его содержимое перед сохранением в артефакте и объединяет его с локальным RAG-контекстом зарегистрированного проекта. Поля тикета не принимаются из HTTP-запроса и не могут расширить запрошенный набор данных.

Для локального сервера из `/Users/alexey_nik/advent_ai/mcp_youtrack_server` добавь в игнорируемый `harness.local.properties`:

```properties
mcp.servers=youtrack
support.youtrack.serverId=youtrack
mcp.youtrack.command=node
mcp.youtrack.arguments=/Users/alexey_nik/advent_ai/mcp_youtrack_server/scripts/run-stdio.mjs
mcp.youtrack.readOnly=true
mcp.youtrack.allowedTools=youtrack_get_issue
mcp.youtrack.timeoutSeconds=30
mcp.youtrack.env.YOUTRACK_URL=
mcp.youtrack.env.YOUTRACK_TOKEN=
```

Сначала выполни `npm run build` в каталоге MCP. Не добавляй write-инструменты (`save`, `start`, `stop`) в allowlist: они не нужны для ответа пользователю. `YOUTRACK_TOKEN` должен иметь только доступ на чтение нужных проектов и не должен появляться в Git, артефактах, логах или ответах API.

## Границы

- MCP-инструменты не вызываются автоматически из `/help`; `/help` использует только локальный документационный RAG.
- `supportAnswer` автоматически вызывает только allowlisted `youtrack_get_issue`; все остальные MCP-инструменты, включая запись файлов и периодические задания, исключены.
- Для вызова инструмента доступен `POST /api/v1/mcp/servers/{id}/tools/{toolName}`. Сервер проверяет allowlist до запуска MCP.
- Создание PR для `testGeneration` допускается только после `externalPublication`; другие публикации требуют отдельного контракта и approval.
