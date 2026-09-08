import assert from "node:assert/strict"
import test from "node:test"
import {
  applyPaymentStatus,
  applyPaymentStatusError,
  createPaymentPageActionState,
} from "./payment-page-state.ts"

const qrCode = { type: "qr_code", value: "weixin://qr/demo" }
const redirect = { type: "redirect", url: "https://pay.example/checkout" }

test("an initial action starts ready so its QR can render before reconciliation", () => {
  const state = createPaymentPageActionState(qrCode)
  assert.equal(state.phase, "ready")
  assert.deepEqual(state.nextAction, qrCode)
})

test("without an initial action the first status request starts loading", () => {
  assert.equal(createPaymentPageActionState(null).phase, "loading")
})

test("initial status failure with a seed keeps the action and avoids a spinner", () => {
  const state = applyPaymentStatusError(createPaymentPageActionState(qrCode), "network failed", "initial")
  assert.equal(state.phase, "ready")
  assert.deepEqual(state.nextAction, qrCode)
  assert.equal(state.errorMessage, "network failed")
})

test("initial status failure without a seed becomes an explicit error", () => {
  const state = applyPaymentStatusError(createPaymentPageActionState(null), "network failed", "initial")
  assert.equal(state.phase, "error")
  assert.equal(state.nextAction, null)
  assert.equal(state.errorMessage, "network failed")
})

test("polling failure preserves the currently usable action", () => {
  const state = applyPaymentStatusError(createPaymentPageActionState(qrCode), "poll failed", "poll")
  assert.equal(state.phase, "ready")
  assert.deepEqual(state.nextAction, qrCode)
})

test("a server action replaces the seed and a terminal status clears it", () => {
  const seeded = createPaymentPageActionState(qrCode)
  const reconciled = applyPaymentStatus(seeded, "PENDING", redirect)
  assert.deepEqual(reconciled.nextAction, redirect)
  const terminal = applyPaymentStatus(reconciled, "PAID", null)
  assert.equal(terminal.nextAction, null)
  assert.equal(terminal.phase, "ready")
})
