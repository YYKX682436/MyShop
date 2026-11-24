import assert from "node:assert/strict"
import test from "node:test"
import { managedImageVariant, managedProductImagePath } from "./managed-product-image.ts"

test("only exact managed files from the current or main site use the optimizer", () => {
  const path = `/api/uploads/${"a".repeat(64)}.jpg`
  const origins = ["https://main.example", "https://shop.example"]
  assert.equal(managedProductImagePath(path, origins), path)
  assert.equal(managedProductImagePath(`https://main.example${path}`, origins), path)
  assert.equal(managedProductImagePath(`https://shop.example${path}`, origins), path)
  assert.equal(managedProductImagePath(`https://outside.example${path}`, origins), null)
  assert.equal(managedProductImagePath(`https://main.example${path}?v=2`, origins), path)
  assert.equal(managedProductImagePath(`https://main.example${path}#part`, origins), path)
  assert.equal(managedProductImagePath(`${path}?v=1`, origins), path)
  assert.equal(managedProductImagePath(`/api/uploads/../${"a".repeat(64)}.jpg`, origins), null)
  assert.equal(managedProductImagePath("https://main.example/api/uploads/avatars/file.png", origins), null)
  assert.equal(managedProductImagePath(`//outside.example${path}`, origins), null)
})

test("managed display, thumbnail and clear URLs are stable and confined to trusted paths", () => {
  const display = "/api/uploads/images/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa-display.webp"
  assert.equal(managedProductImagePath(display, []), display)
  assert.equal(managedImageVariant(display, "thumb"), display.replace("-display.", "-thumb."))
  assert.equal(managedImageVariant(display, "clear"), display.replace("-display.", "-clear."))
  const avatar = "/api/uploads/avatars/managed-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb-display.webp"
  assert.equal(managedImageVariant(avatar, "thumb"), avatar.replace("-display.", "-thumb."))
  assert.equal(managedImageVariant(`https://outside.example${display}`, "clear"), null)
  assert.equal(managedImageVariant("/api/uploads/images/../../secret-display.webp", "clear"), null)
})
