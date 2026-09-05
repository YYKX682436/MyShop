import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { test } from "node:test"

import {
  getPaymentQrValue,
  getRepayPaymentCarriers,
} from "./payment-page.ts"

test("the pay page treats a persisted payment carrier as a QR value fallback", () => {
  assert.equal(getPaymentQrValue("", " https://pay.example/checkout "), "https://pay.example/checkout")
  assert.equal(getPaymentQrValue("", "https://pay.example/submit.php?pid=demo&sign=abcdef"), "https://pay.example/submit.php?pid=demo&sign=abcdef")
  assert.equal(getPaymentQrValue("weixin://dl/business/?ticket=xxx", "https://pay.example/checkout"), "weixin://dl/business/?ticket=xxx")
})

test("repay response carriers are immediately normalized for rendering", () => {
  assert.deepEqual(
    getRepayPaymentCarriers({ qrcode_url: " ", payment_url: " weixin://dl/business/?ticket=xxx ", pay_url: " https://pay.example/checkout " }),
    { qrcodeUrl: "weixin://dl/business/?ticket=xxx", payUrl: "https://pay.example/checkout" },
  )
})

test("the pay page has a bounded no-QR state and a manual repay action", () => {
  const page = readFileSync(new URL("../app/(store)/pay/[orderId]/page.tsx", import.meta.url), "utf8")

  assert.match(page, /statusLoaded/)
  assert.match(page, /payment\.noQrCode/)
  assert.match(page, /payment\.retryQrCode/)
  assert.match(page, /orderApi\.repay\(/)
  assert.match(page, /QRCodeSVG value=\{qrcodeValue\}/)
  assert.doesNotMatch(page, /qrcodeUrl \? <QRCodeSVG value=\{qrcodeUrl\}/)
})
