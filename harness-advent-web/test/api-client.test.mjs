import test from "node:test";
import assert from "node:assert/strict";
import { ApiError, HarnessApi } from "../.test-dist/api/client.js";

test("API client sends task only to the server boundary with idempotency key", async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    return new Response(JSON.stringify({ id: "task-1", status: "queued" }), { status: 202 });
  };
  try {
    const api = new HarnessApi("http://server.test/");
    await api.createTask({ projectId: "project-1", scenario: "ragQuestion", mode: "readOnly", modelProfileId: "local", input: "Найди источник" }, "key-1");
    assert.equal(requests[0].url, "http://server.test/api/v1/tasks");
    assert.equal(new Headers(requests[0].init.headers).get("Idempotency-Key"), "key-1");
    assert.equal(new Headers(requests[0].init.headers).get("Authorization"), null);
    assert.equal(JSON.parse(requests[0].init.body).modelProfileId, "local");
  } finally { globalThis.fetch = originalFetch; }
});

test("API errors expose only safe server contract fields", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ code: "validation_error", message: "Некорректный запрос.", requestId: "req-1" }), { status: 400 });
  try {
    await assert.rejects(new HarnessApi("http://server.test").projects(), (error) => error instanceof ApiError && error.code === "validation_error" && error.requestId === "req-1");
  } finally { globalThis.fetch = originalFetch; }
});

test("project registration uses the same server API boundary", async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, init) => {
    request = { url, init };
    return new Response(JSON.stringify({ id: "project-1", name: "Проект", path: "/safe/project", scanStatus: "NOT_SCANNED" }), { status: 201 });
  };
  try {
    await new HarnessApi("http://server.test").registerProject("Проект", "/safe/project");
    assert.equal(request.url, "http://server.test/api/v1/projects");
    assert.deepEqual(JSON.parse(request.init.body), { name: "Проект", path: "/safe/project" });
  } finally { globalThis.fetch = originalFetch; }
});

test("support answer uses its dedicated endpoint and carries a ticket id", async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, init) => {
    request = { url, init };
    return new Response(JSON.stringify({ id: "task-42", scenario: "supportAnswer", status: "queued" }), { status: 202 });
  };
  try {
    await new HarnessApi("http://server.test").createSupportAnswer({
      projectId: "trainingdiary",
      ticketId: "TRAIN-42",
      question: "Можно ли восстановить тренировку?",
      modelProfileId: "local",
    }, "support-key");
    assert.equal(request.url, "http://server.test/api/v1/support/answers");
    assert.equal(new Headers(request.init.headers).get("Idempotency-Key"), "support-key");
    assert.deepEqual(JSON.parse(request.init.body), {
      projectId: "trainingdiary",
      ticketId: "TRAIN-42",
      question: "Можно ли восстановить тренировку?",
      modelProfileId: "local",
    });
  } finally { globalThis.fetch = originalFetch; }
});
