import assert from "node:assert/strict"
import test from "node:test"
import { resolvePaymentAction } from "./payment-action.ts"

const qrCode = { type: "qr_code", value: "weixin://qr/demo" }
const httpQrCode = { type: "qr_code", value: "https://pay.example/checkout" }
const redirect = { type: "redirect", url: "https://pay.example/checkout" }
const wechatClient = { type: "open_in_client", url: "https://pay.example/open", client: "wechat" }
const alipayClient = { type: "open_in_client", url: "https://pay.example/alipay", client: "alipay" }
const deepLink = { type: "deep_link", url: "weixin://pay/demo", client: "wechat" }
const formPost = { type: "form_post", url: "https://pay.example/form", fields: { token: "signed", amount: "10.00" } }

const desktop = { isMobile: false, isWechatBrowser: false, isAlipayBrowser: false }
const mobile = { isMobile: true, isWechatBrowser: false, isAlipayBrowser: false }

test("qr_code always renders the exact provider value", () => {
  assert.deepEqual(resolvePaymentAction(qrCode, desktop), {
    kind: "qr_code", value: "weixin://qr/demo",
  })
})

test("an HTTP qrcode remains a raw QR payload instead of becoming a redirect", () => {
  assert.deepEqual(resolvePaymentAction(httpQrCode, desktop), {
    kind: "qr_code", value: "https://pay.example/checkout",
  })
})

test("redirect remains an explicit redirect on every device", () => {
  assert.deepEqual(resolvePaymentAction(redirect, mobile), {
    kind: "redirect", url: "https://pay.example/checkout",
  })
})

test("desktop open_in_client renders a QR code", () => {
  assert.equal(resolvePaymentAction(wechatClient, desktop).mode, "qr_code")
  assert.equal(resolvePaymentAction(wechatClient, { ...desktop, isWechatBrowser: true }).mode, "qr_code")
})

test("mobile WeChat open_in_client opens the URL directly", () => {
  assert.equal(resolvePaymentAction(wechatClient, { ...mobile, isWechatBrowser: true }).mode, "direct")
})

test("mobile non-WeChat open_in_client only offers copy", () => {
  const decision = resolvePaymentAction(wechatClient, mobile)
  assert.equal(decision.mode, "copy")
  assert.equal(decision.kind, "open_in_client")
})

test("mobile Alipay browser opens an Alipay action directly", () => {
  const decision = resolvePaymentAction(alipayClient, { ...mobile, isAlipayBrowser: true })
  assert.equal(decision.mode, "direct")
  assert.equal(decision.client, "alipay")
})

test("mobile ordinary browser keeps Alipay action as copy", () => {
  const decision = resolvePaymentAction(alipayClient, mobile)
  assert.equal(decision.mode, "copy")
  assert.equal(decision.client, "alipay")
})

test("deep_link opens on mobile and becomes a QR code on desktop", () => {
  assert.equal(resolvePaymentAction(deepLink, mobile).mode, "open")
  assert.equal(resolvePaymentAction(deepLink, desktop).mode, "qr_code")
})

test("form_post preserves its fields for the renderer", () => {
  assert.deepEqual(resolvePaymentAction(formPost, desktop), {
    kind: "form_post", url: "https://pay.example/form", fields: { token: "signed", amount: "10.00" },
  })
})
