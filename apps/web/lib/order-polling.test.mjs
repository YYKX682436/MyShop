import assert from "node:assert/strict"
import test from "node:test"

import {
  FULFILLMENT_STATUS_POLL_INTERVAL,
  PAYMENT_RETURN_POLL_INTERVAL,
  PAYMENT_STATUS_POLL_INTERVAL,
  getOrderStatusPollInterval,
  shouldPollOrderStatus,
} from "./order-polling.ts"

const waitingManual = { fulfillment_status: "WAITING_MANUAL" }
const waitingAuto = { fulfillment_status: "PENDING" }
const delivered = { fulfillment_status: "DELIVERED" }
const cancelled = { fulfillment_status: "CANCELLED" }

test("polls while payment is pending", () => {
  assert.equal(shouldPollOrderStatus("PENDING", []), true)
  assert.equal(getOrderStatusPollInterval("PENDING", true), PAYMENT_RETURN_POLL_INTERVAL)
  assert.equal(getOrderStatusPollInterval("PENDING", false), PAYMENT_STATUS_POLL_INTERVAL)
  assert.equal(PAYMENT_STATUS_POLL_INTERVAL, 1000)
})

test("polls a paid order while manual fulfillment is waiting", () => {
  assert.equal(shouldPollOrderStatus("PAID", [waitingManual]), true)
  assert.equal(getOrderStatusPollInterval("PAID", false), FULFILLMENT_STATUS_POLL_INTERVAL)
})

test("polls a paid order while automatic fulfillment is pending", () => {
  assert.equal(shouldPollOrderStatus("PAID", [waitingAuto]), true)
})

test("stops once every paid-order item is terminal", () => {
  assert.equal(shouldPollOrderStatus("PAID", [delivered]), false)
  assert.equal(shouldPollOrderStatus("PAID", [delivered, cancelled]), false)
})

test("stops for delivered, refunded, and expired orders", () => {
  assert.equal(shouldPollOrderStatus("DELIVERED", [waitingManual]), false)
  assert.equal(shouldPollOrderStatus("REFUNDED", [waitingManual]), false)
  assert.equal(shouldPollOrderStatus("AFTER_SALE", [waitingManual]), false)
  assert.equal(shouldPollOrderStatus("EXPIRED", [waitingManual]), false)
  assert.equal(getOrderStatusPollInterval("DELIVERED", false), null)
  assert.equal(getOrderStatusPollInterval("REFUNDED", false), null)
  assert.equal(getOrderStatusPollInterval("AFTER_SALE", false), null)
  assert.equal(getOrderStatusPollInterval("EXPIRED", false), null)
})
