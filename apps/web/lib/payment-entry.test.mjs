import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"
import {
  buildOrderPageHref,
  clearPaymentAction,
  paymentActionStorageKey,
  readPaymentAction,
  resolvePaymentEntry,
  savePaymentAction,
  shouldSeedPaymentAction,
} from "./payment-entry.ts"

const redirect = { type: "redirect", url: "https://pay.example/checkout" }
const qrCode = { type: "qr_code", value: "weixin://qr/demo" }
const wechatClient = { type: "open_in_client", url: "https://pay.example/open", client: "wechat" }
const deepLink = { type: "deep_link", url: "weixin://pay/demo", client: "wechat" }
const formPost = { type: "form_post", url: "https://pay.example/form", fields: { token: "signed", amount: "10.00" } }

const desktop = { isMobile: false, isWechatBrowser: false, isAlipayBrowser: false }
const mobile = { isMobile: true, isWechatBrowser: false, isAlipayBrowser: false }
const productActionsSource = readFileSync(new URL("../app/(store)/product/[id]/product-actions.tsx", import.meta.url), "utf8")
const paymentPageSource = readFileSync(new URL("../app/(store)/order/[orderId]/page.tsx", import.meta.url), "utf8")
const paymentActionRendererSource = readFileSync(new URL("../components/shared/payment-action-renderer.tsx", import.meta.url), "utf8")

test("redirect is an immediate entry and never needs a separate payment page", () => {
  const entry = resolvePaymentEntry(redirect, desktop)
  assert.equal(entry.mode, "execute")
  assert.equal(entry.decision.kind, "redirect")
})

test("form_post is submitted immediately and never needs a separate payment page", () => {
  const entry = resolvePaymentEntry(formPost, mobile)
  assert.equal(entry.mode, "execute")
  assert.equal(entry.decision.kind, "form_post")
  assert.equal(shouldSeedPaymentAction(entry), false)
})

test("qr_code enters the order lifecycle page with an initial action seed", () => {
  const entry = resolvePaymentEntry(qrCode, desktop)
  assert.equal(entry.mode, "payment_page")
  assert.equal(entry.decision.kind, "qr_code")
  assert.equal(buildOrderPageHref("order-1"), "/order/order-1")
})

test("desktop open_in_client enters the order lifecycle page for the client QR code", () => {
  const entry = resolvePaymentEntry(wechatClient, desktop)
  assert.equal(entry.mode, "payment_page")
  assert.equal(entry.decision.kind, "open_in_client")
  assert.equal(entry.decision.mode, "qr_code")
})

test("mobile target-client open_in_client executes directly", () => {
  const entry = resolvePaymentEntry(wechatClient, { ...mobile, isWechatBrowser: true })
  assert.equal(entry.mode, "execute")
  assert.equal(entry.decision.mode, "direct")
})

test("mobile ordinary browser open_in_client enters the order lifecycle page for copy UI", () => {
  const entry = resolvePaymentEntry(wechatClient, mobile)
  assert.equal(entry.mode, "payment_page")
  assert.equal(entry.decision.mode, "copy")
})

test("mobile deep_link executes directly while desktop deep_link enters the order lifecycle page", () => {
  assert.equal(resolvePaymentEntry(deepLink, mobile).mode, "execute")
  assert.equal(resolvePaymentEntry(deepLink, desktop).mode, "payment_page")
})

test("storage keeps only the public action under the order-bound key", () => {
  const previousWindow = globalThis.window
  const values = new Map()
  globalThis.window = {
    sessionStorage: {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
      removeItem: (key) => values.delete(key),
    },
  }
  try {
    savePaymentAction("order-2", qrCode)
    assert.equal(values.get(paymentActionStorageKey("order-2")), JSON.stringify(qrCode))
    assert.deepEqual(readPaymentAction("order-2"), qrCode)
    savePaymentAction("order-form", formPost)
    assert.equal(values.get(paymentActionStorageKey("order-form")), undefined)
    clearPaymentAction("order-2")
    assert.equal(readPaymentAction("order-2"), null)
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})

test("ablation guard: unconditional payment-page routing would reject a redirect entry", () => {
  const unconditionalPaymentPageEntry = () => "payment_page"
  assert.equal(unconditionalPaymentPageEntry(), "payment_page")
  assert.equal(resolvePaymentEntry(redirect, desktop).mode, "execute")
})

test("product actions consume create response actions instead of unconditionally pushing the lifecycle page", () => {
  assert.match(productActionsSource, /result\.payment\.next_action/)
  assert.match(productActionsSource, /resolvePaymentEntry\(/)
  assert.match(productActionsSource, /executePaymentAction\(/)
  assert.doesNotMatch(productActionsSource, /buildPaymentPageHref/)
})

test("missing create response action fails on the product page instead of opening a payment page", () => {
  const noActionStart = productActionsSource.indexOf("if (!action) {")
  const entryStart = productActionsSource.indexOf("const entry = resolvePaymentEntry", noActionStart)
  const noActionBranch = productActionsSource.slice(noActionStart, entryStart)

  assert.notEqual(noActionStart, -1)
  assert.notEqual(entryStart, -1)
  assert.match(noActionBranch, /clearPaymentAction\(result\.payment\.order_id\)/)
  assert.match(noActionBranch, /toast\.error\(t\("payment\.loadFailed"\)\)/)
  assert.match(noActionBranch, /return/)
  assert.doesNotMatch(noActionBranch, /router\.push|buildPaymentPageHref|savePaymentAction/)
})

test("checkout form_post response executes directly without a payment-page seed", () => {
  const createResponse = {
    payment: {
      next_action: {
        type: "form_post",
        url: "https://pay.example.com/submit.php",
        fields: { pid: "demo", sign: "signed" },
      },
    },
  }
  const entry = resolvePaymentEntry(createResponse.payment.next_action, desktop)

  assert.equal(entry.mode, "execute")
  assert.equal(entry.decision.kind, "form_post")
  assert.equal(shouldSeedPaymentAction(entry), false)
  assert.match(productActionsSource, /if \(entry\.mode === "execute"/)
  assert.match(productActionsSource, /executePaymentAction\(entry\.decision\)/)
  assert.match(productActionsSource, /if \(shouldSeedPaymentAction\(entry\)\)/)
  const executeBranchStart = productActionsSource.indexOf('if (entry.mode === "execute"')
  const seedBranchStart = productActionsSource.indexOf("if (shouldSeedPaymentAction(entry))")
  const executeBranch = productActionsSource.slice(executeBranchStart, seedBranchStart)
  assert.doesNotMatch(executeBranch, /router\.push/)
  assert.match(executeBranch, /executePaymentAction\(entry\.decision\)/)
  assert.match(executeBranch, /return/)
})

test("lifecycle navigation is reachable only after a concrete payment-page action is seeded", () => {
  const seedBranchStart = productActionsSource.indexOf("if (shouldSeedPaymentAction(entry))")
  const seedBranchEnd = productActionsSource.indexOf("clearPaymentAction(result.payment.order_id)", seedBranchStart)
  const seedBranch = productActionsSource.slice(seedBranchStart, seedBranchEnd)
  const beforeSeed = productActionsSource.slice(productActionsSource.indexOf("const action = result.payment.next_action"), seedBranchStart)

  assert.notEqual(seedBranchStart, -1)
  assert.match(seedBranch, /savePaymentAction\(result\.payment\.order_id, action\)/)
  assert.match(seedBranch, /router\.push\(paymentHref\)/)
  assert.match(seedBranch, /return/)
  assert.doesNotMatch(beforeSeed, /router\.push\(paymentHref\)/)
  assert.equal((productActionsSource.match(/router\.push\(paymentHref\)/g) || []).length, 1)
})

test("direct-payment fallback retries the original browser action without linking to a separate payment page", () => {
  assert.match(productActionsSource, /paymentFallbackAction/)
  assert.match(productActionsSource, /setPaymentFallbackAction\(entry\.decision\)/)
  assert.match(productActionsSource, /const retryPaymentExecution = \(\) =>/)
  assert.match(productActionsSource, /executePaymentAction\(paymentFallbackAction\)/)
  assert.match(productActionsSource, /onClick=\{retryPaymentExecution\}/)
  assert.doesNotMatch(productActionsSource, /paymentFallbackHref/)
})

test("order lifecycle page uses explicit load state and does not hide status errors", () => {
  assert.match(paymentPageSource, /readPaymentAction\(/)
  assert.match(paymentPageSource, /applyPaymentStatusError\(/)
  assert.doesNotMatch(paymentPageSource, /statusLoaded/)
  assert.doesNotMatch(paymentPageSource, /catch\(\(\) => undefined\)/)
})

test("order lifecycle page delegates QR surface ownership to the action renderer", () => {
  const rendererIndex = paymentPageSource.indexOf("<PaymentActionRenderer")
  assert.notEqual(rendererIndex, -1)
  const parentMarkup = paymentPageSource.slice(Math.max(0, rendererIndex - 300), rendererIndex)

  assert.doesNotMatch(parentMarkup, /min-h-52|w-52|bg-white/)
})

test("only QR decisions render a QR surface", () => {
  assert.match(paymentActionRendererSource, /function QrSurface[\s\S]*bg-white/)
  assert.equal((paymentActionRendererSource.match(/<QrSurface>/g) || []).length, 3)

  const qrBranch = paymentActionRendererSource.slice(
    paymentActionRendererSource.indexOf('if (decision.kind === "qr_code")'),
    paymentActionRendererSource.indexOf('if (decision.kind === "redirect")'),
  )
  assert.match(qrBranch, /<QrSurface>[\s\S]*QRCodeSVG/)

  const openClientBranch = paymentActionRendererSource.slice(
    paymentActionRendererSource.indexOf('if (decision.kind === "open_in_client")'),
    paymentActionRendererSource.indexOf('if (decision.kind === "deep_link")'),
  )
  assert.match(openClientBranch, /if \(decision\.mode === "qr_code"\)[\s\S]*<QrSurface>[\s\S]*QRCodeSVG/)

  const deepLinkBranch = paymentActionRendererSource.slice(
    paymentActionRendererSource.indexOf('if (decision.kind === "deep_link")'),
    paymentActionRendererSource.lastIndexOf('return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">'),
  )
  assert.match(deepLinkBranch, /if \(decision\.mode === "qr_code"\)[\s\S]*<QrSurface>[\s\S]*QRCodeSVG/)

  const loadAndErrorBranches = paymentActionRendererSource.slice(
    paymentActionRendererSource.indexOf("if (!action)"),
    paymentActionRendererSource.indexOf('if (decision.kind === "qr_code")'),
  )
  assert.doesNotMatch(loadAndErrorBranches, /<QrSurface>|min-h-52|w-52|bg-white/)

  const redirectBranch = paymentActionRendererSource.slice(
    paymentActionRendererSource.indexOf('if (decision.kind === "redirect")'),
    paymentActionRendererSource.indexOf('if (decision.kind === "open_in_client")'),
  )
  assert.doesNotMatch(redirectBranch, /<QrSurface>|min-h-52|w-52|bg-white/)

  const formPostBranch = paymentActionRendererSource.slice(
    paymentActionRendererSource.lastIndexOf('return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">'),
  )
  assert.doesNotMatch(formPostBranch, /<QrSurface>|min-h-52|w-52|bg-white/)
})

test("an action without a decision uses an explicit retry error instead of a permanent spinner", () => {
  const unresolvedBranchStart = paymentActionRendererSource.indexOf("if (!decision) {")
  const qrBranchStart = paymentActionRendererSource.indexOf('if (decision.kind === "qr_code")')
  const unresolvedBranch = paymentActionRendererSource.slice(unresolvedBranchStart, qrBranchStart)

  assert.notEqual(unresolvedBranchStart, -1)
  assert.match(paymentActionRendererSource, /const waitingForEnvironment = Boolean\(action && needsEnvironment && !environment\)/)
  assert.match(paymentActionRendererSource, /if \(waitingForEnvironment\)[\s\S]*Loader2/)
  assert.match(unresolvedBranch, /role="alert"/)
  assert.match(unresolvedBranch, /statusError \|\| t\("payment\.loadFailed"\)/)
  assert.match(unresolvedBranch, /retryStatusAction \|\| retryAction/)
  assert.doesNotMatch(unresolvedBranch, /Loader2|animate-spin|QrSurface/)
})
