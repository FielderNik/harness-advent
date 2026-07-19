import type {
  Approval,
  ApprovalDecision,
  ApprovalKind,
  ApiErrorPayload,
  Artifact,
  HealthResponse,
  ModelProfile,
  McpServer,
  Project,
  Task,
  TaskCreateInput,
  HelpCommandInput,
  SupportAnswerInput,
} from "./types.js";

export class ApiError extends Error {
  constructor(readonly code: string, message: string, readonly requestId?: string) {
    super(message);
    this.name = "ApiError";
  }
}

const normalizeBaseUrl = (baseUrl: string) => baseUrl.replace(/\/$/, "");

export class HarnessApi {
  readonly baseUrl: string;

  constructor(baseUrl = import.meta.env.VITE_API_BASE_URL || "") {
    this.baseUrl = normalizeBaseUrl(baseUrl);
  }

  health() { return this.request<HealthResponse>("/health"); }
  projects() { return this.request<Project[]>("/api/v1/projects"); }
  registerProject(name: string, path: string) {
    return this.request<Project>("/api/v1/projects", {
      method: "POST",
      body: JSON.stringify({ name, path }),
    });
  }
  project(id: string) { return this.request<Project>(`/api/v1/projects/${encodeURIComponent(id)}`); }
  scanProject(id: string) { return this.request<Project>(`/api/v1/projects/${encodeURIComponent(id)}/scan`, { method: "POST" }); }
  tasks() { return this.request<Task[]>("/api/v1/tasks"); }
  task(id: string) { return this.request<Task>(`/api/v1/tasks/${encodeURIComponent(id)}`); }
  artifacts(id: string) { return this.request<Artifact[]>(`/api/v1/tasks/${encodeURIComponent(id)}/artifacts`); }
  modelProfiles() { return this.request<ModelProfile[]>("/api/v1/model-profiles"); }
  mcpServers() { return this.request<McpServer[]>("/api/v1/mcp/servers"); }
  models(profileId: string) { return this.request<string[]>(`/api/v1/model-profiles/${encodeURIComponent(profileId)}/models`); }

  createTask(input: TaskCreateInput, idempotencyKey: string) {
    return this.request<Task>("/api/v1/tasks", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    });
  }

  executeHelp(input: HelpCommandInput, idempotencyKey: string) {
    return this.request<Task>("/api/v1/assistant/commands", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    });
  }

  createSupportAnswer(input: SupportAnswerInput, idempotencyKey: string) {
    return this.request<Task>("/api/v1/support/answers", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    });
  }

  cancelTask(id: string) {
    return this.request<Task>(`/api/v1/tasks/${encodeURIComponent(id)}/cancel`, { method: "POST" });
  }

  approval(id: string, kind: ApprovalKind, decision: ApprovalDecision) {
    return this.request<Approval>(`/api/v1/tasks/${encodeURIComponent(id)}/approvals`, {
      method: "POST",
      body: JSON.stringify({ kind, decision }),
    });
  }

  eventUrl(taskId: string) {
    return `${this.baseUrl}/api/v1/tasks/${encodeURIComponent(taskId)}/events`;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...init.headers },
    });
    if (!response.ok) {
      const error = await safeError(response);
      throw new ApiError(error.code, error.message, error.requestId);
    }
    return response.json() as Promise<T>;
  }
}

async function safeError(response: Response): Promise<ApiErrorPayload> {
  try {
    const payload = await response.json() as Partial<ApiErrorPayload>;
    if (typeof payload.code === "string" && typeof payload.message === "string") {
      return { code: payload.code, message: payload.message, requestId: typeof payload.requestId === "string" ? payload.requestId : "" };
    }
  } catch {
    // Ответы вне API-контракта не раскрываются пользователю.
  }
  return { code: "request_failed", message: "Сервер не смог обработать запрос.", requestId: "" };
}
