import assert from "node:assert/strict"
import { test } from "node:test"

import {
  getPaymentTypeForChannel,
  isWechatChannel,
} from "./payment-channel.ts"

const channel = (channel_code, payment_type) => ({ channel_code, payment_type })

test("an epay WeChat instance uses its payment_type for mobile behavior", () => {
  const channels = [channel("epay-wechat-main", "wechat")]

  assert.equal(getPaymentTypeForChannel(channels, "epay-wechat-main"), "wechat")
  assert.equal(isWechatChannel(channels, "epay-wechat-main"), true)
})

test("an epay Alipay instance is not treated as WeChat", () => {
  const channels = [channel("epay-alipay-main", "alipay")]

  assert.equal(getPaymentTypeForChannel(channels, "epay-alipay-main"), "alipay")
  assert.equal(isWechatChannel(channels, "epay-alipay-main"), false)
})

test("the channel code cannot override the stored payment_type", () => {
  const channels = [
    channel("wechat-looking-code", "alipay"),
    channel("opaque-main", "wechat"),
  ]

  assert.equal(isWechatChannel(channels, "wechat-looking-code"), false)
  assert.equal(isWechatChannel(channels, "opaque-main"), true)
})
