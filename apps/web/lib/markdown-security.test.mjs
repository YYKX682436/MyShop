import assert from "node:assert/strict"
import test from "node:test"

import { sanitizeMarkdownUrl } from "./markdown-security.ts"

test("markdown allows same-origin uploads and explicit web protocols", () => {
  assert.equal(sanitizeMarkdownUrl("/api/uploads/manual.png", "image"), "/api/uploads/manual.png")
  assert.equal(sanitizeMarkdownUrl("https://cdn.example/manual.png", "image"), "https://cdn.example/manual.png")
  assert.equal(sanitizeMarkdownUrl("https://merchant.example/help", "link"), "https://merchant.example/help")
  assert.equal(sanitizeMarkdownUrl("/order/123", "link"), "/order/123")
  assert.equal(sanitizeMarkdownUrl("mailto:support@example.com", "link"), "mailto:support@example.com")
})

test("markdown rejects script, data, protocol-relative, and non-upload image URLs", () => {
  assert.equal(sanitizeMarkdownUrl("javascript:alert(1)", "link"), null)
  assert.equal(sanitizeMarkdownUrl("data:text/html,<script>alert(1)</script>", "image"), null)
  assert.equal(sanitizeMarkdownUrl("//evil.example/image.png", "image"), null)
  assert.equal(sanitizeMarkdownUrl("/\\evil.example", "link"), null)
  assert.equal(sanitizeMarkdownUrl("/admin/secrets", "image"), null)
  assert.equal(sanitizeMarkdownUrl("/api/uploads/../secrets", "image"), null)
  assert.equal(sanitizeMarkdownUrl("/api/uploads/%2e%2e/secrets", "image"), null)
  assert.equal(sanitizeMarkdownUrl("/api/uploads/", "image"), null)
  assert.equal(sanitizeMarkdownUrl("vbscript:msgbox(1)", "link"), null)
})
