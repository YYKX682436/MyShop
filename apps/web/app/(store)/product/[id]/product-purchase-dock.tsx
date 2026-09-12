"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { ChevronUp, Loader2, Minus, Plus, ShoppingCart } from "lucide-react"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import {
  ApiError,
  getApiErrorMessage,
  orderApi,
  requestOrThrow,
  setTurnstileHeaders,
  userApi,
} from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { PaymentSelector } from "@/components/shared/payment-selector"
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer"
import { cn, detectPaymentDevice, formatYuan } from "@/lib/utils"
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency"
import {
  executePaymentAction,
  isExecutablePaymentAction,
  type ExecutablePaymentAction,
} from "@/lib/payment-action-executor"
import {
  buildOrderPageHref,
  clearPaymentAction,
  getPaymentActionEnvironment,
  resolvePaymentEntry,
  savePaymentAction,
  shouldSeedPaymentAction,
} from "@/lib/payment-entry"
import {
  getBalancePaymentAvailability,
  getDefaultPaymentMethod,
  isImmediateBalancePaymentSuccess,
  paymentMethodForRequest,
  reconcilePaymentMethod,
} from "@/lib/payment-method"
import { validateCustomerInputValue, validateCustomerInputs } from "@/lib/customer-inputs"
import type {
  OrderPaymentMethod,
  ProductDetail,
  ProductSpec,
  PublicPaymentChannel,
} from "@/types"

interface ProductPurchaseDockProps {
  product: ProductDetail
  channels: PublicPaymentChannel[]
}

function isSpecOutOfStock(spec: ProductSpec): boolean {
  return !spec.unlimited && (spec.stock_available ?? 0) <= 0
}

function firstPurchasableSpec(specs: ProductSpec[]): ProductSpec | null {
  return specs.find((spec) => !isSpecOutOfStock(spec)) ?? specs[0] ?? null
}

export function ProductPurchaseDock({ product, channels }: ProductPurchaseDockProps) {
  const { t } = useLocale()
  const { user, setUser, authLoaded } = useAuth()
  const router = useRouter()
  const customerInputRefs = useRef<Record<string, HTMLInputElement | null>>({})
  const [open, setOpen] = useState(false)
  const [selectedSpec, setSelectedSpec] = useState<ProductSpec | null>(() => (
    firstPurchasableSpec(product.specs ?? [])
  ))
  const [quantity, setQuantity] = useState(1)
  const customerInputFields = product.customer_input_fields ?? []
  const [customerInputs, setCustomerInputs] = useState<Record<string, string>>(() => (
    Object.fromEntries(customerInputFields.map((field) => [field.id, ""]))
  ))
  const [customerInputErrors, setCustomerInputErrors] = useState<Record<string, string>>({})
  const [selectedPaymentMethod, setSelectedPaymentMethod] = useState<OrderPaymentMethod>("ONLINE")
  const [selectedPaymentChannelCode, setSelectedPaymentChannelCode] = useState(
    channels[0]?.channel_code || "",
  )
  const [submitting, setSubmitting] = useState(false)
  const [paymentFallbackAction, setPaymentFallbackAction] = useState<ExecutablePaymentAction | null>(null)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  useEffect(() => {
    const specs = product.specs ?? []
    setSelectedSpec((current) => {
      const refreshed = current ? specs.find((spec) => spec.id === current.id) : null
      if (refreshed && !isSpecOutOfStock(refreshed)) return refreshed
      return firstPurchasableSpec(specs)
    })
    setQuantity(1)
  }, [product])

  useEffect(() => {
    setSelectedPaymentChannelCode((current) => (
      channels.some((channel) => channel.channel_code === current)
        ? current
        : channels[0]?.channel_code || ""
    ))
  }, [channels])

  const adminPickup = isAdminRole(user?.role)
  const currentPrice = selectedSpec?.price ?? product.price
  const totalPrice = currentPrice * quantity
  const unlimited = selectedSpec?.unlimited ?? product.unlimited ?? false
  const finiteStock = selectedSpec?.stock_available ?? product.stock_available ?? 0
  const isOutOfStock = !unlimited && finiteStock <= 0
  const balanceAvailability = useMemo(() => getBalancePaymentAvailability(
    Boolean(user),
    user?.role,
    user?.balance,
    totalPrice,
  ), [totalPrice, user])
  const onlineAvailable = channels.length > 0 && Boolean(selectedPaymentChannelCode)
  const paymentReady = adminPickup
    || (selectedPaymentMethod === "BALANCE" ? balanceAvailability.selectable : onlineAvailable)

  useEffect(() => {
    if (!open) return
    const reconciled = reconcilePaymentMethod(selectedPaymentMethod, balanceAvailability)
    if (reconciled !== selectedPaymentMethod) setSelectedPaymentMethod(reconciled)
  }, [balanceAvailability, open, selectedPaymentMethod])

  const resetPanelUiState = () => {
    setCustomerInputErrors({})
    setPaymentFallbackAction(null)
  }

  const handleOpenChange = (nextOpen: boolean) => {
    if (nextOpen) {
      if (isOutOfStock || !authLoaded) return
      resetPanelUiState()
      setSelectedPaymentMethod(getDefaultPaymentMethod(balanceAvailability))
      setOpen(true)
      return
    }

    resetPanelUiState()
    handleTurnstileReset()
    setOpen(false)
  }

  const validateCustomerInputsForAction = () => {
    const result = validateCustomerInputs(customerInputFields, customerInputs)
    if (!result.ok) {
      setCustomerInputErrors((current) => ({ ...current, [result.field ?? ""]: result.message }))
      if (result.field) customerInputRefs.current[result.field]?.focus()
      return null
    }
    setCustomerInputErrors({})
    return result.values
  }

  const retryPaymentExecution = () => {
    if (!paymentFallbackAction) return
    try {
      executePaymentAction(paymentFallbackAction)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("common.error"))
    }
  }

  const handleBuyNow = async () => {
    const normalizedCustomerInputs = validateCustomerInputsForAction()
    if (!normalizedCustomerInputs) return

    if (!adminPickup) {
      if (selectedPaymentMethod === "BALANCE" && !balanceAvailability.selectable) {
        toast.error(balanceAvailability.reason === "login_required"
          ? t("product.balanceLoginRequired")
          : t("product.balanceInsufficient"))
        return
      }
      if (selectedPaymentMethod === "ONLINE" && !onlineAvailable) {
        toast.error(t("product.noOnlineChannels"))
        return
      }
    }

    if ((product.specs ?? []).length > 0 && !selectedSpec) return
    if (isOutOfStock) {
      toast.error(t("product.outOfStock"))
      return
    }

    setSubmitting(true)
    setPaymentFallbackAction(null)
    const requestPaymentIntent = adminPickup
      ? {}
      : paymentMethodForRequest(selectedPaymentMethod, selectedPaymentChannelCode)
    const fingerprintPaymentIntent = adminPickup
      ? { payment_method: null, payment_channel_code: null }
      : {
          payment_method: selectedPaymentMethod,
          payment_channel_code: selectedPaymentMethod === "ONLINE" ? selectedPaymentChannelCode : null,
        }
    const intent = getOrCreateIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, {
      product_id: product.id,
      spec_id: selectedSpec?.id ?? null,
      quantity,
      customer_inputs: normalizedCustomerInputs,
      ...fingerprintPaymentIntent,
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
        ...requestPaymentIntent,
        expected_unit_price: Number(currentPrice.toFixed(2)),
        idempotency_key: intent.key,
        idempotency_fingerprint: intent.fingerprint,
        device,
      }))
      clearIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, intent.key)

      if (adminPickup || result.payment.admin_pickup || result.order.order_type === "ADMIN_PICKUP") {
        toast.success(t("payment.adminPickupSuccess"))
        router.push(buildOrderPageHref(result.order.id))
        return
      }

      if (isImmediateBalancePaymentSuccess(result)) {
        try {
          const refreshedProfile = await requestOrThrow(() => userApi.getProfile())
          setUser(refreshedProfile)
        } catch {
          // The order result is already authoritative; a later profile load reconciles the balance.
        }
        toast.success(t("payment.success"))
        router.push(buildOrderPageHref(result.order.id))
        return
      }

      const paymentHref = buildOrderPageHref(result.payment.order_id)
      const action = result.payment.next_action
      if (!action) {
        clearPaymentAction(result.payment.order_id)
        toast.error(t("payment.loadFailed"))
        return
      }

      const entry = resolvePaymentEntry(action, getPaymentActionEnvironment())
      if (entry.mode === "execute" && isExecutablePaymentAction(entry.decision)) {
        setPaymentFallbackAction(entry.decision)
        toast.success(t("payment.redirectingToPay"))
        executePaymentAction(entry.decision)
        return
      }

      if (shouldSeedPaymentAction(entry)) {
        savePaymentAction(result.payment.order_id, action)
        toast.success(t("payment.redirectingToPay"))
        router.push(paymentHref)
        return
      }

      clearPaymentAction(result.payment.order_id)
      toast.error(t("payment.loadFailed"))
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
    <Drawer
      open={open}
      onOpenChange={handleOpenChange}
      direction="bottom"
      dismissible
      modal
      fixed
      repositionInputs
      shouldScaleBackground={false}
    >
      <div className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] sm:px-4">
        <div className="pointer-events-auto mx-auto flex w-full max-w-2xl items-center gap-3 rounded-[20px] border border-border-subtle bg-surface/85 p-2 shadow-lg backdrop-blur-xl supports-[backdrop-filter]:bg-surface/70">
          <div className="min-w-0 flex-1 px-2">
            <p className="truncate text-[11px] text-muted-foreground">
              {t("product.unitPrice")}{selectedSpec ? ` · ${selectedSpec.name}` : ""}
            </p>
            <p className={cn(
              "text-xl font-semibold tracking-tight",
              isOutOfStock ? "text-muted-foreground" : "text-foreground",
            )}>
              {formatYuan(currentPrice)}
            </p>
          </div>
          <DrawerTrigger asChild>
            <button
              type="button"
              disabled={isOutOfStock || !authLoaded}
              className="inline-flex min-h-12 min-w-[7.5rem] shrink-0 items-center justify-center gap-2 rounded-control bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98] disabled:pointer-events-none disabled:bg-muted disabled:text-muted-foreground"
              aria-label={isOutOfStock ? t("product.soldOut") : t("product.buyNow")}
            >
              {isOutOfStock ? (
                t("product.soldOut")
              ) : (
                <>
                  <ShoppingCart className="size-4" aria-hidden="true" />
                  <span>{t("product.buyNow")}</span>
                  <ChevronUp className="size-4" aria-hidden="true" />
                </>
              )}
            </button>
          </DrawerTrigger>
        </div>
      </div>

      <DrawerContent className="origin-bottom border-border-subtle bg-surface-raised/95 shadow-2xl outline-none sm:mx-auto sm:max-w-2xl">
        <div className="flex max-h-[min(75dvh,760px)] flex-col pb-[env(safe-area-inset-bottom)]">
          <DrawerHeader className="shrink-0 border-b border-border-subtle px-4 pb-3 pt-2 text-left sm:px-6 sm:pt-3">
            <DrawerTitle className="text-base">{t("product.buyNow")}</DrawerTitle>
            <DrawerDescription className="mt-1 line-clamp-1 text-xs">
              {product.title}{selectedSpec ? ` · ${selectedSpec.name}` : ""} · {formatYuan(totalPrice)}
            </DrawerDescription>
          </DrawerHeader>

          <form
            className="flex min-h-0 flex-1 flex-col"
            onSubmit={(event) => {
              event.preventDefault()
              void handleBuyNow()
            }}
          >
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4 sm:px-6 sm:py-5">
              {(product.specs ?? []).length > 1 && (
                <section className="mb-5" aria-labelledby="purchase-spec-title">
                  <h2 id="purchase-spec-title" className="mb-2 text-sm font-medium text-foreground">
                    {t("product.selectSpec")}
                  </h2>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {(product.specs ?? []).map((spec) => {
                      const specOutOfStock = isSpecOutOfStock(spec)
                      return (
                        <button
                          key={spec.id}
                          type="button"
                          aria-pressed={selectedSpec?.id === spec.id}
                          disabled={specOutOfStock}
                          onClick={() => {
                            setSelectedSpec(spec)
                            setQuantity(1)
                          }}
                          className={cn(
                            "min-h-11 rounded-control border px-3 py-2 text-left text-sm font-medium transition-[border-color,background-color,opacity] duration-180",
                            selectedSpec?.id === spec.id
                              ? "border-primary/30 bg-primary/10 text-primary"
                              : "border-border-subtle text-foreground hover:border-border-strong hover:bg-surface-muted",
                            specOutOfStock && "cursor-not-allowed opacity-50",
                          )}
                        >
                          <span className="block truncate">{spec.name}</span>
                          {specOutOfStock && (
                            <span className="mt-0.5 block text-[11px] font-normal text-muted-foreground">
                              {t("product.outOfStock")}
                            </span>
                          )}
                        </button>
                      )
                    })}
                  </div>
                </section>
              )}

              <section className="mb-5" aria-labelledby="purchase-quantity-title">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <h2 id="purchase-quantity-title" className="text-sm font-medium text-foreground">
                    {t("product.quantity")}
                  </h2>
                  <span className="text-xs text-muted-foreground">{formatYuan(currentPrice)} / {t("product.perPiece")}</span>
                </div>
                <div className="inline-flex items-center rounded-control border border-border-subtle bg-surface-muted p-1">
                  <button
                    type="button"
                    onClick={() => setQuantity(Math.max(1, quantity - 1))}
                    className="inline-flex h-10 w-10 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground disabled:pointer-events-none disabled:opacity-40"
                    disabled={quantity <= 1}
                    aria-label={t("product.decreaseQuantity")}
                  >
                    <Minus className="size-4" aria-hidden="true" />
                  </button>
                  <input
                    type="number"
                    min={1}
                    max={unlimited ? undefined : Math.max(1, finiteStock)}
                    value={quantity}
                    onChange={(event) => {
                      const value = Number.parseInt(event.target.value, 10) || 1
                      setQuantity(unlimited
                        ? Math.max(1, value)
                        : Math.min(Math.max(1, value), Math.max(1, finiteStock)))
                    }}
                    className="h-10 w-16 border-x border-border-subtle bg-transparent text-center text-base text-foreground [appearance:textfield] focus:outline-none focus:ring-2 focus:ring-ring/40 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                    aria-label={t("product.quantity")}
                  />
                  <button
                    type="button"
                    onClick={() => setQuantity(unlimited ? quantity + 1 : Math.min(quantity + 1, Math.max(1, finiteStock)))}
                    className="inline-flex h-10 w-10 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground disabled:pointer-events-none disabled:opacity-40"
                    disabled={!unlimited && quantity >= finiteStock}
                    aria-label={t("product.increaseQuantity")}
                  >
                    <Plus className="size-4" aria-hidden="true" />
                  </button>
                </div>
              </section>

              <div className="mb-5 flex items-center justify-between gap-4 rounded-control border border-border-subtle bg-surface-muted px-3.5 py-3">
                <span className="text-sm text-muted-foreground">{t("product.totalPrice")}</span>
                <span className="text-2xl font-semibold tracking-tight text-foreground">{formatYuan(totalPrice)}</span>
              </div>

              {customerInputFields.length > 0 && (
                <section className="mb-5 space-y-4" aria-label={t("product.customerInputSection")}>
                  {customerInputFields.map((field) => {
                    const error = customerInputErrors[field.id]
                    const errorId = `customer-input-error-${field.id}`
                    return (
                      <div key={field.id}>
                        <label htmlFor={`customer-input-${field.id}`} className="mb-1.5 block text-sm font-medium text-foreground">
                          {field.label} <span className="text-destructive">*</span>
                        </label>
                        <input
                          id={`customer-input-${field.id}`}
                          ref={(element) => { customerInputRefs.current[field.id] = element }}
                          type="text"
                          value={customerInputs[field.id] ?? ""}
                          onChange={(event) => {
                            const value = event.target.value
                            setCustomerInputs((current) => ({ ...current, [field.id]: value }))
                            if (error) {
                              const nextError = validateCustomerInputValue(value)
                              setCustomerInputErrors((current) => ({ ...current, [field.id]: nextError ?? "" }))
                            }
                          }}
                          onBlur={() => {
                            const nextError = validateCustomerInputValue(customerInputs[field.id] ?? "")
                            if (nextError) setCustomerInputErrors((current) => ({ ...current, [field.id]: `${field.label}：${nextError}` }))
                          }}
                          placeholder={t("product.customerInputPlaceholder").replace("{label}", field.label)}
                          aria-invalid={Boolean(error)}
                          aria-describedby={error ? errorId : undefined}
                          className={cn(
                            "h-11 w-full rounded-control border bg-surface px-3 text-base text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40",
                            error ? "border-destructive" : "border-input",
                          )}
                        />
                        {error && <p id={errorId} className="mt-1 text-xs text-destructive">{error}</p>}
                      </div>
                    )
                  })}
                </section>
              )}

              <section aria-labelledby="purchase-payment-title">
                <h2 id="purchase-payment-title" className="mb-2 text-sm font-medium text-foreground">
                  {t("product.paymentMethod")}
                </h2>
                {adminPickup ? (
                  <div className="rounded-control border border-primary/20 bg-primary/[0.06] px-3 py-3 text-sm font-medium text-primary">
                    {t("payment.adminPickup")}
                  </div>
                ) : (
                  <>
                    <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label={t("product.paymentMethod")}>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={selectedPaymentMethod === "BALANCE"}
                        disabled={!balanceAvailability.selectable}
                        onClick={() => setSelectedPaymentMethod("BALANCE")}
                        className={cn(
                          "flex min-h-16 flex-col items-start justify-center rounded-control border px-3 py-2.5 text-left text-sm transition-[border-color,background-color,opacity] duration-180",
                          selectedPaymentMethod === "BALANCE" ? "border-primary/25 bg-primary/10" : "border-border-subtle hover:border-border-strong hover:bg-surface-muted",
                          !balanceAvailability.selectable && "cursor-not-allowed opacity-60",
                        )}
                      >
                        <span className="font-medium text-foreground">{t("product.balancePayment")}</span>
                        <span className="text-xs text-muted-foreground">
                          {balanceAvailability.reason === "login_required"
                            ? t("product.balanceLoginRequired")
                            : balanceAvailability.reason === "insufficient"
                              ? `${t("product.balanceInsufficient")}（${t("product.balanceCurrent").replace("{balance}", formatYuan(user?.balance ?? 0))}）`
                              : t("product.balanceCurrent").replace("{balance}", formatYuan(user?.balance ?? 0))}
                        </span>
                      </button>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={selectedPaymentMethod === "ONLINE"}
                        onClick={() => setSelectedPaymentMethod("ONLINE")}
                        className={cn(
                          "flex min-h-16 flex-col items-start justify-center rounded-control border px-3 py-2.5 text-left text-sm transition-[border-color,background-color] duration-180",
                          selectedPaymentMethod === "ONLINE" ? "border-primary/25 bg-primary/10" : "border-border-subtle hover:border-border-strong hover:bg-surface-muted",
                        )}
                      >
                        <span className="font-medium text-foreground">{t("product.onlinePayment")}</span>
                        <span className="text-xs text-muted-foreground">{channels.length > 0 ? t("product.paymentHint") : t("product.noOnlineChannels")}</span>
                      </button>
                    </div>
                    {selectedPaymentMethod === "ONLINE" && channels.length > 0 && (
                      <div className="mt-3">
                        <PaymentSelector
                          channels={channels}
                          selected={selectedPaymentChannelCode}
                          onSelect={setSelectedPaymentChannelCode}
                        />
                      </div>
                    )}
                    {selectedPaymentMethod === "ONLINE" && channels.length === 0 && (
                      <p className="mt-2 text-xs text-destructive">{t("product.noOnlineChannels")}</p>
                    )}
                  </>
                )}
              </section>

              <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mt-5" />
            </div>

            <div className="shrink-0 border-t border-border-subtle bg-surface-raised/95 px-4 py-3 sm:px-6">
              {paymentFallbackAction && (
                <div className="mb-3 flex flex-col items-center gap-2 rounded-control border border-primary/20 bg-primary/[0.06] px-3 py-3 text-center" role="alert">
                  <p className="text-xs text-muted-foreground">{t("payment.actionFallback")}</p>
                  <button
                    type="button"
                    onClick={retryPaymentExecution}
                    className="inline-flex h-9 items-center rounded-control bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                  >
                    {t("payment.continuePay")}
                  </button>
                </div>
              )}
              <button
                type="submit"
                disabled={submitting || isOutOfStock || !paymentReady}
                className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-control bg-primary text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:pointer-events-none disabled:opacity-50"
              >
                {submitting && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
                {!submitting && isOutOfStock
                  ? t("product.soldOut")
                  : !submitting && adminPickup
                    ? t("product.adminPickup")
                    : !submitting && !paymentReady
                      ? t("product.paymentUnavailable")
                      : submitting
                        ? t("common.processing")
                        : t("product.buyNow")}
              </button>
            </div>
          </form>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
