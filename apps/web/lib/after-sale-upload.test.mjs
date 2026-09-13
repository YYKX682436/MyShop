import assert from "node:assert/strict"
import test from "node:test"

import {
  AFTER_SALE_MAX_FILE_SIZE,
  AFTER_SALE_MAX_FILES,
  validateAfterSaleFile,
} from "./after-sale-upload.ts"

const file = (name, type, bytes) => new File([Uint8Array.from(bytes)], name, { type })

test("after-sale upload validation accepts the three supported image signatures", async () => {
  assert.equal(AFTER_SALE_MAX_FILES, 5)
  assert.equal(await validateAfterSaleFile(file("proof.JPG", "image/jpeg", [0xff, 0xd8, 0xff, 0xe0])), null)
  assert.equal(await validateAfterSaleFile(file("proof.png", "image/png", [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), null)
  assert.equal(await validateAfterSaleFile(file("proof.webp", "image/webp", [0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])), null)
})

test("after-sale upload validation rejects mismatched extension, MIME, and magic bytes", async () => {
  assert.equal(await validateAfterSaleFile(file("proof.png", "image/jpeg", [0xff, 0xd8, 0xff])), "FORMAT")
  assert.equal(await validateAfterSaleFile(file("proof.jpg", "image/jpeg", [0x89, 0x50, 0x4e, 0x47])), "FORMAT")
  assert.equal(await validateAfterSaleFile(file("proof.gif", "image/gif", [0x47, 0x49, 0x46, 0x38])), "FORMAT")
  assert.equal(await validateAfterSaleFile(file("proof.jpg", "image/jpeg", [])), "SIZE")
})

test("after-sale upload validation accepts the exact size boundary and rejects one byte over", async () => {
  const exact = new Uint8Array(AFTER_SALE_MAX_FILE_SIZE)
  exact.set([0xff, 0xd8, 0xff])
  assert.equal(await validateAfterSaleFile(new File([exact], "proof.jpg", { type: "image/jpeg" })), null)

  const oversized = new Uint8Array(AFTER_SALE_MAX_FILE_SIZE + 1)
  oversized.set([0xff, 0xd8, 0xff])
  assert.equal(await validateAfterSaleFile(new File([oversized], "proof.jpg", { type: "image/jpeg" })), "SIZE")
})
