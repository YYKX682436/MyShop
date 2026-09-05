import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { test } from "node:test"

import {
  getPaymentTypeForChannel,
  isWechatChannel,
} from "./payment-channel.ts"

const channel = (channel_code, type) => ({ channel_code, type, name: type })

test("an epay WeChat instance uses its type for mobile behavior", () => {
  const channels = [channel("wxpay", "wxpay")]

  assert.equal(getPaymentTypeForChannel(channels, "wxpay"), "wxpay")
  assert.equal(isWechatChannel(channels, "wxpay"), true)
})

test("an epay Alipay instance is not treated as WeChat", () => {
  const channels = [channel("epay-alipay-main", "alipay")]

  assert.equal(getPaymentTypeForChannel(channels, "epay-alipay-main"), "alipay")
  assert.equal(isWechatChannel(channels, "epay-alipay-main"), false)
})

test("the channel code cannot override the stored type", () => {
  const channels = [
    channel("wxpay-looking-code", "alipay"),
    channel("opaque-main", "wxpay"),
  ]

  assert.equal(isWechatChannel(channels, "wechat-looking-code"), false)
  assert.equal(isWechatChannel(channels, "opaque-main"), true)
})

test("the storefront selector keeps the existing SVG logos and optional display fields", () => {
  const selector = readFileSync(new URL("../components/shared/payment-selector.tsx", import.meta.url), "utf8")
  const icon = readFileSync(new URL("../components/shared/payment-icon.tsx", import.meta.url), "utf8")

  assert.match(selector, /PaymentIcon method=\{ch\.type\}/)
  assert.match(selector, /ch\.badge_text\?\.trim\(\)/)
  assert.match(selector, /ch\.payment_hint\?\.trim\(\)/)
  assert.match(icon, /ali\.svg/)
  assert.match(icon, /wechat\.svg/)
  assert.match(icon, /m === "wxpay"/)
})
