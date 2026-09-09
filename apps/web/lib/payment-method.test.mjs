import assert from "node:assert/strict"
import { test } from "node:test"

import {
  getBalancePaymentAvailability,
  isImmediateBalancePaymentSuccess,
  paymentMethodForRequest,
} from "./payment-method.ts"
import { fingerprintPayload } from "./idempotency.ts"

test("balance payment is available only to regular users with enough balance", () => {
  assert.deepEqual(getBalancePaymentAvailability(true, "USER", 10, 10), {
    selectable: true,
    reason: "available",
  })
  assert.deepEqual(getBalancePaymentAvailability(true, "USER", 9.99, 10), {
    selectable: false,
    reason: "insufficient",
  })
  assert.deepEqual(getBalancePaymentAvailability(false, undefined, 100, 10), {
    selectable: false,
    reason: "login_required",
  })
})

test("balance success is immediate and does not depend on next_action", () => {
  const result = {
    order: { status: "PAID" },
    payment: { payment_method: "BALANCE", paid: true, status: "PAID", next_action: null },
  }
  assert.equal(isImmediateBalancePaymentSuccess(result), true)
  assert.equal(isImmediateBalancePaymentSuccess({
    order: { status: "PAID" },
    payment: { payment_method: "ONLINE", paid: true, status: "PAID", next_action: null },
  }), false)
})

test("checkout fingerprint changes with payment method and online channel", () => {
  const base = { product_id: "p1", payment_method: "ONLINE", payment_channel_code: "alipay" }
  assert.notEqual(fingerprintPayload(base), fingerprintPayload({ ...base, payment_method: "BALANCE", payment_channel_code: null }))
  assert.notEqual(fingerprintPayload(base), fingerprintPayload({ ...base, payment_channel_code: "wxpay" }))
})

test("checkout request keeps the channel only for online payment", () => {
  assert.deepEqual(paymentMethodForRequest("BALANCE", "alipay"), {
    payment_method: "BALANCE",
  })
  assert.deepEqual(paymentMethodForRequest("ONLINE", "alipay"), {
    payment_method: "ONLINE",
    payment_channel_code: "alipay",
  })
})
