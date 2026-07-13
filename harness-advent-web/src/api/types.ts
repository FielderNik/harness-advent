export type ProjectScanStatus = "NOT_SCANNED" | "READY" | "FAILED";
export type TaskScenario = "ragQuestion" | "codeReview" | "agentWorkflow";
export type TaskMode = "readOnly" | "mayModify";
export type TaskStatus = "queued" | "running" | "waitingApproval" | "completed" | "failed" | "cancelled";
export type EventLevel = "INFO" | "WARNING" | "ERROR";
export type ApprovalKind = "contextTransfer" | "commandExecution" | "fileModification" | "externalPublication";
export type ApprovalDecision = "approved" | "rejected";

export interface HealthResponse { status: string; }
export interface Project { id: string; name: string; path: string; scanStatus: ProjectScanStatus; lastScannedAt?: number; }
export interface Task {
  id: string;
  projectId: string;
  scenario: TaskScenario;
  mode: TaskMode;
  status: TaskStatus;
  author: string;
  input: string;
  createdAt: number;
  updatedAt: number;
  modelProfileId?: string;
  pendingApprovalKind?: ApprovalKind;
}
export interface TaskEvent {
  id: string;
  taskId: string;
  occurredAt: number;
  level: EventLevel;
  type: string;
  message: string;
  payload?: string;
}
export interface Artifact {
  id: string;
  taskId: string;
  type: string;
  path?: string;
  content?: string;
  sha256?: string;
  createdAt: number;
}
export interface Approval { id: string; taskId: string; kind: ApprovalKind; decision: ApprovalDecision; author: string; createdAt: number; }
export interface ModelProfile {
  id: string;
  provider: string;
  endpointConfigured: boolean;
  models: string[];
  contextPolicy: "LOCAL_ONLY" | "METADATA_ONLY" | "SELECTED_SOURCES" | "APPROVED_TASK_CONTEXT";
  capabilities: string[];
}
export interface McpServer {
  id: string;
  name: string;
  enabled: boolean;
  readOnly: boolean;
  allowedRepositories: string[];
}
export interface ApiErrorPayload { code: string; message: string; requestId: string; }

export interface TaskCreateInput {
  projectId: string;
  scenario: TaskScenario;
  mode: TaskMode;
  input: string;
  modelProfileId: string;
}

export interface HelpCommandInput {
  projectId: string;
  command: string;
  modelProfileId: string;
}
