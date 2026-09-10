import assert from "node:assert/strict"
import test from "node:test"

import {
  getAdminOrderInvalidationRevision,
  invalidateAdminOrders,
  subscribeAdminOrderInvalidation,
} from "./admin-order-invalidation.ts"

test("admin order invalidation advances the revision and notifies subscribers", () => {
  const initialRevision = getAdminOrderInvalidationRevision()
  let notifications = 0
  const unsubscribe = subscribeAdminOrderInvalidation(() => { notifications += 1 })

  assert.equal(invalidateAdminOrders(), initialRevision + 1)
  assert.equal(getAdminOrderInvalidationRevision(), initialRevision + 1)
  assert.equal(notifications, 1)

  unsubscribe()
  assert.equal(invalidateAdminOrders(), initialRevision + 2)
  assert.equal(notifications, 1)
})

test("one invalidation notifies each active subscriber once", () => {
  let first = 0
  let second = 0
  const unsubscribeFirst = subscribeAdminOrderInvalidation(() => { first += 1 })
  const unsubscribeSecond = subscribeAdminOrderInvalidation(() => { second += 1 })

  invalidateAdminOrders()

  assert.equal(first, 1)
  assert.equal(second, 1)
  unsubscribeFirst()
  unsubscribeSecond()
})
