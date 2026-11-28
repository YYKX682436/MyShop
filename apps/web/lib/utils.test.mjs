import assert from "node:assert/strict"
import test from "node:test"

import { formatYuan } from "./utils.ts"

test("formatYuan always renders the RMB symbol and two decimals", () => {
  assert.equal(formatYuan(0), "¥0.00")
  assert.equal(formatYuan(12), "¥12.00")
  assert.equal(formatYuan(12.3), "¥12.30")
  assert.equal(formatYuan(12.345), "¥12.35")
})

test("App desktop UA remains a physical payment phone without changing WeChat/Alipay priority", async () => {
  const { detectPaymentDevice } = await import('./utils.ts')
  const previousWindow = globalThis.window
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  globalThis.window = {}
  try {
    for (const [ua, expected] of [
      ['Mozilla/5.0 (Linux; Android 15) Chrome/148.0.7778.215 Mobile AikayunShop/1.0.1', 'mobile'],
      ['Mozilla/5.0 (X11; Linux x86_64) Chrome/148.0.7778.215 AikayunShop/1.0.1', 'mobile'],
      ['Mozilla/5.0 (X11; Linux x86_64) Chrome/148.0.7778.215', 'pc'],
      ['Android Mobile MicroMessenger AikayunShop/1.0.1', 'wechat'],
      ['Android AlipayClient AikayunShop/1.0.1', 'alipay'],
      ['iPhone Mobile', 'mobile'],
    ]) {
      Object.defineProperty(globalThis, 'navigator', { configurable:true, value:{userAgent:ua} })
      assert.equal(detectPaymentDevice(),expected,ua)
    }
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
    if (previousNavigator) Object.defineProperty(globalThis,'navigator',previousNavigator)
    else delete globalThis.navigator
  }
})
