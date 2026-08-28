"use client"

import { useState, useRef, useEffect } from "react"
import { useRouter } from "next/navigation"
import { ShoppingBag, Mail, CreditCard, Lock } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useCart } from "@/lib/context"
import { orderApi, paymentApi, requestOrThrow, getApiErrorMessage, saveOrderLookupToken } from "@/services/api"
import { validateEmail, getCurrencySymbol, detectPaymentDevice, isMobileDevice } from "@/lib/utils"
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency"
import { getPaymentTypeForChannel, isWechatChannel } from "@/lib/payment-channel"
import { PaymentSelector } from "@/components/shared/payment-selector"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { setTurnstileHeaders } from "@/services/api"
import type { PaymentChannelItem } from "@/types"
import { validateCustomerInputs } from "@/lib/customer-inputs"

export default function CheckoutPage() {
  const { t } = useLocale()
  const router = useRouter()
  const { items, totalAmount, refreshCart, updateItem } = useCart()

  const [email, setEmail] = useState("")
  const [customerInputs, setCustomerInputs] = useState<Record<string, Record<string, string>>>({})
  const [channels, setChannels] = useState<PaymentChannelItem[]>([])
  const [selectedPayment, setSelectedPayment] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const emailInputRef = useRef<HTMLInputElement>(null)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  useEffect(() => {
    setCustomerInputs(current => {
      const next = { ...current }
      for (const item of items) {
        if (!(item.id in next)) {
          next[item.id] = Object.fromEntries(item.customer_input_fields.map(field => [
            field.id,
            item.customer_inputs.find(input => input.id === field.id)?.value ?? "",
          ]))
        }
      }
      return next
    })
  }, [items])

  // Fetch payment channels on mount
  useEffect(() => {
    let cancelled = false
    async function fetchChannels() {
      try {
        const chs = await requestOrThrow(() => paymentApi.getChannels())
        if (!cancelled) {
          const enabled = chs.filter(c => c.is_enabled)
          setChannels(enabled)
          if (enabled.length > 0) setSelectedPayment(enabled[0].channel_code)
        }
      } catch {
        if (!cancelled) {
          setChannels([])
        }
      }
    }
    fetchChannels()
    return () => { cancelled = true }
  }, [])

  const handleConfirmOrder = async () => {
    const normalizedInputsByItem: Record<string, { id: string; value: string }[]> = {}
    for (const item of items) {
      const drafts = customerInputs[item.id] ?? {}
      const validation = validateCustomerInputs(item.customer_input_fields, drafts)
      if (!validation.ok) {
        toast.error(validation.message)
        return
      }
      normalizedInputsByItem[item.id] = validation.values
    }
    if (!email.trim()) {
      toast.error(t("product.emailRequired"))
      emailInputRef.current?.focus()
      return
    }
    if (!validateEmail(email)) {
      toast.error(t("product.emailInvalid"))
      emailInputRef.current?.focus()
      return
    }
    if (!selectedPayment) {
      toast.error(t("product.paymentMethod"))
      return
    }

    setSubmitting(true)
    const intent = getOrCreateIdempotencyKey("myshop:checkout:idempotency", {
      items: items.map(item => ({
        product_id: item.product_id,
        spec_id: item.spec_id,
        quantity: item.quantity,
        customer_inputs: normalizedInputsByItem[item.id],
      })),
      email: email.trim().toLowerCase(),
      payment_method: selectedPayment,
    })
    try {
      await Promise.all(items.map(item => updateItem(
        item.id,
        item.quantity,
        normalizedInputsByItem[item.id],
      )))
      setTurnstileHeaders(turnstileToken)
      const device = detectPaymentDevice()
      const result = await requestOrThrow(() => orderApi.createFromCart({
          email,
          payment_method: selectedPayment,
          idempotency_key: intent.key,
          idempotency_fingerprint: intent.fingerprint,
          device,
        }))
      saveOrderLookupToken(result.payment.order_id, result.lookup_token)
      clearIdempotencyKey("myshop:checkout:idempotency", intent.key)
      await refreshCart()
      toast.success(t("checkout.processingOrder"))
      const payUrlH5 = result.payment.pay_url || ""
      const qr = result.payment.qrcode_url || result.payment.payment_url || ""
      const paymentType = getPaymentTypeForChannel(channels, selectedPayment)
      let payUrl = `/pay/${result.payment.order_id}?method=${encodeURIComponent(paymentType || "")}`
      if (qr) payUrl += `&qr=${encodeURIComponent(qr)}`
      if (payUrlH5) payUrl += `&payurl=${encodeURIComponent(payUrlH5)}`
      // 移动端非微信：直接跳转网关支付页，避免中间经过 pay 页面的延迟
      // 导致支付宝 H5 session token 过期（"会话超时"）
      // 微信支付的 jspay 走 JSAPI（需微信浏览器），普通浏览器不能跳转，只能到 pay 页展示二维码
      const isWechat = isWechatChannel(channels, selectedPayment)
      if (isMobileDevice() && payUrlH5 && !isWechat) {
        sessionStorage.setItem(`pay_redirected_${result.payment.order_id}`, "1")
        window.location.href = payUrlH5
        return
      }
      router.push(payUrl)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center gap-3">
        <ShoppingBag className="h-6 w-6 text-primary" />
        <h1 className="text-2xl font-bold text-foreground">{t("checkout.title")}</h1>
      </div>

      <div className="space-y-6">
        {/* Order summary */}
        <div className="rounded-lg border border-border bg-background p-6">
          <h2 className="mb-4 text-base font-semibold text-foreground">{t("checkout.summary")}</h2>
          <div className="space-y-3">
            {items.map((item) => (
              <div key={item.id} className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  {item.product_title}
                  {item.spec_name ? ` (${item.spec_name})` : ""}
                  {" x"}{item.quantity}
                </span>
                <span className="font-medium text-foreground">{getCurrencySymbol(item.currency)}{item.subtotal.toFixed(2)}</span>
              </div>
            ))}
            <div className="flex items-center justify-between border-t border-border pt-3">
              <span className="text-base font-medium text-foreground">{t("checkout.totalAmount")}</span>
              <span className="text-2xl font-bold text-primary">
                {getCurrencySymbol(items[0]?.currency)}{totalAmount.toFixed(2)}
              </span>
            </div>
          </div>
        </div>

        {/* Email */}
        {items.some(item => item.customer_input_fields.length > 0) && (
          <div className="rounded-lg border border-border bg-background p-6">
            <div className="mb-4 flex items-center gap-2">
              <ShoppingBag className="h-5 w-5 text-primary" />
              <h2 className="text-base font-semibold text-foreground">购买客户信息</h2>
            </div>
            <div className="space-y-4">
              {items.filter(item => item.customer_input_fields.length > 0).map(item => (
                <div key={item.id} className="space-y-3">
                  <p className="text-sm font-medium text-foreground">{item.product_title}{item.spec_name ? ` · ${item.spec_name}` : ""}</p>
                  {item.customer_input_fields.map(field => (
                    <label key={field.id} className="block text-sm font-medium text-foreground">
                      {field.label} <span className="text-destructive">*</span>
                      <input
                        type="text"
                        value={customerInputs[item.id]?.[field.id] ?? ""}
                        onChange={(event) => setCustomerInputs(current => ({
                          ...current,
                          [item.id]: { ...(current[item.id] ?? {}), [field.id]: event.target.value },
                        }))}
                        placeholder={`请输入${field.label}`}
                        className="mt-1.5 h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                      />
                    </label>
                  ))}
                </div>
              ))}
            </div>
            <p className="mt-3 text-xs text-muted-foreground">该信息会随订单提供给商家，仅用于本次发货。</p>
          </div>
        )}

        {/* Email */}
        <div className="rounded-lg border border-border bg-background p-6">
          <div className="mb-4 flex items-center gap-2">
            <Mail className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold text-foreground">
              {t("product.email")}
            </h2>
          </div>
          <input
            ref={emailInputRef}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t("product.emailPlaceholder")}
            className="mb-2 w-full rounded-lg border border-input bg-background px-4 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
          />
          <p className="text-xs text-muted-foreground">
            {t("product.emailFullHint")}
          </p>
        </div>

        {/* Payment method */}
        <div className="rounded-lg border border-border bg-background p-6">
          <div className="mb-4 flex items-center gap-2">
            <CreditCard className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold text-foreground">
              {t("product.paymentMethod")}
            </h2>
          </div>
          <PaymentSelector
            channels={channels}
            selected={selectedPayment}
            onSelect={setSelectedPayment}
          />
        </div>

        {/* Security notice */}
        <div className="flex items-start gap-3 rounded-lg border border-border bg-muted/30 p-4">
          <Lock className="h-5 w-5 shrink-0 text-muted-foreground" />
          <div className="text-xs text-muted-foreground">
            <p className="mb-1 font-medium text-foreground">{t("checkout.securePayment")}</p>
            <p>{t("checkout.securePaymentDesc")}</p>
          </div>
        </div>

        <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-4" />

        {/* Confirm button */}
        <button
          onClick={handleConfirmOrder}
          disabled={submitting || items.length === 0}
          className="scheme-glow w-full rounded-lg bg-primary py-3.5 text-base font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:pointer-events-none disabled:opacity-50"
        >
          {submitting ? (
            <span className="inline-flex items-center gap-2">
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              {t("checkout.processingOrder")}
            </span>
          ) : (
            <>{t("checkout.confirmOrder")} {getCurrencySymbol(items[0]?.currency)}{totalAmount.toFixed(2)}</>
          )}
        </button>
      </div>
    </div>
  )
}
