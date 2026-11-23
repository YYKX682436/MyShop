import assert from "node:assert/strict"
import test from "node:test"
import { managedProductImagePath } from "./managed-product-image.ts"

test("only exact managed files from the current or main site use the optimizer", () => {
  const path = `/api/uploads/${"a".repeat(64)}.jpg`
  const origins = ["https://main.example", "https://shop.example"]
  assert.equal(managedProductImagePath(path, origins), path)
  assert.equal(managedProductImagePath(`https://main.example${path}`, origins), path)
  assert.equal(managedProductImagePath(`https://shop.example${path}`, origins), path)
  assert.equal(managedProductImagePath(`https://outside.example${path}`, origins), null)
  assert.equal(managedProductImagePath(`https://main.example${path}?v=2`, origins), null)
  assert.equal(managedProductImagePath(`https://main.example${path}#part`, origins), null)
  assert.equal(managedProductImagePath("https://main.example/api/uploads/avatars/file.png", origins), null)
  assert.equal(managedProductImagePath(`//outside.example${path}`, origins), null)
})
