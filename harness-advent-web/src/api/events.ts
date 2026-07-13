import type { TaskEvent } from "./types.js";

export interface TaskEventSubscription {
  close(): void;
}

export function subscribeToTaskEvents(
  url: string,
  onEvent: (event: TaskEvent) => void,
  onConnectionChange: (state: "connected" | "reconnecting") => void,
): TaskEventSubscription {
  const source = new EventSource(url);
  source.onopen = () => onConnectionChange("connected");
  source.onmessage = (message) => {
    try {
      onEvent(JSON.parse(message.data) as TaskEvent);
    } catch {
      // Некорректное событие игнорируется: состояние затем восстанавливается GET-запросом.
    }
  };
  source.onerror = () => onConnectionChange("reconnecting");
  return { close: () => source.close() };
}
