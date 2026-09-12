import assert from "node:assert/strict"
import { test } from "node:test"

import {
  getDefaultPaymentMethod,
  getBalancePaymentAvailability,
  isImmediateBalancePaymentSuccess,
  paymentMethodForRequest,
  reconcilePaymentMethod,
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

test("a regular user with enough balance defaults to balance payment", () => {
  const availability = getBalancePaymentAvailability(true, "USER", 25, 25)
  assert.equal(getDefaultPaymentMethod(availability), "BALANCE")
})

test("a regular user without enough balance defaults to online payment", () => {
  const availability = getBalancePaymentAvailability(true, "USER", 24.99, 25)
  assert.equal(getDefaultPaymentMethod(availability), "ONLINE")
})

test("guests and unsupported roles always default to online payment", () => {
  assert.equal(getDefaultPaymentMethod(getBalancePaymentAvailability(false, undefined, 100, 10)), "ONLINE")
  assert.equal(getDefaultPaymentMethod(getBalancePaymentAvailability(true, "ADMIN", 100, 10)), "ONLINE")
})

test("a selected balance method falls back immediately when a later amount is no longer allowed", () => {
  const enough = getBalancePaymentAvailability(true, "USER", 20, 20)
  const insufficient = getBalancePaymentAvailability(true, "USER", 20, 21)
  assert.equal(reconcilePaymentMethod("BALANCE", enough), "BALANCE")
  assert.equal(reconcilePaymentMethod("BALANCE", insufficient), "ONLINE")
})

test("an explicit online choice is never reclaimed when the balance becomes enough", () => {
  const availability = getBalancePaymentAvailability(true, "USER", 50, 10)
  assert.equal(reconcilePaymentMethod("ONLINE", availability), "ONLINE")
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
