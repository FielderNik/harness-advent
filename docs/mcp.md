# MCP-интеграции

## Назначение

Harness Advent подключает MCP-серверы только через серверную локальную конфигурацию. Браузер видит безопасный статус, имя и разрешённый репозиторий, но никогда не получает команду запуска, токены или переменные окружения MCP.

Каждая запись реестра указывается в `harness.local.properties` через `mcp.servers=<id>,<id>`. Для неё задаются команда, аргументы, переменные окружения, read-only режим, allowlist инструментов и таймаут. Это позволяет добавлять другие stdio MCP-серверы без изменения доменной модели.

## GitHub MCP только для чтения

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

## Границы

- MCP-инструменты не вызываются автоматически из `/help`; `/help` использует только локальный документационный RAG.
- Для вызова инструмента доступен `POST /api/v1/mcp/servers/{id}/tools/{toolName}`. Сервер проверяет allowlist до запуска MCP.
- Добавление write-инструментов, OAuth или публикации на GitHub не входит в этот этап и потребует отдельной политики и approval.
