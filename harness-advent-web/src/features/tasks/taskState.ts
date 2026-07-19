import type { TaskMode, TaskScenario, TaskStatus } from "../../api/types.js";

const statusLabels: Record<TaskStatus, string> = {
  queued: "В очереди",
  running: "Выполняется",
  waitingApproval: "Ожидает подтверждения",
  completed: "Завершена",
  failed: "Ошибка",
  cancelled: "Отменена",
};

const scenarioLabels: Record<TaskScenario, string> = {
  ragQuestion: "Поиск по проекту",
  supportAnswer: "Ассистент поддержки",
  codeReview: "Код-ревью",
  agentWorkflow: "Агентский сценарий",
};

const modeLabels: Record<TaskMode, string> = {
  readOnly: "Только чтение",
  mayModify: "Может изменить рабочую копию",
};

export const statusLabel = (status: TaskStatus) => statusLabels[status];
export const scenarioLabel = (scenario: TaskScenario) => scenarioLabels[scenario];
export const modeLabel = (mode: TaskMode) => modeLabels[mode];
export const isTerminalTask = (status: TaskStatus) => ["completed", "failed", "cancelled"].includes(status);
export const canCancelTask = (status: TaskStatus) => !isTerminalTask(status);
