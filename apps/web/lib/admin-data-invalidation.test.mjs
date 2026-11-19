import assert from "node:assert/strict"
import test from "node:test"
import { announceCreatedProduct, getAdminDataRevision, invalidateAdminData, prepareProductRestock, subscribeAdminDataInvalidation, takeCreatedProduct, takeProductRestock } from "./admin-data-invalidation.ts"

test("only affected resources receive one notification per change", () => {
  const beforeProducts = getAdminDataRevision("products")
  const beforeUsers = getAdminDataRevision("users")
  let products = 0
  let users = 0
  const unsubscribeProducts = subscribeAdminDataInvalidation("products", () => { products++ })
  const unsubscribeUsers = subscribeAdminDataInvalidation("users", () => { users++ })
  invalidateAdminData("products", "inventory", "products")
  assert.equal(getAdminDataRevision("products"), beforeProducts + 1)
  assert.equal(getAdminDataRevision("users"), beforeUsers)
  assert.equal(products, 1)
  assert.equal(users, 0)
  unsubscribeProducts()
  unsubscribeUsers()
})

test("the newly created product can be located exactly once", () => {
  announceCreatedProduct("new-product", 42)
  assert.deepEqual(takeCreatedProduct(), { id: "new-product", productNo: 42 })
  assert.equal(takeCreatedProduct(), null)
})

test("a full-page restock intent is consumed only once by the list", () => {
  prepareProductRestock("new-product")
  assert.equal(takeProductRestock(), "new-product")
  assert.equal(takeProductRestock(), null)
})
