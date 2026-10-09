import assert from "node:assert/strict"
import test from "node:test"

import {
  applyQrLoginStatus,
  buildQrLoginPollCommand,
  isQrLoginComplete,
  isQrLoginTerminal,
} from "./xianyu-qr-login.ts"

test("each poll keeps the same intent, session, and reauthentication row across fresh HTTP request IDs", () => {
  const login = {
    status: "WAITING",
    mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
    loginIntentId: "intent-221",
    sessionId: "session-221",
    targetAccountRowId: "myshop-row-1",
    targetProviderAccountId: "2215747465381",
  }
  const first = buildQrLoginPollCommand(login, "request-a")
  const second = buildQrLoginPollCommand(login, "request-b")

  assert.equal(first.loginIntentId, second.loginIntentId)
  assert.equal(first.loginSessionId, second.loginSessionId)
  assert.equal(first.targetAccountRowId, second.targetAccountRowId)
  assert.notEqual(first.requestId, second.requestId)
})

test("scan confirmation and cookie persistence remain separate from provider authentication", () => {
  const login = {
    status: "WAITING",
    mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
    loginIntentId: "intent-221",
    sessionId: "session-221",
    targetAccountRowId: "myshop-row-1",
    targetProviderAccountId: "2215747465381",
  }
  for (const status of ["QR_SCAN_CONFIRMED", "COOKIE_PERSISTED"]) {
    const next = applyQrLoginStatus(login, {
      status,
      loginIntentId: login.loginIntentId,
      loginSessionId: login.sessionId,
      accountId: "2215747465381",
    })
    assert.equal(next.status, status)
    assert.equal(isQrLoginComplete(next.status), false)
  }
  assert.equal(isQrLoginComplete("PROVIDER_AUTH_VERIFIED"), true)
  assert.equal(isQrLoginTerminal("PROVIDER_AUTH_VERIFIED"), true)
  assert.equal(isQrLoginTerminal("PROVIDER_AUTH_VERIFICATION_FAILED"), true)
  assert.equal(isQrLoginTerminal("COOKIE_PERSISTED"), false)
})

test("mismatched intent, session, or scanned account fails the local flow closed", () => {
  const login = {
    status: "WAITING",
    mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
    loginIntentId: "intent-221",
    sessionId: "session-221",
    targetAccountRowId: "myshop-row-1",
    targetProviderAccountId: "2215747465381",
  }
  for (const response of [
    { status: "COOKIE_PERSISTED", loginIntentId: "other-intent", loginSessionId: login.sessionId },
    { status: "COOKIE_PERSISTED", loginIntentId: login.loginIntentId, loginSessionId: "other-session" },
    { status: "COOKIE_PERSISTED", loginIntentId: login.loginIntentId,
      loginSessionId: login.sessionId, accountId: "another-account" },
  ]) {
    const next = applyQrLoginStatus(login, response)
    assert.equal(next.status, "FAILED")
    assert.match(next.errorCode, /IDENTITY_CONFLICT|TARGET_MISMATCH/)
  }
})

test("reauthentication polling requires its bound account row", () => {
  const login = {
    status: "WAITING",
    mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
    loginIntentId: "intent-221",
    sessionId: "session-221",
  }
  assert.throws(() => buildQrLoginPollCommand(login, "request-a"), /QR_LOGIN_TARGET_REQUIRED/)
})
