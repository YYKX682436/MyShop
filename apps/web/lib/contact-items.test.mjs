import assert from "node:assert/strict"
import { test } from "node:test"

import {
  getContactItemAction,
  getDisplayContactItems,
  hasContactMethods,
  normalizeContactItems,
  parseContactItems,
  parseContactItemsSafely,
  serializeContactItems,
} from "./contact-items.ts"

test("contact items accepts zero, one, and three ordered entries", () => {
  assert.deepEqual(parseContactItems("[]"), [])
  assert.equal(parseContactItems('[{"name":"邮箱","url":"https://example.com"}]').length, 1)
  assert.deepEqual(
    parseContactItems('[{"name":"A","url":"https://a.example"},{"name":"B","qr_url":"/api/uploads/b.png"},{"name":"C","url":"http://c.example"}]')
      .map(item => item.name),
    ["A", "B", "C"],
  )
})

test("contact item validation rejects four entries, empty names, and missing destinations", () => {
  assert.throws(() => normalizeContactItems([
    { name: "1", url: "https://1.example" },
    { name: "2", url: "https://2.example" },
    { name: "3", url: "https://3.example" },
    { name: "4", url: "https://4.example" },
  ]), /最多/)
  assert.throws(() => normalizeContactItems([{ name: "   ", url: "https://example.com" }]), /不能为空/)
  assert.throws(() => normalizeContactItems([{ name: "客服" }]), /链接或二维码/)
})

test("admin-safe contact parsing isolates malformed persisted configuration", () => {
  assert.deepEqual(parseContactItemsSafely("not-json"), { items: [], hasError: true })
  assert.deepEqual(parseContactItemsSafely('[{"name":"客服","url":"https://example.com"}]'), {
    items: [{ name: "客服", url: "https://example.com" }],
    hasError: false,
  })
})

test("contact item validation allows https and uploaded QR paths but rejects javascript and data URLs", () => {
  assert.deepEqual(
    normalizeContactItems([{ name: " 客服 ", url: " https://example.com/support ", qr_url: " /api/uploads/qr.webp " }]),
    [{ name: "客服", url: "https://example.com/support", qr_url: "/api/uploads/qr.webp" }],
  )
  assert.throws(() => normalizeContactItems([{ name: "客服", url: "javascript:alert(1)" }]), /http\/https/)
  assert.throws(() => normalizeContactItems([{ name: "客服", qr_url: "data:image/png;base64,abc" }]), /二维码地址/)
})

test("serialization strips blank optional fields and produces canonical JSON", () => {
  assert.equal(
    serializeContactItems([{ name: "  客服 ", url: "https://example.com", qr_url: " " }]),
    '[{"name":"客服","url":"https://example.com"}]',
  )
})

test("shared contact rendering preserves order and prioritizes QR behavior", () => {
  const items = [
    { name: "链接", url: "https://example.com" },
    { name: "二维码", qr_url: "/api/uploads/qr.webp" },
    { name: "二维码+链接", url: "https://example.com/chat", qr_url: "/api/uploads/chat.webp" },
  ]

  assert.equal(getContactItemAction(items[0]), "link")
  assert.equal(getContactItemAction(items[1]), "qr")
  assert.equal(getContactItemAction(items[2]), "qr")
  assert.deepEqual(getDisplayContactItems(items).map(item => item.name), ["链接", "二维码", "二维码+链接"])
})

test("shared contact rendering ignores empty destinations and reports availability", () => {
  const items = [
    { name: "空白", url: "", qr_url: "" },
    { name: "有效", url: "https://example.com" },
  ]

  assert.deepEqual(getDisplayContactItems(items).map(item => item.name), ["有效"])
  assert.equal(hasContactMethods(undefined, []), false)
  assert.equal(hasContactMethods("  ", items.slice(0, 1)), false)
  assert.equal(hasContactMethods(undefined, items), true)
  assert.equal(hasContactMethods("support@example.com", []), true)
})
