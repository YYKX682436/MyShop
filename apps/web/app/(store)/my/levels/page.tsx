"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { ArrowLeft, Check, Crown, Search, TrendingDown } from "lucide-react"
import { toast } from "sonner"
import { PaymentActionRenderer } from "@/components/shared/payment-action-renderer"
import { PaymentSelector } from "@/components/shared/payment-selector"
import { Button } from "@/components/ui/button"
import { useAuth, useLocale } from "@/lib/context"
import { detectPaymentDevice, formatYuan } from "@/lib/utils"
import { getApiErrorMessage, paymentApi, productApi, requestOrThrow, userAccountApi, userApi } from "@/services/api"
import type { AccountPaymentCreateResult, AccountTransaction, ProductCard, ProductDetail, ProductPriceCheck, PublicPaymentChannel, UserAccountOverview } from "@/types"

export default function MyLevelsPage() {
  const { t } = useLocale()
  const { user, isLoggedIn, authLoaded, setUser } = useAuth()
  const userId = user?.id
  const [overview, setOverview] = useState<UserAccountOverview | null>(null)
  const [channels, setChannels] = useState<PublicPaymentChannel[]>([])
  const [targetLevelId, setTargetLevelId] = useState("")
  const [paymentMethod, setPaymentMethod] = useState<"BALANCE" | "ONLINE">("BALANCE")
  const [channelCode, setChannelCode] = useState("")
  const [transaction, setTransaction] = useState<AccountTransaction | null>(null)
  const [payment, setPayment] = useState<AccountPaymentCreateResult | null>(null)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState("")
  const [searchResults, setSearchResults] = useState<ProductCard[]>([])
  const [searching, setSearching] = useState(false)
  const [product, setProduct] = useState<ProductDetail | null>(null)
  const [specId, setSpecId] = useState("")
  const [priceCheck, setPriceCheck] = useState<ProductPriceCheck | null>(null)
  const [priceLoading, setPriceLoading] = useState(false)
  const transactionId = typeof window === "undefined" ? null
    : new URLSearchParams(window.location.search).get("payment_transaction")

  const selectedUpgrade = useMemo(
    () => overview?.upgrades.find((item) => item.level_id === targetLevelId) ?? overview?.upgrades[0] ?? null,
    [overview, targetLevelId],
  )

  const loadOverview = useCallback(async () => {
    const next = await requestOrThrow(() => userAccountApi.getOverview())
    setOverview(next)
    const selected = next.upgrades.find((item) => item.level_id === targetLevelId) ?? next.upgrades[0] ?? null
    setTargetLevelId(selected?.level_id ?? "")
    const enoughBalance = selected ? Number(next.balance ?? 0) >= selected.amount : false
    if (!enoughBalance) setPaymentMethod("ONLINE")
    else setPaymentMethod("BALANCE")
  }, [targetLevelId])

  const refreshProfile = useCallback(async () => {
    const profile = await requestOrThrow(() => userApi.getProfile())
    setUser(profile)
  }, [setUser])

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn || !userId) { setLoading(false); return }
    let cancelled = false
    Promise.all([userAccountApi.getOverview(), paymentApi.getChannels()]).then(([nextOverview, nextChannels]) => {
      if (cancelled) return
      setOverview(nextOverview)
      const first = nextOverview.upgrades[0]
      setTargetLevelId(first?.level_id ?? "")
      setPaymentMethod(first && Number(nextOverview.balance ?? 0) >= first.amount ? "BALANCE" : "ONLINE")
      setChannels(nextChannels)
      setChannelCode(nextChannels[0]?.channel_code ?? "")
      setLoading(false)
    }).catch(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [authLoaded, isLoggedIn, userId])

  useEffect(() => {
    if (!transactionId || !isLoggedIn) return
    let cancelled = false
    requestOrThrow(() => userAccountApi.getTransaction(transactionId)).then(async (value) => {
      if (cancelled) return
      setTransaction(value)
      setPayment({
        payment_id: value.payment_id,
        account_transaction_id: value.id,
        transaction_id: value.id,
        order_id: null,
        status: value.payment_status ?? "PENDING",
        creation_status: value.creation_status,
        payment_method: value.payment_method,
        payment_type: value.payment_type,
        payment_channel_code: value.channel_code,
        next_action: value.next_action,
        expires_at: value.expires_at ?? null,
      })
      const refreshed = await requestOrThrow(() => userAccountApi.refreshTransaction(transactionId))
      if (!cancelled) {
        setTransaction(refreshed)
        setPayment((current) => current ? { ...current, status: refreshed.payment_status ?? current.status, creation_status: refreshed.creation_status ?? current.creation_status, next_action: refreshed.next_action ?? current.next_action } : current)
      }
    }).catch((error) => { if (!cancelled) toast.error(getApiErrorMessage(error, t)) })
    return () => { cancelled = true }
  }, [transactionId, isLoggedIn, t])

  const activeTransactionId = transaction?.id
  const activeTransactionStatus = transaction?.status

  useEffect(() => {
    if (!activeTransactionId || activeTransactionStatus === "PENDING") return
    void refreshProfile().catch(() => {})
    void loadOverview().catch(() => {})
  }, [activeTransactionId, activeTransactionStatus, loadOverview, refreshProfile])

  useEffect(() => {
    if (!activeTransactionId || activeTransactionStatus !== "PENDING") return
    let cancelled = false
    const timer = window.setTimeout(() => {
      requestOrThrow(() => userAccountApi.refreshTransaction(activeTransactionId)).then((next) => {
        if (!cancelled) {
          setTransaction(next)
          setPayment((current) => current ? { ...current, status: next.payment_status ?? current.status, creation_status: next.creation_status ?? current.creation_status, next_action: next.next_action ?? current.next_action } : current)
        }
      }).catch(() => {})
    }, 3000)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [activeTransactionId, activeTransactionStatus])

  const selectedProductId = product?.id
  const selectedProductHasSpecs = product?.spec_enabled
  useEffect(() => {
    if (!selectedProductId) { setPriceCheck(null); return }
    if (selectedProductHasSpecs && !specId) { setPriceCheck(null); return }
    let cancelled = false
    setPriceLoading(true)
    requestOrThrow(() => userAccountApi.checkProductPrice(selectedProductId, specId || null))
      .then((result) => { if (!cancelled) setPriceCheck(result) })
      .catch((error) => { if (!cancelled) { setPriceCheck(null); toast.error(getApiErrorMessage(error, t)) } })
      .finally(() => { if (!cancelled) setPriceLoading(false) })
    return () => { cancelled = true }
  }, [selectedProductId, selectedProductHasSpecs, specId, user?.user_level_id, t])

  const submitUpgrade = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedUpgrade) return
    setSaving(true)
    try {
      const result = await requestOrThrow(() => userAccountApi.beginUpgrade({
        target_level_id: selectedUpgrade.level_id,
        payment_method: paymentMethod,
        channel_code: paymentMethod === "ONLINE" ? channelCode : undefined,
        device: detectPaymentDevice(),
      }))
      if (!result.payment_id) {
        const id = result.transaction_id || result.account_transaction_id
        if (id) setTransaction(await requestOrThrow(() => userAccountApi.getTransaction(id)))
        await refreshProfile()
        await loadOverview()
        toast.success(t("my.purchaseCompleted"))
      } else {
        setPayment(result)
        const id = result.account_transaction_id || result.transaction_id
        if (id) setTransaction(await requestOrThrow(() => userAccountApi.getTransaction(id)))
      }
    } catch (error: unknown) {
      const message = getApiErrorMessage(error, t)
      toast.error(message)
      if (message.includes("余额不足")) setPaymentMethod("ONLINE")
    } finally { setSaving(false) }
  }

  const searchProducts = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!search.trim()) { setSearchResults([]); return }
    setSearching(true)
    try {
      const result = await requestOrThrow(() => productApi.getList({ keyword: search.trim(), page: 1, page_size: 20 }))
      setSearchResults(result.list)
    } catch (error: unknown) { toast.error(getApiErrorMessage(error, t)) }
    finally { setSearching(false) }
  }

  const chooseProduct = async (item: ProductCard) => {
    try {
      const detail = await requestOrThrow(() => productApi.getDetail(item.id))
      setProduct(detail)
      setSearchResults([])
      setSpecId(detail.spec_enabled ? detail.specs.find((spec) => spec.is_visible !== false)?.id ?? "" : "")
    } catch (error: unknown) { toast.error(getApiErrorMessage(error, t)) }
  }

  const refreshActiveTransaction = async () => {
    if (!transaction) return
    try {
      const next = await requestOrThrow(() => userAccountApi.refreshTransaction(transaction.id))
      setTransaction(next)
      setPayment((current) => current ? { ...current, status: next.payment_status ?? current.status, creation_status: next.creation_status ?? current.creation_status, next_action: next.next_action ?? current.next_action } : current)
    } catch (error) { toast.error(getApiErrorMessage(error, t)) }
  }

  if (!authLoaded || loading) return <div className="account-page space-y-4"><div className="h-8 w-36 animate-pulse rounded-full bg-foreground/[0.07]" /><div className="h-40 animate-pulse rounded-card bg-foreground/[0.05]" /></div>
  if (!isLoggedIn || !user) return <div className="account-page space-y-4"><h1 className="text-2xl font-semibold">{t("my.upgradeTitle")}</h1><Link className="text-sm text-primary underline" href="/login?redirect=/my/levels">{t("nav.login")}</Link></div>

  return (
    <div className="account-page space-y-5 sm:space-y-6">
      <header className="flex items-center gap-3">
        <Link href="/my" className="inline-flex size-10 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.05] hover:text-foreground" aria-label={t("my.backToAccount")}><ArrowLeft className="size-5" aria-hidden="true" /></Link>
        <div><h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("my.upgradeTitle")}</h1></div>
      </header>

      <section className="content-surface space-y-4 rounded-card p-4 sm:p-5">
        <div className="flex items-center justify-between gap-4">
          <div className="min-w-0"><p className="text-xs text-muted-foreground">{t("my.currentLevel")}</p><p className="mt-1 truncate text-lg font-semibold text-foreground">{overview?.current_level_name ?? user.user_level_name ?? "—"}</p></div>
          <div className="shrink-0 text-right"><p className="text-xs text-muted-foreground">{t("my.balance")}</p><p className="mt-1 font-semibold tabular-nums text-foreground">{formatYuan(user.balance)}</p></div>
        </div>

        {selectedUpgrade ? (
          <form action="/api/user/account/level-upgrades" method="post" onSubmit={(event) => void submitUpgrade(event)} className="space-y-4" aria-labelledby="upgrade-form-title">
            <h2 id="upgrade-form-title" className="sr-only">{t("my.upgradeTitle")}</h2>
            <label htmlFor="target-level" className="block space-y-1.5 text-sm font-medium">{t("my.targetLevel")}
              <select id="target-level" name="target_level_id" value={selectedUpgrade.level_id} onChange={(event) => {
                const target = overview?.upgrades.find((item) => item.level_id === event.target.value)
                setTargetLevelId(event.target.value)
                setPaymentMethod(target && Number(user.balance) >= target.amount ? "BALANCE" : "ONLINE")
              }} className="h-11 w-full rounded-control border border-input bg-background px-3 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                {overview?.upgrades.map((item) => <option key={item.level_id} value={item.level_id}>{item.name} · {formatYuan(item.amount)}</option>)}
              </select>
            </label>
            <p className="flex items-center justify-between rounded-control bg-surface-muted px-3 py-3 text-sm"><span className="text-muted-foreground">{t("my.upgradeFee")}</span><strong className="text-base tabular-nums text-foreground">{formatYuan(selectedUpgrade.amount)}</strong></p>

            {selectedUpgrade.amount > 0 && <fieldset className="space-y-2">
              <legend className="text-sm font-medium text-foreground">{t("product.paymentMethod")}</legend>
              <div className="grid grid-cols-2 gap-2">
                {(["BALANCE", "ONLINE"] as const).map((method) => {
                  const selected = paymentMethod === method
                  const insufficient = method === "BALANCE" && Number(user.balance) < selectedUpgrade.amount
                  return <label key={method} className={`flex min-h-12 cursor-pointer items-center gap-2 rounded-control border px-3 text-sm font-medium transition-colors ${selected ? "border-primary/30 bg-primary/10 text-primary" : "border-border-subtle text-foreground hover:bg-surface-muted"}`}>
                    <input type="radio" name="payment_method" value={method} checked={selected} onChange={() => setPaymentMethod(method)} className="accent-primary" />
                    <span>{method === "BALANCE" ? t("my.payBalance") : t("my.payOnline")}</span>
                    {insufficient && <span className="ml-auto text-[11px] text-muted-foreground">{t("product.balanceInsufficient")}</span>}
                  </label>
                })}
              </div>
            </fieldset>}

            {selectedUpgrade.amount > 0 && paymentMethod === "ONLINE" && (channels.length ? <PaymentSelector channels={channels} selected={channelCode} onSelect={setChannelCode} /> : <p className="text-sm text-muted-foreground">{t("product.noOnlineChannels")}</p>)}
            <Button type="submit" disabled={saving || (selectedUpgrade.amount > 0 && paymentMethod === "ONLINE" && (!channels.length || !channelCode))} className="w-full sm:w-auto">
              <Crown className="size-4" aria-hidden="true" />{saving ? t("admin.processing") : selectedUpgrade.amount === 0 ? t("my.freeUpgrade") : t("my.confirmUpgrade")}
            </Button>
          </form>
        ) : <p className="text-sm text-muted-foreground">{t("my.upgradeUnavailable")}</p>}

        {payment && transaction?.status === "PENDING" && (
          <div className="rounded-card border border-border-subtle bg-surface-muted/50 p-4">
            <PaymentActionRenderer action={payment.next_action}
              loadState={payment.next_action ? "ready" : payment.creation_status === "INTENT" || payment.creation_status === "SUBMITTING" ? "loading" : "ready"}
              retryAction={<Button type="button" variant="outline" onClick={() => void refreshActiveTransaction()}>{t("my.refreshPayment")}</Button>}
              retryStatusAction={<Button type="button" variant="outline" onClick={() => void refreshActiveTransaction()}>{t("my.refreshPayment")}</Button>} />
            <p className="mt-3 text-center text-xs text-muted-foreground">{t("my.purchasePending")}</p>
          </div>
        )}
        {transaction?.status === "COMPLETED" && <p className="flex items-center gap-2 text-sm font-medium text-emerald-600"><Check className="size-4" aria-hidden="true" />{t("my.purchaseCompleted")}</p>}
        {transaction?.status === "RECOVERED" && <p className="text-sm text-muted-foreground">{transaction.recovery_reason || t("my.upgradeRecovered")}</p>}
      </section>

      <section className="content-surface space-y-4 rounded-card p-4 sm:p-5">
        <h2 className="text-lg font-semibold text-foreground">{t("my.productPriceSearch")}</h2>
        <search>
          <form action="/products" method="get" onSubmit={(event) => void searchProducts(event)} className="flex gap-2">
            <label className="sr-only" htmlFor="price-search">{t("my.productSearch")}</label>
            <input id="price-search" name="keyword" type="search" autoComplete="off" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("my.productSearch")} className="h-11 min-w-0 flex-1 rounded-control border border-input bg-background px-3 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
            <Button type="submit" variant="outline" disabled={searching || !search.trim()} aria-label={t("my.productSearch")}><Search className="size-4" aria-hidden="true" />{searching ? t("admin.processing") : t("nav.search")}</Button>
          </form>
        </search>
        {searchResults.length > 0 && <ul className="max-h-64 divide-y divide-border-subtle overflow-y-auto rounded-control border border-border-subtle bg-surface">{searchResults.map((item) => <li key={item.id}><button type="button" onClick={() => void chooseProduct(item)} className="flex min-h-12 w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-muted"><span className="min-w-0 flex-1 truncate font-medium">{item.title}</span><span className="shrink-0 tabular-nums text-muted-foreground">{formatYuan(item.price)}</span></button></li>)}</ul>}
        {product && <div className="space-y-4 rounded-control border border-border-subtle p-3 sm:p-4">
          <div className="flex items-start justify-between gap-3"><h3 className="min-w-0 break-words font-semibold text-foreground">{product.title}</h3><button type="button" className="shrink-0 text-xs text-muted-foreground underline underline-offset-2" onClick={() => { setProduct(null); setPriceCheck(null); setSpecId("") }}>{t("my.selectProduct")}</button></div>
          {product.spec_enabled && <label className="block space-y-1.5 text-sm font-medium">{t("my.selectSpec")}
            <select value={specId} onChange={(event) => setSpecId(event.target.value)} className="h-11 w-full rounded-control border border-input bg-background px-3 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <option value="">{t("my.selectSpec")}</option>
              {product.specs.filter((spec) => spec.is_visible !== false).map((spec) => <option key={spec.id} value={spec.id}>{spec.name}</option>)}
            </select>
          </label>}
          {priceLoading ? <div className="h-20 animate-pulse rounded-control bg-foreground/[0.05]" role="status" aria-label={t("common.loading")} /> : priceCheck && <ul className="divide-y divide-border-subtle">
            <li className="flex min-h-11 items-center justify-between gap-3 text-sm"><span className="text-muted-foreground">{priceCheck.current_level_name} · {t("my.currentPrice")}</span><strong className="tabular-nums text-foreground">{formatYuan(priceCheck.current_price)}</strong></li>
            {priceCheck.levels.map((item) => <li key={item.level_id} className="flex min-h-11 items-center justify-between gap-3 text-sm"><span className="min-w-0 flex-1 truncate text-foreground">{item.level_name}</span><span className="flex shrink-0 items-center gap-2"><strong className="tabular-nums text-foreground">{formatYuan(item.price)}</strong>{item.saving > 0 ? <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-600 dark:text-emerald-400"><TrendingDown className="size-3.5" aria-hidden="true" />{formatYuan(item.saving)}</span> : item.saving < 0 ? <span className="text-xs text-muted-foreground">+{formatYuan(Math.abs(item.saving))}</span> : null}</span></li>)}
          </ul>}
          {priceCheck?.levels.length === 0 && !priceLoading && <p className="text-sm text-muted-foreground">{t("my.noPublicPrices")}</p>}
        </div>}
      </section>
    </div>
  )
}
