"use client"

import { useState, useMemo, useRef, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Zap, Minus, Plus, Package, TrendingUp } from "lucide-react"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { ApiError, orderApi, requestOrThrow, getApiErrorMessage, setTurnstileHeaders, saveOrderLookupToken } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { cn, validateEmail, formatYuan, detectPaymentDevice, isMobileDevice } from "@/lib/utils"
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency"
import { getPaymentTypeForChannel, isWechatChannel } from "@/lib/payment-channel"
import { PaymentSelector } from "@/components/shared/payment-selector"
import type { ProductDetail, ProductSpec, PaymentChannelItem } from "@/types"
import { validateCustomerInputs, validateCustomerInputValue } from "@/lib/customer-inputs"

interface ProductActionsProps {
  product: ProductDetail
  channels: PaymentChannelItem[]
}

export function ProductActions({ product, channels }: ProductActionsProps) {
  const { t } = useLocale()
  const { user } = useAuth()
  const router = useRouter()
  const emailInputRef = useRef<HTMLInputElement>(null)
  const customerInputRefs = useRef<Record<string, HTMLInputElement | null>>({})

  const enabledChannels = useMemo(() => channels.filter(c => c.is_enabled), [channels])
  const adminPickup = isAdminRole(user?.role)

  const [selectedSpec, setSelectedSpec] = useState<ProductSpec | null>(
    product.specs?.[0] || null
  )
  useEffect(() => {
    setSelectedSpec((current) => product.specs?.find((spec) => spec.id === current?.id) || product.specs?.[0] || null)
  }, [product])
  const [quantity, setQuantity] = useState(1)
  const [email, setEmail] = useState("")
  const [emailError, setEmailError] = useState("")
  const customerInputFields = product.customer_input_fields ?? []
  const [customerInputs, setCustomerInputs] = useState<Record<string, string>>(() =>
    Object.fromEntries(customerInputFields.map(field => [field.id, ""]))
  )
  const [customerInputErrors, setCustomerInputErrors] = useState<Record<string, string>>({})
  const [selectedPayment, setSelectedPayment] = useState(
    enabledChannels.length > 0 ? enabledChannels[0].channel_code : ""
  )
  const [submitting, setSubmitting] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const currentPrice = selectedSpec ? selectedSpec.price : product.price
  const totalPrice = currentPrice * quantity
  const unlimited = selectedSpec?.unlimited ?? product.unlimited ?? false
  const currentStock = selectedSpec?.stock_available ?? product.stock_available ?? 0
  const finiteStock = currentStock ?? 0
  const isOutOfStock = !unlimited && finiteStock === 0
  const deliveryType = product.delivery_type === "MANUAL" ? "manual" : "auto"

  const handleEmailChange = (value: string) => {
    setEmail(value)
    if (value && !validateEmail(value)) {
      setEmailError(t("product.emailInvalid"))
    } else {
      setEmailError("")
    }
  }

  const validateCustomerInputsForAction = () => {
    const result = validateCustomerInputs(customerInputFields, customerInputs)
    if (!result.ok) {
      setCustomerInputErrors(current => ({ ...current, [result.field ?? ""]: result.message }))
      if (result.field) customerInputRefs.current[result.field]?.focus()
      return null
    }
    setCustomerInputErrors({})
    return result.values
  }

  const handleBuyNow = async () => {
    const normalizedCustomerInputs = validateCustomerInputsForAction()
    if (!normalizedCustomerInputs) return
    if (!adminPickup && !email.trim()) {
      toast.error(t("product.emailRequired"))
      emailInputRef.current?.focus()
      emailInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" })
      return
    }
    if (!adminPickup && !validateEmail(email)) {
      toast.error(t("product.emailInvalid"))
      emailInputRef.current?.focus()
      return
    }
    if (!adminPickup && !selectedPayment) {
      toast.error(t("product.paymentMethod"))
      return
    }
    if (product.specs.length > 0 && !selectedSpec) return
    if (isOutOfStock) {
      toast.error(t("product.outOfStock"))
      return
    }

    setSubmitting(true)
    const intent = getOrCreateIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, {
      product_id: product.id,
      spec_id: selectedSpec?.id ?? null,
      quantity,
      customer_inputs: normalizedCustomerInputs,
      email: adminPickup ? undefined : email.trim().toLowerCase(),
      payment_method: adminPickup ? "ADMIN_PICKUP" : selectedPayment,
      expected_unit_price: Number(currentPrice.toFixed(2)),
    })
    try {
      setTurnstileHeaders(turnstileToken)
      const device = detectPaymentDevice()
      const result = await requestOrThrow(() => orderApi.create({
          product_id: product.id,
          spec_id: selectedSpec?.id ?? null,
          quantity,
          email: adminPickup ? undefined : email,
          customer_inputs: normalizedCustomerInputs,
          payment_method: adminPickup ? "ADMIN_PICKUP" : selectedPayment,
          expected_unit_price: Number(currentPrice.toFixed(2)),
          idempotency_key: intent.key,
          idempotency_fingerprint: intent.fingerprint,
          device,
        }))
      saveOrderLookupToken(result.payment.order_id, result.lookup_token)
      clearIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, intent.key)
      if (adminPickup || result.payment.admin_pickup || result.order.order_type === "ADMIN_PICKUP") {
        toast.success(t("payment.adminPickupSuccess"))
        router.push(`/order/query?orderId=${encodeURIComponent(result.payment.order_id)}`)
        return
      }
      toast.success(t("payment.redirectingToPay"))
      const payUrlH5 = result.payment.pay_url || ""
      const qr = result.payment.qrcode_url || result.payment.payment_url || ""
      const paymentType = getPaymentTypeForChannel(enabledChannels, selectedPayment)
      let payUrl = `/pay/${result.payment.order_id}?method=${encodeURIComponent(paymentType || "")}`
      if (qr) payUrl += `&qr=${encodeURIComponent(qr)}`
      if (payUrlH5) payUrl += `&payurl=${encodeURIComponent(payUrlH5)}`
      // 移动端非微信：直接跳转网关支付页，避免中间经过 pay 页面的延迟
      // 导致支付宝 H5 session token 过期（"会话超时"）
      // 微信支付的 jspay 走 JSAPI（需微信浏览器），普通浏览器不能跳转，只能到 pay 页展示二维码
      const isWechat = isWechatChannel(enabledChannels, selectedPayment)
      if (isMobileDevice() && payUrlH5 && !isWechat) {
        sessionStorage.setItem(`pay_redirected_${result.payment.order_id}`, "1")
        window.location.href = payUrlH5
        return
      }
      router.push(payUrl)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.code === 40007) {
        const current = err.params?.current_unit_price
        const suffix = current === undefined ? "" : `（当前价格：${formatYuan(Number(current))}）`
        toast.error(`${t("error.priceChanged")}${suffix}`)
        router.refresh()
      } else {
        toast.error(getApiErrorMessage(err, t))
      }
      handleTurnstileReset()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="lg:sticky lg:top-4 flex flex-col gap-4">
      {/* Title */}
      <div>
        <h1 className="text-xl font-bold text-foreground">
          {product.title}
        </h1>
      </div>

      {/* Price + Specs + Stock */}
      <div className="rounded-lg border border-border p-4 space-y-4">
        {/* Price row + delivery status */}
        <div className="flex flex-wrap items-baseline justify-between gap-y-2">
          <div className="flex items-baseline gap-3">
            <div className="flex items-baseline gap-0.5">
              <span className="text-2xl font-extrabold text-primary">
                {formatYuan(currentPrice)}
              </span>
            </div>
          </div>

          {/* Delivery status indicator */}
          <div className={cn(
            "inline-flex items-center gap-1.5 rounded-full border px-3 py-1",
            deliveryType === "auto"
              ? "border-emerald-200 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-900/20"
              : "border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/20"
          )}>
            <span className={cn(
              "relative inline-flex h-2 w-2 rounded-full",
              deliveryType === "auto" ? "bg-emerald-500" : "bg-amber-400"
            )}>
              {deliveryType === "auto" && (
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
              )}
            </span>
            <span className={cn(
              "text-xs font-semibold",
              deliveryType === "auto"
                ? "text-emerald-700 dark:text-emerald-300"
                : "text-amber-600 dark:text-amber-300"
            )}>
              {deliveryType === "auto" ? t("product.deliveryAuto") : t("product.deliveryManual")}
            </span>
          </div>
        </div>

        {/* Stock + Sales */}
        <div className="flex items-center gap-4 text-sm">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            <Package className="h-3.5 w-3.5" />
            {t("product.stock")} {unlimited ? "不限" : finiteStock}
          </span>
          {((product.sales_count ?? 0) + (product.initial_sales ?? 0)) > 0 && (
            <span className="flex items-center gap-1.5 text-muted-foreground">
              <TrendingUp className="h-3.5 w-3.5" />
              {t("product.sold")} {(product.sales_count ?? 0) + (product.initial_sales ?? 0)}
            </span>
          )}
        </div>

        {/* Spec selection */}
        {product.specs && product.specs.length > 1 && (
          <div>
            <label className="mb-2 block text-sm font-medium text-foreground">
              {t("product.selectSpec")}
            </label>
            <div className="flex flex-wrap gap-2">
              {product.specs.map((spec) => (
                <button
                  key={spec.id}
                  onClick={() => {
                    setSelectedSpec(spec)
                    setQuantity(1)
                  }}
                  className={cn(
                    "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                    selectedSpec?.id === spec.id
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border text-foreground hover:border-primary/30"
                  )}
                  disabled={!spec.unlimited && spec.stock_available === 0}
                >
                  {spec.name}
                  {!spec.unlimited && spec.stock_available === 0 && (
                    <span className="ml-1 text-xs text-muted-foreground">
                      ({t("product.outOfStock")})
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Action area */}
      <div className="rounded-lg border border-border p-4 space-y-4">
        {/* Quantity */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.quantity")}
          </label>
          <div className="inline-flex items-center rounded-md border border-border">
            <button
              onClick={() => setQuantity(Math.max(1, quantity - 1))}
              className="inline-flex h-9 w-9 items-center justify-center text-muted-foreground transition-colors hover:bg-accent"
              disabled={quantity <= 1}
            >
              <Minus className="h-4 w-4" />
            </button>
            <input
              type="number"
              min={1}
              max={unlimited ? undefined : Math.max(1, finiteStock)}
              value={quantity}
              onChange={(e) => {
                const v = parseInt(e.target.value) || 1
                setQuantity(unlimited ? Math.max(1, v) : Math.min(Math.max(1, v), finiteStock || 1))
              }}
              className="h-9 w-16 border-x border-border bg-background text-center text-sm text-foreground [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
            />
            <button
              onClick={() => setQuantity(unlimited ? quantity + 1 : Math.min(quantity + 1, finiteStock || 1))}
              className="inline-flex h-9 w-9 items-center justify-center text-muted-foreground transition-colors hover:bg-accent"
              disabled={!unlimited && quantity >= finiteStock}
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
        </div>

        {customerInputFields.map(field => (
          <div key={field.id}>
            <label className="mb-1.5 block text-sm font-medium text-foreground">
              {field.label} <span className="text-destructive">*</span>
            </label>
            <input
              ref={element => { customerInputRefs.current[field.id] = element }}
              type="text"
              value={customerInputs[field.id] ?? ""}
              onChange={(event) => {
                const value = event.target.value
                setCustomerInputs(current => ({ ...current, [field.id]: value }))
                if (customerInputErrors[field.id]) {
                  const error = validateCustomerInputValue(value)
                  setCustomerInputErrors(current => ({ ...current, [field.id]: error ?? "" }))
                }
              }}
              onBlur={() => {
                const error = validateCustomerInputValue(customerInputs[field.id] ?? "")
                if (error) setCustomerInputErrors(current => ({ ...current, [field.id]: `${field.label}：${error}` }))
              }}
              placeholder={`请输入${field.label}`}
              className={cn(
                "h-10 w-full rounded-lg border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                customerInputErrors[field.id] ? "border-destructive" : "border-input"
              )}
            />
            {customerInputErrors[field.id] && <p className="mt-1 text-xs text-destructive">{customerInputErrors[field.id]}</p>}
          </div>
        ))}

        {/* Email input */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {adminPickup ? t("product.adminPickupEmailOptional") : t("product.email")}
          </label>
          <input
            ref={emailInputRef}
            type="email"
              placeholder={adminPickup ? t("product.adminPickupEmailPlaceholder") : t("product.emailPlaceholder")}
            value={email}
            onChange={(e) => handleEmailChange(e.target.value)}
            className={cn(
              "h-10 w-full rounded-lg border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
              emailError ? "border-destructive" : "border-input"
            )}
          />
          <div className="mt-1.5">
            <p className="text-xs text-muted-foreground">
              {adminPickup ? t("product.adminPickupEmailHint") : t("product.emailFullHint")}
            </p>
            {emailError && (
              <p className="mt-1 text-xs text-destructive">{emailError}</p>
            )}
          </div>
        </div>

        {/* Payment method */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.paymentMethod")}
          </label>
          {adminPickup ? (
            <div className="rounded-lg border border-primary/30 bg-primary/5 px-3 py-2.5 text-sm font-medium text-primary">
              {t("payment.adminPickup")}
            </div>
          ) : (
            <PaymentSelector
              channels={enabledChannels}
              selected={selectedPayment}
              onSelect={setSelectedPayment}
            />
          )}
        </div>

        {/* Total */}
        <div className="flex items-baseline justify-between border-t border-border pt-4">
          <span className="text-sm text-muted-foreground">{t("product.totalPrice")}</span>
          <div className="flex items-baseline gap-0.5">
            <span className="text-2xl font-bold text-primary">
              {formatYuan(totalPrice)}
            </span>
          </div>
        </div>

        <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-3" />

        {/* Action Buttons */}
        <div>
          <button
            onClick={handleBuyNow}
            disabled={submitting || isOutOfStock}
            className="scheme-glow inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:pointer-events-none disabled:opacity-50"
          >
            {submitting ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <Zap className="h-4 w-4" />
            )}
            {isOutOfStock ? t("product.outOfStock") : adminPickup ? t("product.adminPickup") : t("product.buyNow")}
          </button>
        </div>
      </div>
    </div>
  )
}
