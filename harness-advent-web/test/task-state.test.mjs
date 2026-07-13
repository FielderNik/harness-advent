import test from "node:test";
import assert from "node:assert/strict";
import { canCancelTask, isTerminalTask, modeLabel, statusLabel } from "../.test-dist/features/tasks/taskState.js";

test("terminal task cannot be cancelled", () => {
  assert.equal(isTerminalTask("completed"), true);
  assert.equal(canCancelTask("cancelled"), false);
  assert.equal(canCancelTask("running"), true);
});

test("Russian labels describe task access and state", () => {
  assert.equal(statusLabel("waitingApproval"), "Ожидает подтверждения");
  assert.equal(modeLabel("mayModify"), "Может изменить рабочую копию");
});
