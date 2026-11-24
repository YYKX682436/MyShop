"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import * as Dialog from "@radix-ui/react-dialog"
import { Loader2, Minus, Plus, X } from "lucide-react"
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
  isZeroPricePurchaseAllowed,
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

interface PurchaseActionBarProps {
  open: boolean
  selectedSpec: ProductSpec | null
  quantity: number
  totalPrice: number
  isOutOfStock: boolean
  authLoaded: boolean
  adminPickup: boolean
  paymentReady: boolean
  submitting: boolean
  triggerRef: React.RefObject<HTMLButtonElement | null>
}

function PurchaseActionBar({
  open, selectedSpec, quantity, totalPrice, isOutOfStock, authLoaded,
  adminPickup, paymentReady, submitting, triggerRef,
}: PurchaseActionBarProps) {
  const { t } = useLocale()
  const label = isOutOfStock ? t("product.soldOut")
    : open && submitting ? t("common.processing")
    : open && adminPickup ? t("product.adminPickup")
    : open && !paymentReady ? t("product.paymentUnavailable")
    : t("product.buyNow")
  const button = (
    <button
      ref={open ? undefined : triggerRef}
      type={open ? "submit" : "button"}
      disabled={open ? submitting || isOutOfStock || !paymentReady : isOutOfStock || !authLoaded}
      className="inline-flex min-h-10 min-w-[8.125rem] max-w-[55%] shrink-0 items-center justify-center rounded-[var(--purchase-corner)] bg-primary px-4 text-center text-sm font-semibold text-primary-foreground shadow-sm transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:bg-muted disabled:text-muted-foreground"
      aria-label={label}
    >
      {submitting && open && <Loader2 className="mr-1.5 size-4 shrink-0 animate-spin" aria-hidden="true" />}
      <span className="min-w-0">{label}</span>
    </button>
  )

  return (
    <div data-slot="purchase-action-bar" className="flex shrink-0 items-center gap-3 p-2">
      <div className="min-w-0 flex-1 px-3">
        <p className="flex min-w-0 text-[11px] text-muted-foreground">
          <span className="truncate">{selectedSpec?.name ?? t("product.quantity")}</span>
          <span className="shrink-0 whitespace-nowrap">{` × ${quantity}`}</span>
        </p>
        <p data-slot="purchase-total" className={cn(
          "break-all text-xl font-bold tracking-tight",
          isOutOfStock ? "text-muted-foreground" : "text-primary",
        )}>
          {formatYuan(totalPrice)}
        </p>
      </div>
      {open ? button : <Dialog.Trigger asChild>{button}</Dialog.Trigger>}
    </div>
  )
}

export function ProductPurchaseDock({ product, channels }: ProductPurchaseDockProps) {
  const { t } = useLocale()
  const { user, setUser, authLoaded } = useAuth()
  const router = useRouter()
  const customerInputRefs = useRef<Record<string, HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | null>>({})
  const triggerRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const viewportCleanupRef = useRef<(() => void) | null>(null)
  const submittingRef = useRef(false)
  const [open, setOpen] = useState(false)
  const [selectedSpec, setSelectedSpec] = useState<ProductSpec | null>(() => (
    firstPurchasableSpec(product.specs ?? [])
  ))
  const [quantity, setQuantity] = useState(1)
  const customerInputFields = [...(product.customer_input_fields ?? []), ...(selectedSpec?.supplier_input_fields ?? [])]
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

  const attachPanel = useCallback((panel: HTMLDivElement | null) => {
    viewportCleanupRef.current?.()
    viewportCleanupRef.current = null
    if (!panel) return
    const viewport = window.visualViewport
    if (!viewport) return

    // The visual viewport can shrink independently of the layout viewport
    // when a mobile keyboard opens. Update only on viewport changes.
    const syncViewport = () => {
      panel.style.setProperty("--purchase-visual-height", `${viewport.height}px`)
      panel.style.setProperty("--purchase-visual-bottom", `${Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)}px`)
    }
    syncViewport()
    viewport.addEventListener("resize", syncViewport)
    viewport.addEventListener("scroll", syncViewport)
    window.addEventListener("resize", syncViewport)
    viewportCleanupRef.current = () => {
      viewport.removeEventListener("resize", syncViewport)
      viewport.removeEventListener("scroll", syncViewport)
      window.removeEventListener("resize", syncViewport)
    }
  }, [])

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
  const zeroPricePurchase = isZeroPricePurchaseAllowed(currentPrice, selectedSpec ? selectedSpec.zero_price_purchase_allowed : product.zero_price_purchase_allowed)
  const zeroPriceUnavailable = currentPrice === 0 && !zeroPricePurchase && !adminPickup
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
  const paymentReady = !zeroPriceUnavailable && (adminPickup || zeroPricePurchase
    || (selectedPaymentMethod === "BALANCE" ? balanceAvailability.selectable : onlineAvailable)
  )

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
    if (submittingRef.current) return
    const normalizedCustomerInputs = validateCustomerInputsForAction()
    if (!normalizedCustomerInputs) return

    if (zeroPriceUnavailable) { toast.error(t("product.zeroUnavailable")); return }
    if (!adminPickup && !zeroPricePurchase) {
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

    submittingRef.current = true
    setSubmitting(true)
    setPaymentFallbackAction(null)
    const requestPaymentIntent = adminPickup
      ? {}
      : zeroPricePurchase ? { payment_method: "BALANCE" as const }
      : paymentMethodForRequest(selectedPaymentMethod, selectedPaymentChannelCode)
    const fingerprintPaymentIntent = adminPickup
      ? { payment_method: null, payment_channel_code: null }
      : zeroPricePurchase ? { payment_method: "BALANCE" as const, payment_channel_code: null }
      : {
          payment_method: selectedPaymentMethod,
          payment_channel_code: selectedPaymentMethod === "ONLINE" ? selectedPaymentChannelCode : null,
        }
    try {
      const intent = getOrCreateIdempotencyKey(`myshop:direct-buy:${product.id}:idempotency`, {
        product_id: product.id,
        spec_id: selectedSpec?.id ?? null,
        quantity,
        customer_inputs: normalizedCustomerInputs,
        ...fingerprintPaymentIntent,
        expected_unit_price: Number(currentPrice.toFixed(2)),
      })
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
        router.push(paymentHref)
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
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  const actionBarProps = {
    selectedSpec, quantity, totalPrice, isOutOfStock, authLoaded,
    adminPickup, paymentReady, submitting, triggerRef,
  }

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <div
        aria-hidden={open}
        inert={open}
        className={cn(
          "product-purchase-dock-viewport pointer-events-none fixed inset-x-0 bottom-0 z-40 justify-center px-3",
          open ? "hidden" : "flex",
        )}
      >
        <div className="product-purchase-dock-closed static-material action-bar-material pointer-events-auto w-full max-w-md sm:max-w-lg">
          <PurchaseActionBar {...actionBarProps} open={false} />
        </div>
      </div>

      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/45" />
        <Dialog.Content
          ref={attachPanel}
          className="product-purchase-panel static-material action-bar-material z-50 flex flex-col outline-none"
          onOpenAutoFocus={(event) => {
            event.preventDefault()
            closeRef.current?.focus({ preventScroll: true })
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            triggerRef.current?.focus({ preventScroll: true })
          }}
        >
          <form
            className="flex min-h-0 flex-col"
            onSubmit={(event) => {
              event.preventDefault()
              void handleBuyNow()
            }}
          >
            <div className="shrink-0 border-b border-border-subtle/60 px-4 pb-2.5 pt-3 text-left sm:px-6">
              <div className="flex items-center justify-between">
                <Dialog.Title className="text-base font-semibold">{t("product.buyNow")}</Dialog.Title>
                <Dialog.Close ref={closeRef} type="button" className="rounded-full p-1 text-muted-foreground transition-colors hover:bg-surface-muted hover:text-foreground">
                  <span className="sr-only">{t("common.close")}</span>
                  <X className="size-4" aria-hidden="true" />
                </Dialog.Close>
              </div>
              <Dialog.Description className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                {product.title}{selectedSpec ? ` · ${selectedSpec.name}` : ""}
              </Dialog.Description>
            </div>
            <div data-slot="purchase-content" className="min-h-0 overflow-y-auto overscroll-contain px-4 py-3.5 sm:px-6 sm:py-4">
              {(product.specs ?? []).length > 1 && (
                <section className="mb-4" aria-labelledby="purchase-spec-title">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <h2 id="purchase-spec-title" className="shrink-0 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                      {t("product.selectSpec")}
                    </h2>
                    {selectedSpec && (
                      <span className="min-w-0 truncate text-right text-xs font-medium text-primary">{selectedSpec.name}</span>
                    )}
                  </div>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {(product.specs ?? []).map((spec) => {
                      const specOutOfStock = isSpecOutOfStock(spec)
                      const isSelected = selectedSpec?.id === spec.id
                      return (
                        <button
                          key={spec.id}
                          type="button"
                          aria-pressed={isSelected}
                          disabled={specOutOfStock}
                          onClick={() => {
                            setSelectedSpec(spec)
                            setQuantity(1)
                            setCustomerInputs({})
                            setCustomerInputErrors({})
                          }}
                          className={cn(
                            "min-h-10 rounded-xl border px-3 py-2 text-left text-sm font-medium transition-all duration-180",
                            isSelected
                              ? "border-primary bg-primary/10 text-primary shadow-xs ring-1 ring-primary/40 font-semibold"
                              : "border-border-subtle bg-surface-muted/50 text-foreground hover:border-border-strong hover:bg-surface-muted",
                            specOutOfStock && "cursor-not-allowed opacity-40 grayscale",
                          )}
                        >
                          <span className="block truncate">{spec.name}</span>
                          {specOutOfStock && (
                            <span className="mt-0.5 block text-[10px] text-muted-foreground">
                              {t("product.soldOut")}
                            </span>
                          )}
                        </button>
                      )
                    })}
                  </div>
                </section>
              )}

              <section className="mb-4 flex items-center justify-between gap-3 py-1" aria-labelledby="purchase-quantity-title">
                <div>
                  <h2 id="purchase-quantity-title" className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("product.quantity")}
                  </h2>
                  <p className="mt-0.5 text-xs text-muted-foreground/80">
                    {formatYuan(currentPrice)} / {t("product.perPiece")}
                  </p>
                </div>
                <div className="inline-flex items-center rounded-xl border border-border-subtle bg-surface-muted/60 p-0.5">
                  <button
                    type="button"
                    onClick={() => setQuantity(Math.max(1, quantity - 1))}
                    className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-surface hover:text-foreground disabled:pointer-events-none disabled:opacity-30"
                    disabled={quantity <= 1}
                    aria-label={t("product.decreaseQuantity")}
                  >
                    <Minus className="size-3.5" aria-hidden="true" />
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
                    className="h-9 w-14 border-x border-border-subtle/80 bg-transparent text-center text-sm font-semibold text-foreground [appearance:textfield] focus:outline-none focus:ring-1 focus:ring-primary/40 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                    aria-label={t("product.quantity")}
                  />
                  <button
                    type="button"
                    onClick={() => setQuantity(unlimited ? quantity + 1 : Math.min(quantity + 1, Math.max(1, finiteStock)))}
                    className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-surface hover:text-foreground disabled:pointer-events-none disabled:opacity-30"
                    disabled={!unlimited && quantity >= finiteStock}
                    aria-label={t("product.increaseQuantity")}
                  >
                    <Plus className="size-3.5" aria-hidden="true" />
                  </button>
                </div>
              </section>

              {customerInputFields.length > 0 && (
                <section className="mb-4 space-y-3" aria-label={t("product.customerInputSection")}>
                  {customerInputFields.map((field) => {
                    const error = customerInputErrors[field.id]
                    const errorId = `customer-input-error-${field.id}`
                    const change = (value: string) => {
                      setCustomerInputs((current) => ({ ...current, [field.id]: value }))
                      if (error && !validateCustomerInputValue(value, field)) {
                        setCustomerInputErrors((current) => ({ ...current, [field.id]: "" }))
                      }
                    }
                    const blur = () => {
                      const nextError = validateCustomerInputValue(customerInputs[field.id] ?? "", field)
                      if (nextError) setCustomerInputErrors((current) => ({ ...current, [field.id]: `${field.label}：${nextError}` }))
                    }
                    const className = cn(
                      "w-full rounded-xl border bg-surface/80 px-3 text-base text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 sm:text-sm",
                      field.type === "MULTILINE" ? "min-h-20 py-2" : "h-10",
                      error ? "border-destructive" : "border-input",
                    )
                    const common = { id: `customer-input-${field.id}`, value: customerInputs[field.id] ?? "",
                      required: field.required !== false, "aria-invalid": Boolean(error),
                      "aria-describedby": error ? errorId : undefined, className, onBlur: blur }
                    return (
                      <div key={field.id}>
                        <label htmlFor={`customer-input-${field.id}`} className="mb-1 block text-xs font-medium text-foreground">
                          {field.label} {field.required !== false && <span className="text-destructive" aria-hidden="true">*</span>}
                        </label>
                        {field.type === "SELECT" ? <select {...common} ref={(element) => { customerInputRefs.current[field.id] = element }} onChange={(event) => change(event.target.value)}>
                          <option value="">请选择</option>{field.options?.map(option => <option value={option.value} key={option.value}>{option.name}</option>)}
                        </select> : field.type === "MULTILINE" ? <textarea {...common} ref={(element) => { customerInputRefs.current[field.id] = element }} onChange={(event) => change(event.target.value)} placeholder={field.placeholder || ""} />
                          : <input {...common} ref={(element) => { customerInputRefs.current[field.id] = element }} type="text" inputMode={field.type === "NUMBER" ? "decimal" : "text"} disabled={field.type === "IMAGE" || field.type === "CASCADE"} onChange={(event) => change(event.target.value)} placeholder={field.placeholder || t("product.customerInputPlaceholder").replace("{label}", field.label)} />}
                        {(field.type === "IMAGE" || field.type === "CASCADE") && <p className="mt-1 text-xs text-muted-foreground">此字段暂不支持购买</p>}
                        {error && <p id={errorId} role="alert" className="mt-1 text-xs text-destructive">{error}</p>}
                      </div>
                    )
                  })}
                </section>
              )}

              <section aria-labelledby="purchase-payment-title">
                <h2 id="purchase-payment-title" className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("product.paymentMethod")}
                </h2>
                {adminPickup ? (
                  <div className="rounded-xl border border-primary/20 bg-primary/[0.06] px-3.5 py-2.5 text-sm font-medium text-primary">
                    {t("payment.adminPickup")}
                  </div>
                ) : zeroPricePurchase ? (
                  <p className="rounded-xl border border-primary/20 bg-primary/[0.06] px-3.5 py-2.5 text-sm font-medium text-primary">{t("product.zeroPrice")}</p>
                ) : zeroPriceUnavailable ? (
                  <p className="rounded-xl border border-destructive/30 px-3.5 py-2.5 text-sm text-destructive">{t("product.zeroUnavailable")}</p>
                ) : (
                  <>
                    <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label={t("product.paymentMethod")}>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={selectedPaymentMethod === "BALANCE"}
                        disabled={!balanceAvailability.selectable}
                        onClick={() => setSelectedPaymentMethod("BALANCE")}
                        className={cn(
                          "flex flex-col items-start justify-center rounded-xl border p-2.5 text-left transition-all duration-180",
                          selectedPaymentMethod === "BALANCE"
                            ? "border-primary bg-primary/10 text-primary shadow-xs ring-1 ring-primary/40 font-semibold"
                            : "border-border-subtle bg-surface-muted/50 text-foreground hover:bg-surface-muted",
                          !balanceAvailability.selectable && "cursor-not-allowed opacity-45",
                        )}
                      >
                        <span className="text-sm font-medium">{t("product.balancePayment")}</span>
                        <span className="mt-0.5 w-full truncate text-[11px] text-muted-foreground">
                          {balanceAvailability.reason === "login_required"
                            ? t("product.balanceLoginRequired")
                            : balanceAvailability.reason === "insufficient"
                              ? t("product.balanceInsufficient")
                              : `${formatYuan(user?.balance ?? 0)}`}
                        </span>
                      </button>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={selectedPaymentMethod === "ONLINE"}
                        onClick={() => setSelectedPaymentMethod("ONLINE")}
                        className={cn(
                          "flex flex-col items-start justify-center rounded-xl border p-2.5 text-left transition-all duration-180",
                          selectedPaymentMethod === "ONLINE"
                            ? "border-primary bg-primary/10 text-primary shadow-xs ring-1 ring-primary/40 font-semibold"
                            : "border-border-subtle bg-surface-muted/50 text-foreground hover:bg-surface-muted",
                        )}
                      >
                        <span className="text-sm font-medium">{t("product.onlinePayment")}</span>
                        <span className="mt-0.5 w-full truncate text-[11px] text-muted-foreground">
                          {channels.length > 0 ? t("product.paymentHint") : t("product.noOnlineChannels")}
                        </span>
                      </button>
                    </div>
                    {selectedPaymentMethod === "ONLINE" && channels.length > 0 && (
                      <div className="mt-2.5">
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

              <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mt-4" />
              {paymentFallbackAction && (
                <div className="mt-3 flex flex-col items-center gap-2 rounded-xl border border-primary/20 bg-primary/[0.06] px-3 py-2.5 text-center" role="alert">
                  <p className="text-xs text-muted-foreground">{t("payment.actionFallback")}</p>
                  <button
                    type="button"
                    onClick={retryPaymentExecution}
                    className="inline-flex h-8 items-center rounded-lg bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                  >
                    {t("payment.continuePay")}
                  </button>
                </div>
              )}
            </div>
            <PurchaseActionBar {...actionBarProps} open />
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
