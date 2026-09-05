import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { test } from "node:test"

import {
  getPaymentQrValue,
  getRepayPaymentCarriers,
  shouldDirectProviderPayment,
} from "./payment-page.ts"

test("a mobile payment with pay_url goes directly to the provider", () => {
  assert.equal(shouldDirectProviderPayment({ isMobile: true, payUrl: " https://pay.example/checkout ", qrcodeUrl: "" }), true)
})

test("mobile WeChat does not get a special MyShop payment-page exception", () => {
  assert.equal(shouldDirectProviderPayment({ isMobile: true, payUrl: "https://pay.example/checkout", qrcodeUrl: "weixin://dl/business/?ticket=xxx" }), true)
})

test("desktop pay-only payments go directly to the provider", () => {
  assert.equal(shouldDirectProviderPayment({ isMobile: false, payUrl: "https://pay.example/checkout", qrcodeUrl: "" }), true)
})

test("desktop provider QR payments remain on MyShop for QR rendering", () => {
  assert.equal(shouldDirectProviderPayment({ isMobile: false, payUrl: "https://pay.example/checkout", qrcodeUrl: "weixin://dl/business/?ticket=xxx" }), false)
})

test("the pay page only renders a provider-supplied QR carrier", () => {
  assert.equal(getPaymentQrValue(""), "")
  assert.equal(getPaymentQrValue(" https://pay.example/submit.php?pid=demo&sign=abcdef "), "https://pay.example/submit.php?pid=demo&sign=abcdef")
  assert.equal(getPaymentQrValue("weixin://dl/business/?ticket=xxx"), "weixin://dl/business/?ticket=xxx")
})

test("repay response carriers are immediately normalized for rendering", () => {
  assert.deepEqual(
    getRepayPaymentCarriers({ qrcode_url: " ", payment_url: " https://pay.example/submit.php?trade_no=demo ", pay_url: " " }),
    { qrcodeUrl: "", payUrl: "https://pay.example/submit.php?trade_no=demo" },
  )
  assert.deepEqual(
    getRepayPaymentCarriers({ qrcode_url: " weixin://dl/business/?ticket=xxx ", payment_url: " weixin://dl/business/?ticket=xxx ", pay_url: " https://pay.example/checkout " }),
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
  assert.match(page, /window\.location\.href = payUrl/)
  assert.match(page, /shouldDirectProviderPayment\(/)
  assert.match(page, /router\.replace\(`\/order\/query\?orderNo=/)
  assert.match(page, /paymentReturn=1/)
  assert.match(page, /paymentPageAvailable/)
  assert.doesNotMatch(page, /myshop_payment_page_redirected/)
  assert.doesNotMatch(page, /returnedFromGateway/)
  assert.doesNotMatch(page, /isWechatMobile/)
  assert.doesNotMatch(page, /qrcodeUrl \? <QRCodeSVG value=\{qrcodeUrl\}/)
  assert.doesNotMatch(page, /getPaymentQrValue\(qrcodeUrl, payUrl\)/)
})

test("checkout forwards only an explicit QR carrier to the pay page", () => {
  const checkout = readFileSync(new URL("../app/(store)/product/[id]/product-actions.tsx", import.meta.url), "utf8")

  assert.match(checkout, /const qr = result\.payment\.qrcode_url \|\| \"\"/)
  assert.doesNotMatch(checkout, /qrcode_url \|\| result\.payment\.payment_url/)
})

test("checkout opens a pay-only gateway page directly", () => {
  const checkout = readFileSync(new URL("../app/(store)/product/[id]/product-actions.tsx", import.meta.url), "utf8")

  assert.match(checkout, /shouldDirectProviderPayment\(/)
  assert.match(checkout, /window\.location\.href = payUrlH5/)
  assert.doesNotMatch(checkout, /isWechatChannel/)
  assert.doesNotMatch(checkout, /myshop_payment_page_redirected/)
})

test("payment return enables only the order-query confirmation flow", () => {
  const queryPage = readFileSync(new URL("../app/(store)/order/query/page.tsx", import.meta.url), "utf8")

  assert.match(queryPage, /paymentReturn/)
  assert.match(queryPage, /setTimeout|RETURN_POLL_INTERVAL/)
  assert.match(queryPage, /RETURN_POLL_MAX_MS/)
  assert.match(queryPage, /orderApi\.deliver\(/)
  assert.doesNotMatch(queryPage, /setInterval\(/)
})
