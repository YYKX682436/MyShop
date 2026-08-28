import assert from "node:assert/strict"
import test from "node:test"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "./image-upload.ts"

test("image upload validation matches the backend-safe formats and size", () => {
  assert.equal(validateImageFile({ type: "image/jpeg", size: 10 * 1024 * 1024 }), null)
  assert.equal(validateImageFile({ type: "image/svg+xml", size: 1 }), "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP")
  assert.equal(validateImageFile({ type: "image/png", size: 10 * 1024 * 1024 + 1 }), "图片大小不能超过 10MB")
  assert.equal(ALLOWED_IMAGE_ACCEPT.includes(".svg"), false)
})
