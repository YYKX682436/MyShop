import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"
import {
  ALLOWED_IMAGE_ACCEPT,
  AVATAR_ALLOWED_IMAGE_ACCEPT,
  MAX_AVATAR_SIZE_BYTES,
  getAvatarFileValidationError,
  QR_ALLOWED_IMAGE_ACCEPT,
  validateImageFile,
  validateQrImageFile,
} from "./image-upload.ts"

const dropzoneSource = readFileSync(new URL("../components/admin/image-upload-dropzone.tsx", import.meta.url), "utf8")
const previewDialogSource = readFileSync(new URL("../components/admin/products/product-image-preview-dialog.tsx", import.meta.url), "utf8")

test("image upload validation matches the backend-safe formats and size", () => {
  assert.equal(validateImageFile({ type: "image/jpeg", size: 10 * 1024 * 1024 }), null)
  assert.equal(validateImageFile({ type: "image/svg+xml", size: 1 }), "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP")
  assert.equal(validateImageFile({ type: "image/png", size: 10 * 1024 * 1024 + 1 }), "图片大小不能超过 10MB")
  assert.equal(ALLOWED_IMAGE_ACCEPT.includes(".svg"), false)
})

test("QR upload validation only allows PNG/JPG/JPEG/WebP", () => {
  assert.equal(validateQrImageFile({ type: "image/png", size: 1 }), null)
  assert.equal(validateQrImageFile({ type: "image/jpeg", size: 1 }), null)
  assert.equal(validateQrImageFile({ type: "image/webp", size: 1 }), null)
  assert.equal(validateQrImageFile({ type: "image/gif", size: 1 }), "二维码仅支持 JPG/PNG/WebP 格式")
  assert.equal(QR_ALLOWED_IMAGE_ACCEPT.includes(".bmp"), false)
})

test("avatar upload validation is limited to JPG/PNG/WebP and 5MB", () => {
  assert.equal(getAvatarFileValidationError({ type: "image/jpeg", size: MAX_AVATAR_SIZE_BYTES }), null)
  assert.equal(getAvatarFileValidationError({ type: "image/gif", size: 1 }), "format")
  assert.equal(getAvatarFileValidationError({ type: "image/png", size: MAX_AVATAR_SIZE_BYTES + 1 }), "size")
  assert.equal(AVATAR_ALLOWED_IMAGE_ACCEPT.includes(".gif"), false)
})

test("admin product cover preview uses the same square contain stage as storefront imagery", () => {
  assert.match(dropzoneSource, /product-image-stage product-image-stage--upload/)
  assert.match(dropzoneSource, /className="product-image-stage-image h-full w-full"/)
  assert.doesNotMatch(dropzoneSource, /object-cover/)
  assert.match(dropzoneSource, /ALLOWED_IMAGE_ACCEPT/)
  assert.match(dropzoneSource, /validateImageFile\(file\)/)
  assert.match(previewDialogSource, /sizes="40px" className="product-image-stage-image"/)
  assert.doesNotMatch(previewDialogSource, /sizes="40px"[^\n]*object-cover/)
})
