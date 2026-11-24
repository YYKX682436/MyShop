import assert from "node:assert/strict"
import test from "node:test"
import { managedProductImagePath } from "./managed-product-image.ts"

test("legacy managed image URLs retain local optimization without accepting other origins or paths", () => {
  const name = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png"
  const path = `/api/uploads/${name}`
  const origins = ["https://mc.mc520.top"]
  assert.equal(managedProductImagePath(`${path}?v=1`, origins), path)
  assert.equal(managedProductImagePath(`https://mc.mc520.top${path}`, origins), path)
  assert.equal(managedProductImagePath(`https://other.example${path}`, origins), null)
  assert.equal(managedProductImagePath("/api/uploads/avatars/avatar.png", origins), null)
  assert.equal(managedProductImagePath(`/api/uploads/../${name}`, origins), null)
})
