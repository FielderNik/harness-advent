import "./styles.css";
import { HarnessApi, ApiError } from "./api/client.js";
import { subscribeToTaskEvents, type TaskEventSubscription } from "./api/events.js";
import type { Artifact, McpServer, ModelProfile, Project, Task, TaskEvent, TaskMode, TaskScenario, TestCoveragePlan, TestCoverageStatus } from "./api/types.js";
import { canCancelTask, isTerminalTask, modeLabel, scenarioLabel, statusLabel } from "./features/tasks/taskState.js";

type Page = "tasks" | "projects" | "coverage";
type Tab = "plan" | "timeline" | "ticket" | "sources" | "changes" | "checks" | "report";
type Dialog = { type: "cancel" } | { type: "approval" } | null;

const api = new HarnessApi();
const app = document.querySelector<HTMLDivElement>("#app");
if (!app) throw new Error("App root was not found.");
const appRoot = app;

let page: Page = "tasks";
let tab: Tab = "timeline";
let projects: Project[] = [];
let tasks: Task[] = [];
let profiles: ModelProfile[] = [];
let mcpServers: McpServer[] = [];
let coveragePlan: TestCoveragePlan | null = null;
let coverageProjectId: string | null = null;
let selectedTaskId: string | null = null;
let selectedProfileId = "";
let events: TaskEvent[] = [];
let artifacts: Artifact[] = [];
let health: "loading" | "ready" | "error" = "loading";
let streamState: "idle" | "connected" | "reconnecting" | "polling" = "idle";
let message = "";
let error = "";
let loading = false;
let dialog: Dialog = null;
let subscription: TaskEventSubscription | null = null;
let pollingTimer: number | null = null;

const testGenerationProfileId = "deepseek";

const escapeHtml = (value: string) => value
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;")
  .replaceAll("'", "&#039;");

const formatDate = (timestamp?: number) => timestamp
  ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeStyle: "short" }).format(timestamp)
  : "—";

const activeTask = () => tasks.find((task) => task.id === selectedTaskId) ?? null;
const activeProject = () => projects.find((project) => project.id === activeTask()?.projectId) ?? null;
const activeProfile = () => profiles.find((profile) => profile.id === selectedProfileId) ?? null;

function setMessage(next: string) {
  message = next;
  error = "";
}

function setError(cause: unknown) {
  error = cause instanceof ApiError
    ? `${cause.message}${cause.requestId ? ` Код запроса: ${cause.requestId}` : ""}`
    : "Не удалось связаться с сервером. Проверьте его доступность.";
  message = "";
}

async function loadInitial() {
  loading = true;
  render();
  try {
    const [healthResponse, loadedProjects, loadedTasks, loadedProfiles, loadedMcpServers] = await Promise.all([
      api.health(), api.projects(), api.tasks(), api.modelProfiles(), api.mcpServers(),
    ]);
    health = healthResponse.status === "ok" ? "ready" : "error";
    projects = loadedProjects;
    tasks = loadedTasks;
    profiles = loadedProfiles;
    mcpServers = loadedMcpServers;
    selectedProfileId = profiles[0]?.id ?? "";
    selectedTaskId = tasks[0]?.id ?? null;
    if (selectedTaskId) {
      await loadTaskDetail(selectedTaskId, false);
      trackTask(selectedTaskId);
    }
  } catch (cause) {
    health = "error";
    setError(cause);
  } finally {
    loading = false;
    render();
  }
}

async function loadTaskDetail(taskId: string, renderAfter = true) {
  const [task, loadedArtifacts] = await Promise.all([api.task(taskId), api.artifacts(taskId)]);
  tasks = [task, ...tasks.filter((item) => item.id !== task.id)];
  artifacts = loadedArtifacts;
  if (renderAfter) render();
}

function stopTracking() {
  subscription?.close();
  subscription = null;
  if (pollingTimer !== null) window.clearInterval(pollingTimer);
  pollingTimer = null;
  streamState = "idle";
}

function trackTask(taskId: string) {
  stopTracking();
  subscription = subscribeToTaskEvents(api.eventUrl(taskId), (event) => {
    events = [...events.filter((item) => item.id !== event.id), event].sort((a, b) => a.occurredAt - b.occurredAt);
    void refreshAfterEvent(taskId);
    render();
  }, (state) => {
    streamState = state;
    if (state === "reconnecting") startPolling(taskId);
    render();
  });
}

async function refreshAfterEvent(taskId: string) {
  try {
    await loadTaskDetail(taskId, false);
  } catch {
    // SSE уже сообщает пользователю о переподключении; безопасный polling попробует обновить состояние.
  }
}

function startPolling(taskId: string) {
  if (pollingTimer !== null) return;
  streamState = "polling";
  pollingTimer = window.setInterval(async () => {
    if (selectedTaskId !== taskId) return;
    try {
      await loadTaskDetail(taskId, false);
      render();
    } catch {
      // Временная недоступность сервера не перезаписывает уже показанные данные.
    }
  }, 5_000);
}

async function selectTask(taskId: string) {
  selectedTaskId = taskId;
  page = "tasks";
  events = [];
  artifacts = [];
  loading = true;
  render();
  try {
    await loadTaskDetail(taskId, false);
    trackTask(taskId);
  } catch (cause) {
    setError(cause);
  } finally {
    loading = false;
    render();
  }
}

function render() {
  const task = activeTask();
  appRoot.innerHTML = `
    <main class="app-shell">
      <aside class="sidebar" aria-label="Навигация и задачи">
        <header class="sidebar-header">
          <p class="wordmark">Harness Advent</p>
          <p class="caption">Инженерные задачи</p>
        </header>
        <nav class="navigation" aria-label="Разделы">
          ${navigationItem("tasks", "Задачи")}
          ${navigationItem("projects", "Проекты и RAG")}
          ${navigationItem("coverage", "Покрытие тестами")}
        </nav>
        <button class="primary-button new-task" data-action="new-task" type="button">Новая задача</button>
        ${page === "tasks" ? taskList() : projectList()}
      </aside>

      <section class="main-scene" aria-label="Рабочая область">
        ${notification()}
        ${page === "projects" ? projectsScreen() : page === "coverage" ? coverageScreen() : tasksScreen(task)}
      </section>

      <aside class="utility-panel" aria-label="Параметры и подтверждения">
        ${utilityPanel(task)}
      </aside>
    </main>
    ${dialog ? dialogMarkup(task) : ""}
    <div class="live-region" aria-live="polite">${escapeHtml(error || message || liveStatus())}</div>
  `;
}

function navigationItem(item: Page, label: string) {
  return `<button class="nav-item ${page === item ? "active" : ""}" data-page="${item}" type="button">${label}</button>`;
}

function taskList() {
  if (loading && tasks.length === 0) return `<p class="sidebar-empty">Загрузка задач…</p>`;
  if (tasks.length === 0) return `<p class="sidebar-empty">Создайте задачу для разрешённого проекта.</p>`;
  return `<section class="task-list" aria-label="Список задач">${tasks.map((task) => `
    <button class="task-list-item ${task.id === selectedTaskId ? "active" : ""}" data-task-id="${task.id}" type="button">
      <span>${escapeHtml(shorten(task.input, 58))}</span>
      <small>${statusLabel(task.status)} · ${formatDate(task.updatedAt)}</small>
    </button>`).join("")}</section>`;
}

function projectList() {
  if (projects.length === 0) return `<p class="sidebar-empty">Сервер пока не зарегистрировал разрешённые проекты.</p>`;
  return `<section class="task-list" aria-label="Подключённые проекты">${projects.map((project) => `
    <button class="task-list-item" data-project-id="${project.id}" type="button">
      <span>${escapeHtml(project.name)}</span>
      <small>${scanStatusLabel(project.scanStatus)}</small>
    </button>`).join("")}</section>`;
}

function notification() {
  if (error) return `<div class="notification error" role="alert">${escapeHtml(error)}<button data-action="clear-notification" aria-label="Закрыть ошибку" type="button">×</button></div>`;
  if (message) return `<div class="notification">${escapeHtml(message)}<button data-action="clear-notification" aria-label="Закрыть сообщение" type="button">×</button></div>`;
  return "";
}

function tasksScreen(task: Task | null) {
  if (!task) return newTaskScreen();
  const project = activeProject();
  return `
    <header class="scene-header">
      <div>
        <p class="eyebrow">${escapeHtml(project?.name ?? "Неизвестный проект")} · ${scenarioLabel(task.scenario)}</p>
        <h1>${escapeHtml(shorten(task.input, 120))}</h1>
        <p class="metadata">Создана ${formatDate(task.createdAt)} · ${modeLabel(task.mode)}</p>
      </div>
      <div class="header-actions">
        <span class="status status-${task.status}">${statusLabel(task.status)}</span>
        ${canCancelTask(task.status) ? `<button class="danger-button" data-action="ask-cancel" type="button">Отменить</button>` : ""}
      </div>
    </header>
    <section class="task-summary" aria-label="Состояние задачи">
      <div><span>Состояние</span><strong>${statusLabel(task.status)}</strong></div>
      <div><span>Поток</span><strong>${streamLabel()}</strong></div>
      <div><span>Последнее обновление</span><strong>${formatDate(task.updatedAt)}</strong></div>
    </section>
    <nav class="tabs" aria-label="Материалы задачи">
      ${tabButton("plan", "План")}${tabButton("timeline", "Журнал")}${task.scenario === "supportAnswer" ? tabButton("ticket", "Тикет") : ""}${tabButton("sources", "Источники")}${tabButton("changes", "Изменения")}${tabButton("checks", "Проверки")}${tabButton("report", "Отчёт")}
    </nav>
    <section class="tab-content">${tabContent(task)}</section>
  `;
}

function newTaskScreen() {
  const configuredProfiles = profiles.filter((profile) => profile.endpointConfigured);
  return `
    <header class="scene-header"><div><p class="eyebrow">Задачи</p><h1>Новая инженерная задача</h1><p class="metadata">Сервер создаст фоновое задание и останется источником его состояния.</p></div></header>
    <form class="task-form" data-form="help-command">
      <div><h2>Ассистент проекта</h2><p class="form-help">Команда <code>/help</code> ищет ответ только в индексированных README, <code>docs</code> и API-описаниях.</p></div>
      <div class="form-grid"><label>Проект<select name="projectId" required ${projects.length === 0 ? "disabled" : ""}><option value="">Выберите проект</option>${projects.map((project) => `<option value="${project.id}">${escapeHtml(project.name)} · ${scanStatusLabel(project.scanStatus)}</option>`).join("")}</select></label><label>Профиль модели<select name="modelProfileId" required ${configuredProfiles.length === 0 ? "disabled" : ""}>${configuredProfiles.map((profile) => `<option value="${profile.id}" ${profile.id === selectedProfileId ? "selected" : ""}>${escapeHtml(profile.provider)}</option>`).join("")}</select></label></div>
      <label>Команда<input name="command" required value="/help " placeholder="/help Как устроен сервер?" /></label>
      <div class="form-actions"><button class="primary-button" type="submit" ${projects.length === 0 || configuredProfiles.length === 0 ? "disabled" : ""}>Спросить ассистента</button></div>
    </form>
    <form class="task-form" data-form="support-answer">
      <div><h2>Ассистент поддержки</h2><p class="form-help">Получает тикет только через read-only YouTrack MCP, затем ищет ответ в RAG-источниках выбранного проекта. Убедитесь, что YouTrack MCP настроен в панели справа.</p></div>
      <div class="form-grid"><label>Проект<select name="projectId" required ${projects.length === 0 ? "disabled" : ""}><option value="">Выберите проект</option>${projects.map((project) => `<option value="${project.id}">${escapeHtml(project.name)} · ${scanStatusLabel(project.scanStatus)}</option>`).join("")}</select></label><label>Тикет YouTrack<input name="ticketId" required maxlength="128" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,127}" placeholder="TRAIN-42" /></label></div>
      <label>Профиль модели<select name="modelProfileId" required ${configuredProfiles.length === 0 ? "disabled" : ""}>${configuredProfiles.map((profile) => `<option value="${profile.id}" ${profile.id === selectedProfileId ? "selected" : ""}>${escapeHtml(profile.provider)}</option>`).join("")}</select></label>
      <label>Вопрос пользователя<textarea name="question" rows="4" required minlength="3" maxlength="4000" placeholder="Я случайно удалил тренировку. Можно ли её восстановить?"></textarea></label>
      <div class="form-actions"><button class="primary-button" type="submit" ${projects.length === 0 || configuredProfiles.length === 0 ? "disabled" : ""}>Получить ответ поддержки</button></div>
    </form>
    <form class="task-form" data-form="create-task">
      <label>Проект<select name="projectId" required ${projects.length === 0 ? "disabled" : ""}>
        <option value="">Выберите разрешённый проект</option>${projects.map((project) => `<option value="${project.id}">${escapeHtml(project.name)} · ${scanStatusLabel(project.scanStatus)}</option>`).join("")}
      </select></label>
      <div class="form-grid">
        <label>Сценарий<select name="scenario"><option value="ragQuestion">Поиск по проекту</option><option value="codeReview">Код-ревью</option><option value="agentWorkflow">Агентский сценарий</option></select></label>
        <label>Режим доступа<select name="mode"><option value="readOnly">Только чтение</option><option value="mayModify">Может изменить рабочую копию</option></select></label>
      </div>
      <label>Провайдер модели<select name="modelProfileId" required ${configuredProfiles.length === 0 ? "disabled" : ""}>
        ${configuredProfiles.map((profile) => `<option value="${profile.id}" ${profile.id === selectedProfileId ? "selected" : ""}>${escapeHtml(profile.provider)}</option>`).join("")}
      </select></label>
      <label>Описание<textarea name="input" rows="7" required placeholder="Что нужно исследовать, проверить или реализовать?"></textarea></label>
      <p class="form-help">Для облачного профиля сервер запросит подтверждение передачи выбранных фрагментов проекта. Изменяющая задача потребует отдельного подтверждения. Команды и ключи в браузер не передаются.</p>
      <div class="form-actions"><button class="primary-button" type="submit" ${projects.length === 0 || configuredProfiles.length === 0 ? "disabled" : ""}>Создать задачу</button></div>
    </form>`;
}

function tabButton(next: Tab, label: string) {
  return `<button class="tab ${tab === next ? "active" : ""}" data-tab="${next}" type="button">${label}</button>`;
}

function tabContent(task: Task) {
  const tabArtifacts = (type: string) => artifacts.filter((item) => item.type === type);
  if (tab === "timeline") return timelineContent();
  if (tab === "ticket") return artifactContent(tabArtifacts("supportTicketContext"), "Контекст тикета ещё не получен.", "После обращения к YouTrack MCP здесь появится очищенный контекст тикета.");
  if (tab === "plan") return artifactContent([...tabArtifacts("testCoveragePlanItem"), ...tabArtifacts("modelReport")], "План пока не сохранён отдельным артефактом.", "План и стадии сервера появятся здесь по мере развития API.");
  if (tab === "sources") return artifactContent(task.scenario === "supportAnswer" ? tabArtifacts("supportSources") : tabArtifacts("ragSources"), "Источники ещё не получены.", "Для поиска сначала отсканируйте проект и создайте задачу поиска.");
  if (tab === "report") return artifactContent([...tabArtifacts("testGenerationReport"), ...tabArtifacts("modelReport"), ...tabArtifacts("codeReviewReport"), ...tabArtifacts("supportAnswer")], "Итоговый ответ ещё не готов.", task.status === "completed" ? "Сервер не сохранил ответ модели для этой задачи." : "Ответ появится после завершения задачи.");
  if (tab === "changes") return task.scenario === "testGeneration" ? artifactContent(tabArtifacts("testGenerationWorkspace"), "Изменения ещё не подготовлены.", "После успешной проверки появится изолированный worktree и ветка.") : unavailableArtifact("Изменения рабочей копии", "Текущий безопасный серверный адаптер не изменяет файлы. Когда появятся diff-артефакты, они будут отображены здесь.");
  return task.scenario === "testGeneration" ? artifactContent(tabArtifacts("testCheck"), "Проверка ещё не запускалась.", "Результат разрешённой Gradle-проверки появится после записи теста.") : unavailableArtifact("Проверки", "Текущий безопасный серверный адаптер не запускает внешние команды. Проверки и их логи будут доступны отдельными артефактами.");
}

function timelineContent() {
  if (events.length === 0) return `<div class="empty-state"><h2>Журнал ещё пуст</h2><p>События появятся после запуска фонового задания.</p></div>`;
  return `<ol class="timeline">${events.map((event) => `<li class="timeline-event level-${event.level.toLowerCase()}"><time>${formatDate(event.occurredAt)}</time><div><strong>${escapeHtml(event.type)}</strong><p>${escapeHtml(event.message)}</p>${event.payload ? `<pre>${escapeHtml(event.payload)}</pre>` : ""}</div></li>`).join("")}</ol>`;
}

function artifactContent(items: Artifact[], heading: string, description: string) {
  if (items.length === 0) return `<div class="empty-state"><h2>${heading}</h2><p>${description}</p></div>`;
  return `<div class="artifact-list">${items.map((item) => `<article class="artifact"><header><strong>${escapeHtml(item.type)}</strong><time>${formatDate(item.createdAt)}</time></header>${item.path ? `<p class="technical">${escapeHtml(item.path)}${item.sha256 ? ` · ${escapeHtml(item.sha256)}` : ""}</p>` : ""}<pre>${escapeHtml(item.content ?? "Артефакт не содержит текстового представления.")}</pre></article>`).join("")}</div>`;
}

function unavailableArtifact(heading: string, description: string) {
  return `<div class="empty-state"><h2>${heading}</h2><p>${description}</p></div>`;
}

function projectsScreen() {
  return `
    <header class="scene-header"><div><p class="eyebrow">Проекты и RAG</p><h1>Разрешённые проекты</h1><p class="metadata">Сервер сканирует только каталоги из своей allowlist и не передаёт их в браузер целиком.</p></div></header>
    <form class="project-form" data-form="create-project">
      <div><h2>Подключить проект</h2><p class="form-help">Каталог должен быть заранее разрешён в <code>HARNESS_ALLOWED_PROJECTS</code> на сервере. Интерфейс не может обойти это ограничение.</p></div>
      <div class="form-grid"><label>Название<input name="name" required maxlength="128" placeholder="Например, DataMobileX" /></label><label>Абсолютный путь<input name="path" required placeholder="/Users/name/projects/my-project" /></label></div>
      <div class="form-actions"><button class="primary-button" type="submit">Добавить проект</button></div>
    </form>
    ${projects.length === 0 ? `<div class="empty-state compact-empty"><h2>Нет подключённых проектов</h2><p>Добавьте каталог выше, затем запустите сканирование.</p></div>` : `<div class="projects-table" role="region" aria-label="Подключённые проекты"><table><thead><tr><th>Проект</th><th>Статус индекса</th><th>Последнее сканирование</th><th></th></tr></thead><tbody>${projects.map((project) => `<tr><td><strong>${escapeHtml(project.name)}</strong><small class="technical">${escapeHtml(project.path)}</small></td><td>${scanStatusLabel(project.scanStatus)}</td><td>${formatDate(project.lastScannedAt)}</td><td><button class="secondary-button" data-action="scan-project" data-project-id="${project.id}" type="button">Сканировать</button> <button class="primary-button" data-action="test-generation" data-project-id="${project.id}" type="button">Написать тесты</button></td></tr>`).join("")}</tbody></table></div>`}
  `;
}

function coverageScreen() {
  const project = projects.find((item) => item.id === coverageProjectId);
  if (!coveragePlan) return `<header class="scene-header"><div><p class="eyebrow">Покрытие тестами</p><h1>План пока не создан</h1><p class="metadata">Нажмите «Написать тесты» у проекта: сервер найдёт реализации репозиториев и выберет один класс для пайплайна.</p></div></header>`;
  return `<header class="scene-header"><div><p class="eyebrow">${escapeHtml(project?.name ?? "Проект")} · репозитории</p><h1>План unit-тестов</h1><p class="metadata">Обновлён ${formatDate(coveragePlan.analyzedAt)}. Один пайплайн обрабатывает один класс.</p></div></header>
    <div class="projects-table" role="region" aria-label="План покрытия unit-тестами"><table><thead><tr><th>Класс</th><th>Тест</th><th>Статус</th><th>Причина</th></tr></thead><tbody>${coveragePlan.items.map((item) => `<tr><td><strong>${escapeHtml(item.className)}</strong><small class="technical">${escapeHtml(item.sourcePath)}</small></td><td class="technical">${escapeHtml(item.testPath)}</td><td>${coverageStatusLabel(item.status)}</td><td>${escapeHtml(item.reason ?? "—")}${item.pullRequestUrl ? `<br><a href="${escapeHtml(item.pullRequestUrl)}" target="_blank" rel="noreferrer">Pull request</a>` : ""}</td></tr>`).join("")}</tbody></table></div>`;
}

function utilityPanel(task: Task | null) {
  const profile = activeProfile();
  return `
    <section class="utility-section"><h2>Сервер</h2><p class="utility-status ${health}">${health === "ready" ? "Доступен" : health === "error" ? "Недоступен" : "Проверяется…"}</p><button class="text-button" data-action="refresh" type="button">Обновить данные</button></section>
    <section class="utility-section"><h2>Профиль модели</h2>
      <label class="compact-label">Профиль<select data-control="profile">${profiles.map((item) => `<option value="${item.id}" ${item.id === selectedProfileId ? "selected" : ""}>${escapeHtml(item.provider)}</option>`).join("")}</select></label>
      ${profile ? `<dl class="details-list"><div><dt>Доступность</dt><dd>${profile.endpointConfigured ? "Настроен сервером" : "Не готов"}</dd></div><div><dt>Контекст</dt><dd>${contextPolicyLabel(profile.contextPolicy)}</dd></div><div><dt>Модели</dt><dd>${profile.models.length ? escapeHtml(profile.models.join(", ")) : "Не опубликованы"}</dd></div><div><dt>Возможности</dt><dd>${escapeHtml(profile.capabilities.join(", "))}</dd></div></dl><p class="form-help">Ключи, endpoint и стоимость не передаются в UI текущим API.</p>` : `<p class="form-help">Сервер не опубликовал профили моделей.</p>`}
    </section>
    <section class="utility-section"><h2>MCP</h2>${mcpPanel()}</section>
    <section class="utility-section"><h2>Разрешения</h2>${approvalPanel(task)}</section>
  `;
}

function mcpPanel() {
  if (mcpServers.length === 0) return `<p class="form-help">Серверы MCP не настроены.</p>`;
  return `<dl class="details-list">${mcpServers.map((server) => `<div><dt>${escapeHtml(server.name)}</dt><dd>${server.enabled ? "Настроен" : "Не готов"} · ${server.readOnly ? "только чтение" : "доступ не ограничен"}${server.allowedRepositories.length ? ` · ${escapeHtml(server.allowedRepositories.join(", "))}` : ""}</dd></div>`).join("")}</dl>`;
}

function approvalPanel(task: Task | null) {
  if (!task || task.status !== "waitingApproval") return `<p class="form-help">Нет ожидающих подтверждений.</p>`;
  const isContextTransfer = task.pendingApprovalKind === "contextTransfer";
  const isPublication = task.pendingApprovalKind === "externalPublication";
  const copy = isContextTransfer
    ? "Выбранный облачный профиль получит только отобранные фрагменты проекта. Передача ещё не выполнена."
    : isPublication
      ? "Тесты прошли проверку. Подтверждение разрешит push ветки и создание pull request в разрешённом GitHub-репозитории."
    : "Задача может изменить рабочую копию. Сервер ещё не выполнял изменение.";
  return `<p class="approval-copy">${copy}</p><button class="primary-button full-width" data-action="ask-approval" type="button">Рассмотреть подтверждение</button>`;
}

function dialogMarkup(task: Task | null) {
  if (!task) return "";
  const isCancel = dialog?.type === "cancel";
  const isContextTransfer = task.pendingApprovalKind === "contextTransfer";
  const isPublication = task.pendingApprovalKind === "externalPublication";
  const title = isCancel ? "Отменить задачу?" : isContextTransfer ? "Подтвердить передачу контекста?" : isPublication ? "Создать pull request?" : "Подтвердить изменение рабочей копии?";
  const description = isCancel ? "Сервер остановит задачу, если отмена поддерживается текущим этапом." : isContextTransfer ? "В облачный провайдер будут переданы только отобранные безопасным сканером фрагменты проекта. Публикация и доступ к ключам не разрешаются." : isPublication ? "Сервер отправит уже проверенную ветку только в разрешённый GitHub-репозиторий и создаст pull request." : "Подтверждение разрешает следующий изменяющий шаг. Внешняя публикация и доступ к ключам не разрешаются.";
  const scope = isContextTransfer ? "Выбранные фрагменты проекта" : isPublication ? "Одна подготовленная ветка и один GitHub pull request" : "Только текущая рабочая копия";
  return `<div class="dialog-backdrop" data-action="close-dialog"><section class="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><h2 id="dialog-title">${title}</h2><p>${description}</p><dl class="details-list"><div><dt>Задача</dt><dd>${escapeHtml(shorten(task.input, 90))}</dd></div><div><dt>Режим</dt><dd>${modeLabel(task.mode)}</dd></div><div><dt>Область</dt><dd>${scope}</dd></div></dl><div class="dialog-actions"><button class="secondary-button" data-action="close-dialog" type="button">Не выполнять</button><button class="${isCancel ? "danger-button" : "primary-button"}" data-action="confirm-dialog" type="button">${isCancel ? "Отменить задачу" : "Подтвердить"}</button></div></section></div>`;
}

function liveStatus() {
  const task = activeTask();
  return task ? `Задача: ${statusLabel(task.status)}. ${streamLabel()}.` : "";
}

function streamLabel() {
  return ({ idle: "поток не подключён", connected: "события подключены", reconnecting: "поток переподключается", polling: "временный опрос сервера" } as const)[streamState];
}

function scanStatusLabel(status: Project["scanStatus"]) {
  return ({ NOT_SCANNED: "Не сканирован", READY: "Индекс готов", FAILED: "Ошибка сканирования" } as const)[status];
}

function contextPolicyLabel(policy: ModelProfile["contextPolicy"]) {
  return ({ LOCAL_ONLY: "Только локально", METADATA_ONLY: "Только метаданные", SELECTED_SOURCES: "Выбранные источники", APPROVED_TASK_CONTEXT: "Контекст после подтверждения" } as const)[policy];
}

function shorten(value: string, max: number) {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

appRoot.addEventListener("click", (event) => {
  const target = (event.target as Element).closest<HTMLElement>("[data-action], [data-page], [data-task-id], [data-tab]");
  if (!target) return;
  const taskId = target.dataset.taskId;
  if (taskId) { void selectTask(taskId); return; }
  if (target.dataset.page) { page = target.dataset.page as Page; render(); return; }
  if (target.dataset.tab) { tab = target.dataset.tab as Tab; render(); return; }
  switch (target.dataset.action) {
    case "new-task": stopTracking(); selectedTaskId = null; events = []; artifacts = []; page = "tasks"; render(); break;
    case "clear-notification": error = ""; message = ""; render(); break;
    case "refresh": void loadInitial(); break;
    case "ask-cancel": dialog = { type: "cancel" }; render(); break;
    case "ask-approval": dialog = { type: "approval" }; render(); break;
    case "close-dialog": dialog = null; render(); break;
    case "confirm-dialog": void confirmDialog(); break;
    case "scan-project": if (target.dataset.projectId) void scanProject(target.dataset.projectId); break;
    case "test-generation": if (target.dataset.projectId) void startTestGeneration(target.dataset.projectId); break;
  }
});

appRoot.addEventListener("change", (event) => {
  const target = event.target as HTMLSelectElement;
  if (target.dataset.control === "profile") { selectedProfileId = target.value; render(); }
});

appRoot.addEventListener("submit", (event) => {
  const form = event.target as HTMLFormElement;
  event.preventDefault();
  if (form.dataset.form === "create-task") void createTask(new FormData(form));
  if (form.dataset.form === "create-project") void createProject(new FormData(form));
  if (form.dataset.form === "help-command") void executeHelpCommand(new FormData(form));
  if (form.dataset.form === "support-answer") void createSupportAnswer(new FormData(form));
});

async function createTask(form: FormData) {
  const input = String(form.get("input") ?? "").trim();
  if (!input) return;
  loading = true;
  render();
  try {
    const task = await api.createTask({
      projectId: String(form.get("projectId")),
      scenario: String(form.get("scenario")) as TaskScenario,
      mode: String(form.get("mode")) as TaskMode,
      input,
      modelProfileId: String(form.get("modelProfileId")),
    }, crypto.randomUUID());
    tasks = [task, ...tasks.filter((item) => item.id !== task.id)];
    setMessage("Задача создана. Ожидаем события от сервера.");
    await selectTask(task.id);
  } catch (cause) {
    setError(cause);
  } finally {
    loading = false;
    render();
  }
}

async function executeHelpCommand(form: FormData) {
  const command = String(form.get("command") ?? "").trim();
  if (!command) return;
  loading = true;
  render();
  try {
    const task = await api.executeHelp({
      projectId: String(form.get("projectId")),
      command,
      modelProfileId: String(form.get("modelProfileId")),
    }, crypto.randomUUID());
    tasks = [task, ...tasks.filter((item) => item.id !== task.id)];
    setMessage("Вопрос передан ассистенту. Источники и ответ появятся в задаче.");
    await selectTask(task.id);
  } catch (cause) {
    setError(cause);
  } finally {
    loading = false;
    render();
  }
}

async function createSupportAnswer(form: FormData) {
  const ticketId = String(form.get("ticketId") ?? "").trim();
  const question = String(form.get("question") ?? "").trim();
  if (!ticketId || !question) return;
  loading = true;
  render();
  try {
    const task = await api.createSupportAnswer({
      projectId: String(form.get("projectId")),
      ticketId,
      question,
      modelProfileId: String(form.get("modelProfileId")),
    }, crypto.randomUUID());
    tasks = [task, ...tasks.filter((item) => item.id !== task.id)];
    setMessage("Запрос поддержки создан. Контекст тикета, источники и ответ появятся в задаче.");
    await selectTask(task.id);
  } catch (cause) {
    setError(cause);
  } finally {
    loading = false;
    render();
  }
}

async function scanProject(projectId: string) {
  loading = true;
  render();
  try {
    const project = await api.scanProject(projectId);
    projects = projects.map((item) => item.id === project.id ? project : item);
    setMessage(`Проект «${project.name}» просканирован.`);
  } catch (cause) { setError(cause); } finally { loading = false; render(); }
}

async function startTestGeneration(projectId: string) {
  const testGenerationProfile = profiles.find((profile) => profile.id === testGenerationProfileId && profile.endpointConfigured);
  if (!testGenerationProfile) { setError(new Error("Для генерации тестов нужен настроенный профиль DeepSeek.")); render(); return; }
  loading = true;
  render();
  try {
    const result = await api.startTestGeneration({ projectId, modelProfileId: testGenerationProfileId }, crypto.randomUUID());
    coveragePlan = result.plan;
    coverageProjectId = projectId;
    page = "coverage";
    if (result.task) {
      tasks = [result.task, ...tasks.filter((item) => item.id !== result.task!.id)];
      selectedTaskId = result.task.id;
      trackTask(result.task.id);
      setMessage("План обновлён. DeepSeek напишет тесты для одного класса после подтверждения изменения рабочей копии.");
    } else {
      setMessage("План обновлён: классов, которым нужны тесты, не найдено.");
    }
  } catch (cause) { setError(cause); } finally { loading = false; render(); }
}

function coverageStatusLabel(status: TestCoverageStatus) {
  return ({ needsTests: "Нужны тесты", covered: "Покрыт", coveredInOpenPr: "Тесты в открытом PR", inProgress: "В работе", testsWritten: "Тест записан", checking: "Проверка", awaitingPublication: "Ожидает публикации", prCreated: "PR создан", failed: "Ошибка", blocked: "Заблокирован" } as const)[status];
}

async function createProject(form: FormData) {
  const name = String(form.get("name") ?? "").trim();
  const path = String(form.get("path") ?? "").trim();
  if (!name || !path) return;
  loading = true;
  render();
  try {
    const project = await api.registerProject(name, path);
    projects = [project, ...projects.filter((item) => item.id !== project.id)];
    setMessage(`Проект «${project.name}» добавлен. Запустите сканирование перед созданием RAG-задачи.`);
  } catch (cause) { setError(cause); } finally { loading = false; render(); }
}

async function confirmDialog() {
  const task = activeTask();
  if (!task || !dialog) return;
  const requested = dialog.type;
  dialog = null;
  loading = true;
  render();
  try {
    const updated = requested === "cancel"
      ? await api.cancelTask(task.id)
      : (await api.approval(task.id, task.pendingApprovalKind ?? "fileModification", "approved"), await api.task(task.id));
    tasks = [updated, ...tasks.filter((item) => item.id !== updated.id)];
    setMessage(requested === "cancel" ? "Задача отменена сервером." : "Подтверждение отправлено серверу.");
    if (isTerminalTask(updated.status)) stopTracking();
  } catch (cause) { setError(cause); } finally { loading = false; render(); }
}

window.addEventListener("beforeunload", stopTracking);
void loadInitial();
