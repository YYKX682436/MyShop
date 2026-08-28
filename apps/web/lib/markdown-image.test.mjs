import assert from "node:assert/strict"
import test from "node:test"
import { createMarkdownImage, insertMarkdownImage } from "./markdown-image.ts"

test("markdown image insertion replaces the current selection and returns the cursor after the image", () => {
  const markdownImage = createMarkdownImage("商品图.png", "/api/uploads/item.png")
  const result = insertMarkdownImage("说明文字", markdownImage, { start: 2, end: 3 })

  assert.equal(result.value, `说明\n${markdownImage}\n字`)
  assert.equal(result.cursorPosition, `说明\n${markdownImage}\n`.length)
})

test("markdown image insertion falls back to the end when the saved selection is invalid", () => {
  const markdownImage = createMarkdownImage("item.png", "/api/uploads/item.png")
  const result = insertMarkdownImage("已有内容", markdownImage, { start: 99, end: 100 })

  assert.equal(result.value, `已有内容\n${markdownImage}\n`)
  assert.equal(result.cursorPosition, `已有内容\n${markdownImage}\n`.length)
})
