"use client"

import { useState, useMemo, useRef, useEffect } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { Zap, Minus, Plus, Package, TrendingUp } from "lucide-react"
import { motion } from "motion/react"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { ApiError, orderApi, requestOrThrow, getApiErrorMessage, setTurnstileHeaders, saveOrderLookupToken } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { cn, formatYuan, detectPaymentDevice } from "@/lib/utils"
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency"
import { getPaymentTypeForChannel } from "@/lib/payment-channel"
import { executePaymentAction, isExecutablePaymentAction } from "@/lib/payment-action-executor"
import { buildPaymentPageHref, getPaymentActionEnvironment, resolvePaymentEntry, savePaymentAction, shouldSeedPaymentAction } from "@/lib/payment-entry"
import { PaymentSelector } from "@/components/shared/payment-selector"
import type { ProductDetail, ProductSpec, PublicPaymentChannel } from "@/types"
import { validateCustomerInputs, validateCustomerInputValue } from "@/lib/customer-inputs"

interface ProductActionsProps {
  product: ProductDetail
  channels: PublicPaymentChannel[]
}

export function ProductActions({ product, channels }: ProductActionsProps) {
  const { t } = useLocale()
  const { user } = useAuth()
  const router = useRouter()
  const customerInputRefs = useRef<Record<string, HTMLInputElement | null>>({})

  // The public API only returns enabled, storefront-safe channels.
  const enabledChannels = useMemo(() => channels, [channels])
  const adminPickup = isAdminRole(user?.role)

  const [selectedSpec, setSelectedSpec] = useState<ProductSpec | null>(
    product.specs?.[0] || null
  )
  useEffect(() => {
    setSelectedSpec((current) => product.specs?.find((spec) => spec.id === current?.id) || product.specs?.[0] || null)
  }, [product])
  const [quantity, setQuantity] = useState(1)
  const customerInputFields = product.customer_input_fields ?? []
  const [customerInputs, setCustomerInputs] = useState<Record<string, string>>(() =>
    Object.fromEntries(customerInputFields.map(field => [field.id, ""]))
  )
  const [customerInputErrors, setCustomerInputErrors] = useState<Record<string, string>>({})
  const [selectedPayment, setSelectedPayment] = useState(
    enabledChannels.length > 0 ? enabledChannels[0].channel_code : ""
  )
  const [submitting, setSubmitting] = useState(false)
  const [paymentFallbackHref, setPaymentFallbackHref] = useState<string | null>(null)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const currentPrice = selectedSpec ? selectedSpec.price : product.price
  const totalPrice = currentPrice * quantity
  const unlimited = selectedSpec?.unlimited ?? product.unlimited ?? false
  const currentStock = selectedSpec?.stock_available ?? product.stock_available ?? 0
  const finiteStock = currentStock ?? 0
  const isOutOfStock = !unlimited && finiteStock === 0
  const deliveryType = product.delivery_type === "MANUAL" ? "manual" : "auto"

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
    setPaymentFallbackHref(null)
    const intent = getOrCreateIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, {
      product_id: product.id,
      spec_id: selectedSpec?.id ?? null,
      quantity,
      customer_inputs: normalizedCustomerInputs,
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
          customer_inputs: normalizedCustomerInputs,
          payment_method: adminPickup ? "ADMIN_PICKUP" : selectedPayment,
          expected_unit_price: Number(currentPrice.toFixed(2)),
          idempotency_key: intent.key,
          idempotency_fingerprint: intent.fingerprint,
          device,
        }))
      saveOrderLookupToken(result.order.order_no, result.lookup_token)
      clearIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, intent.key)
      if (adminPickup || result.payment.admin_pickup || result.order.order_type === "ADMIN_PICKUP") {
        toast.success(t("payment.adminPickupSuccess"))
        router.push(`/order/query?orderNo=${encodeURIComponent(String(result.order.order_no))}`)
        return
      }
      const paymentType = getPaymentTypeForChannel(enabledChannels, selectedPayment)
      const paymentHref = buildPaymentPageHref(result.payment.order_id, result.order.order_no, paymentType || "")
      const action = result.payment.next_action
      if (action) {
        const entry = resolvePaymentEntry(action, getPaymentActionEnvironment())
        if (entry.mode === "execute" && isExecutablePaymentAction(entry.decision)) {
          setPaymentFallbackHref(paymentHref)
          toast.success(t("payment.redirectingToPay"))
          executePaymentAction(entry.decision)
          return
        }
        if (shouldSeedPaymentAction(entry)) {
          savePaymentAction(result.payment.order_id, action)
        }
      }
      toast.success(t("payment.redirectingToPay"))
      router.push(paymentHref)
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
    <div className="flex flex-col gap-5 lg:sticky lg:top-24">
      {/* Title */}
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-primary">{t("product.buyNow")}</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">
          {product.title}
        </h1>
      </div>

      {/* Price + Specs + Stock */}
      <div className="surface-raised space-y-5 p-5">
        {/* Price row + delivery status */}
        <div className="flex flex-wrap items-baseline justify-between gap-y-2">
          <div className="flex items-baseline gap-3">
            <div className="flex items-baseline gap-0.5">
              <span className="text-3xl font-semibold tracking-tight text-foreground">
                {formatYuan(currentPrice)}
              </span>
            </div>
          </div>

          {/* Delivery status indicator */}
          <div className={cn(
            "inline-flex items-center gap-1.5 rounded-full border px-3 py-1",
            deliveryType === "auto"
              ? "border-emerald-500/20 bg-emerald-500/[0.08]"
              : "border-amber-500/20 bg-amber-500/[0.08]"
          )}>
            <span className={cn(
              "relative inline-flex h-2 w-2 rounded-full",
              deliveryType === "auto" ? "bg-emerald-500" : "bg-amber-400"
            )} />
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
                    "relative rounded-control border px-3 py-2 text-sm font-medium transition-colors duration-180",
                    selectedSpec?.id === spec.id
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border-subtle text-foreground hover:border-border-strong hover:bg-surface-muted"
                  )}
                  disabled={!spec.unlimited && spec.stock_available === 0}
                >
                  {selectedSpec?.id === spec.id && <motion.span layoutId="selected-spec" className="absolute inset-0 -z-0 rounded-control border border-primary/30 bg-primary/[0.06]" transition={{ type: "spring", stiffness: 420, damping: 34 }} />}
                  <span className="relative z-10">{spec.name}</span>
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
      <div className="surface-raised space-y-5 p-5">
        {/* Quantity */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.quantity")}
          </label>
          <div className="inline-flex items-center rounded-md border border-border">
            <button
              onClick={() => setQuantity(Math.max(1, quantity - 1))}
              className="inline-flex h-9 w-9 items-center justify-center rounded-l-control text-muted-foreground transition-colors hover:bg-surface"
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
              className="h-9 w-16 border-x border-border-subtle bg-surface text-center text-sm text-foreground [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
            />
            <button
              onClick={() => setQuantity(unlimited ? quantity + 1 : Math.min(quantity + 1, finiteStock || 1))}
              className="inline-flex h-9 w-9 items-center justify-center rounded-r-control text-muted-foreground transition-colors hover:bg-surface"
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
                "h-11 w-full rounded-control border bg-surface px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                customerInputErrors[field.id] ? "border-destructive" : "border-input"
              )}
            />
            {customerInputErrors[field.id] && <p className="mt-1 text-xs text-destructive">{customerInputErrors[field.id]}</p>}
          </div>
        ))}

        {/* Payment method */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.paymentMethod")}
          </label>
          {adminPickup ? (
            <div className="rounded-control border border-primary/20 bg-primary/[0.06] px-3 py-3 text-sm font-medium text-primary">
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
        <div className="flex items-baseline justify-between border-t border-border-subtle pt-4">
          <span className="text-sm text-muted-foreground">{t("product.totalPrice")}</span>
          <div className="flex items-baseline gap-0.5">
            <span className="text-2xl font-semibold text-foreground">
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
            className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-control bg-primary text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:pointer-events-none disabled:opacity-50"
          >
            {submitting ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <Zap className="h-4 w-4" />
            )}
            {isOutOfStock ? t("product.outOfStock") : adminPickup ? t("product.adminPickup") : t("product.buyNow")}
          </button>
          {paymentFallbackHref && (
            <div className="mt-3 flex flex-col items-center gap-2 rounded-control border border-primary/20 bg-primary/[0.06] px-3 py-3 text-center" role="alert">
              <p className="text-xs text-muted-foreground">{t("payment.actionFallback")}</p>
              <Link href={paymentFallbackHref} className="inline-flex h-9 items-center rounded-control bg-primary px-4 text-sm font-medium text-primary-foreground">
                {t("payment.continuePay")}
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
