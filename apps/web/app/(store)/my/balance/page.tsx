"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { ArrowLeft, Check, Clock3, Wallet } from "lucide-react"
import { toast } from "sonner"
import { PaymentActionRenderer } from "@/components/shared/payment-action-renderer"
import { PaymentSelector } from "@/components/shared/payment-selector"
import { Button } from "@/components/ui/button"
import { useAuth, useLocale } from "@/lib/context"
import { detectPaymentDevice, formatDateTime, formatYuan } from "@/lib/utils"
import { getApiErrorMessage, paymentApi, requestOrThrow, userAccountApi, userApi } from "@/services/api"
import type { AccountPaymentCreateResult, AccountTransaction, BalanceTransaction, PublicPaymentChannel } from "@/types"

function transactionLabel(type: BalanceTransaction["type"], t: (key: import("@/lib/i18n").TranslationKey) => string) {
  if (type === "RECHARGE") return t("my.balanceTypeRecharge")
  if (type === "ORDER_PAYMENT") return t("my.balanceTypeOrder")
  if (type === "REFUND") return t("my.balanceTypeRefund")
  if (type === "LEVEL_UPGRADE") return t("my.balanceTypeUpgrade")
  if (type === "SUBSITE_PROFIT" || type === "SUBSITE_PROFIT_REVERSAL" || type === "SUBSITE_UPGRADE_PROFIT") return t("my.balanceTypeSubsite")
  if (type === "ADMIN_ADJUSTMENT") return t("my.balanceTypeAdmin")
  return type === "SUBSITE_OPENING" ? t("my.balanceTypeOrder") : type
}

function transactionDelta(item: BalanceTransaction) {
  if (item.type === "LEVEL_UPGRADE" && item.delta === 0) return formatYuan(item.amount)
  return `${item.delta > 0 ? "+" : item.delta < 0 ? "−" : ""}${formatYuan(Math.abs(item.delta))}`
}

export default function MyBalancePage() {
  const { t, locale } = useLocale()
  const device = detectPaymentDevice()
  const { user, isLoggedIn, authLoaded, setUser } = useAuth()
  const userId = user?.id
  const [overview, setOverview] = useState<Awaited<ReturnType<typeof userAccountApi.getOverview>> | null>(null)
  const [channels, setChannels] = useState<PublicPaymentChannel[]>([])
  const [selectedChannel, setSelectedChannel] = useState("")
  const [amount, setAmount] = useState("")
  const [payment, setPayment] = useState<AccountPaymentCreateResult | null>(null)
  const [transaction, setTransaction] = useState<AccountTransaction | null>(null)
  const [busy, setBusy] = useState(false)
  const [history, setHistory] = useState<BalanceTransaction[]>([])
  const [historyTotal, setHistoryTotal] = useState(0)
  const [historyPage, setHistoryPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const queryTransactionId = typeof window === "undefined" ? null
    : new URLSearchParams(window.location.search).get("payment_transaction")
  const queryLedgerId = typeof window === "undefined" ? null
    : new URLSearchParams(window.location.search).get("ledger")
  const [focusedLedger, setFocusedLedger] = useState<string | null>(null)

  const loadHistory = useCallback(async (page: number, append = false) => {
    const result = await requestOrThrow(() => userAccountApi.getBalanceTransactions({ page, page_size: 20 }))
    setHistory((current) => append ? [...current, ...result.list] : result.list)
    setHistoryTotal(result.pagination.total)
    setHistoryPage(page)
  }, [])

  const refreshProfile = useCallback(async () => {
    const profile = await requestOrThrow(() => userApi.getProfile())
    setUser(profile)
  }, [setUser])

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn || !userId) { setLoading(false); return }
    let cancelled = false
    const historyRequest = queryLedgerId
      ? userAccountApi.getBalanceTransactionLocation(queryLedgerId).then(location =>
          userAccountApi.getBalanceTransactions({ page: location.page, page_size: 20 }).then(result => ({ result, page: location.page, id: location.transaction.id })))
          .catch(() => userAccountApi.getBalanceTransactions({ page: 1, page_size: 20 }).then(result => ({ result, page: 1, id: null })))
      : userAccountApi.getBalanceTransactions({ page: 1, page_size: 20 }).then(result => ({ result, page: 1, id: null }))
    Promise.all([
      requestOrThrow(() => userAccountApi.getOverview()),
      requestOrThrow(() => paymentApi.getChannels()),
      historyRequest,
    ]).then(([nextOverview, nextChannels, locatedHistory]) => {
      if (cancelled) return
      setOverview(nextOverview)
      setChannels(nextChannels)
      setSelectedChannel(nextChannels[0]?.channel_code ?? "")
      setHistory(locatedHistory.result.list)
      setHistoryTotal(locatedHistory.result.pagination.total)
      setHistoryPage(locatedHistory.page)
      setFocusedLedger(locatedHistory.id)
      setLoading(false)
    }).catch(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [authLoaded, isLoggedIn, userId, queryLedgerId])

  useEffect(() => {
    if (loading || !focusedLedger) return
    document.getElementById(`balance-entry-${focusedLedger}`)?.scrollIntoView({ block: "center", behavior: "auto" })
  }, [focusedLedger, history, loading])

  useEffect(() => {
    if (!queryTransactionId || !isLoggedIn) return
    let cancelled = false
    requestOrThrow(() => userAccountApi.getTransaction(queryTransactionId))
      .then(async (value) => {
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
        const refreshed = await requestOrThrow(() => userAccountApi.refreshTransaction(queryTransactionId))
        if (!cancelled) {
          setTransaction(refreshed)
          setPayment((current) => current ? { ...current, status: refreshed.payment_status ?? current.status, creation_status: refreshed.creation_status ?? current.creation_status, next_action: refreshed.next_action ?? current.next_action } : current)
        }
      })
      .catch((error) => { if (!cancelled) toast.error(getApiErrorMessage(error, t)) })
    return () => { cancelled = true }
  }, [queryTransactionId, isLoggedIn, t])

  const activeTransactionId = transaction?.id
  const activeTransactionStatus = transaction?.status
  const enteredAmount = amount.trim() ? Number(amount) : Number.NaN
  const canSubmitRecharge = !!overview && Number.isFinite(enteredAmount)
    && enteredAmount >= overview.recharge_min_amount && enteredAmount <= overview.recharge_max_amount

  useEffect(() => {
    if (!activeTransactionId || activeTransactionStatus === "PENDING") return
    void refreshProfile().catch(() => {})
    void loadHistory(1).catch(() => {})
  }, [activeTransactionId, activeTransactionStatus, loadHistory, refreshProfile])

  useEffect(() => {
    if (!activeTransactionId || activeTransactionStatus !== "PENDING") return
    let cancelled = false
    const timer = window.setTimeout(() => {
      requestOrThrow(() => userAccountApi.refreshTransaction(activeTransactionId))
        .then((next) => {
          if (cancelled) return
          setTransaction(next)
          setPayment((current) => current ? { ...current, status: next.payment_status ?? current.status, creation_status: next.creation_status ?? current.creation_status, next_action: next.next_action ?? current.next_action } : current)
        })
        .catch(() => {})
    }, 3000)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [activeTransactionId, activeTransactionStatus])

  const refreshActiveTransaction = useCallback(async () => {
    if (!transaction) return
    try {
      const next = await requestOrThrow(() => userAccountApi.refreshTransaction(transaction.id))
      setTransaction(next)
      setPayment((current) => current ? { ...current, status: next.payment_status ?? current.status, creation_status: next.creation_status ?? current.creation_status, next_action: next.next_action ?? current.next_action } : current)
    } catch (error) { toast.error(getApiErrorMessage(error, t)) }
  }, [transaction, t])

  const submitRecharge = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!overview?.recharge_enabled || !selectedChannel) return
    setBusy(true)
    try {
      const result = await requestOrThrow(() => userAccountApi.beginRecharge({
        amount: Number(amount), channel_code: selectedChannel, device,
      }))
      setPayment(result)
      const id = result.account_transaction_id || result.transaction_id
      if (id) setTransaction(await requestOrThrow(() => userAccountApi.getTransaction(id)))
      toast.success(t("my.purchasePending"))
    } catch (error: unknown) {
      toast.error(getApiErrorMessage(error, t))
    } finally { setBusy(false) }
  }

  if (!authLoaded || loading) return <div className="account-page space-y-4"><div className="h-8 w-36 animate-pulse rounded-full bg-foreground/[0.07]" /><div className="h-40 animate-pulse rounded-card bg-foreground/[0.05]" /></div>
  if (!isLoggedIn || !user) return <div className="account-page space-y-4"><h1 className="text-2xl font-semibold">{t("my.rechargeTitle")}</h1><Link className="text-sm text-primary underline" href="/login?redirect=/my/balance">{t("nav.login")}</Link></div>

  return (
    <div className="account-page space-y-4 sm:space-y-5">
      <header className="flex items-center gap-3">
        <Link href="/my" className="inline-flex size-10 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.05] hover:text-foreground" aria-label={t("my.backToAccount")}><ArrowLeft className="size-5" aria-hidden="true" /></Link>
        <div><h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("my.rechargeTitle")}</h1><p className="text-xs text-muted-foreground">{t("my.balance")}: {user.unlimited ? "∞" : formatYuan(user.balance)}</p></div>
      </header>

      {overview?.recharge_enabled && !user.unlimited && (
        <section className="content-surface space-y-5 rounded-card p-4 sm:p-5">
          <form action="/api/user/account/recharges" method="post" onSubmit={(event) => void submitRecharge(event)} className="space-y-5" aria-labelledby="recharge-heading">
            <h2 id="recharge-heading" className="sr-only">{t("my.rechargeTitle")}</h2>
            <div className="max-w-sm space-y-1.5">
              <label htmlFor="recharge-amount" className="block text-sm font-medium text-foreground">{t("my.rechargeAmount")}</label>
              <input id="recharge-amount" name="amount" type="number" inputMode="decimal" min={overview.recharge_min_amount} max={overview.recharge_max_amount} step="0.01" required value={amount} onChange={(event) => setAmount(event.target.value)} aria-describedby="recharge-limit-hint" className="h-11 w-full rounded-control border border-input bg-background px-3 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
              <p id="recharge-limit-hint" className="text-xs text-muted-foreground">{formatYuan(overview.recharge_min_amount)} – {formatYuan(overview.recharge_max_amount)}</p>
            </div>

            <fieldset className="space-y-3">
              <legend className="text-sm font-medium text-foreground">{t("product.paymentMethod")}</legend>
              {channels.length ? <PaymentSelector channels={channels} selected={selectedChannel} onSelect={setSelectedChannel} /> : <p className="text-sm text-muted-foreground">{t("product.noOnlineChannels")}</p>}
            </fieldset>
            <Button type="submit" disabled={busy || !channels.length || !selectedChannel || !canSubmitRecharge} className="w-full sm:w-auto">
              <Wallet className="size-4" aria-hidden="true" />{busy ? t("admin.processing") : canSubmitRecharge ? t("my.confirmRecharge").replace("{amount}", formatYuan(enteredAmount)) : t("my.recharge")}
            </Button>
          </form>

          {payment && transaction?.status === "PENDING" && (
            <div className="rounded-card border border-border-subtle bg-surface-muted/50 p-4">
              <PaymentActionRenderer action={payment.next_action}
                loadState={payment.next_action ? "ready" : payment.creation_status === "INTENT" || payment.creation_status === "SUBMITTING" ? "loading" : "ready"}
                retryAction={<Button type="button" variant="outline" onClick={() => void refreshActiveTransaction()}>{t("my.refreshPayment")}</Button>}
                retryStatusAction={<Button type="button" variant="outline" onClick={() => void refreshActiveTransaction()}>{t("my.refreshPayment")}</Button>} />
              <div className="mt-3 flex items-center justify-center gap-2 text-xs text-muted-foreground" role="status"><Clock3 className="size-3.5" aria-hidden="true" />{t("my.purchasePending")}</div>
            </div>
          )}
          {transaction?.status === "COMPLETED" && <p className="flex items-center gap-2 text-sm font-medium text-emerald-600"><Check className="size-4" aria-hidden="true" />{t("my.purchaseCompleted")}</p>}
          {transaction?.status === "RECOVERED" && <p className="text-sm text-muted-foreground">{transaction.recovery_reason || t("my.purchaseRecovered")}</p>}
        </section>
      )}

          {!overview?.recharge_enabled && !user.unlimited && <p className="text-sm text-muted-foreground">{t("my.rechargeUnavailable")}</p>}

      <section id="history" className="space-y-3 scroll-mt-20">
        <h2 className="text-lg font-semibold text-foreground">{t("my.balanceHistory")}</h2>
        {history.length === 0 ? <p className="py-8 text-center text-sm text-muted-foreground">{t("my.balanceEmpty")}</p> : (
          <ul className="divide-y divide-border-subtle rounded-card border border-border-subtle bg-surface px-4">
            {history.map((item) => (
              <li id={`balance-entry-${item.id}`} key={item.id} className={`flex min-w-0 items-center justify-between gap-3 rounded-control py-3 transition-colors ${item.id === focusedLedger ? "bg-primary/5 px-3 ring-1 ring-primary/25" : ""}`}>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-foreground">{transactionLabel(item.type, t)}</p>
                  <p className="mt-0.5 truncate text-xs text-muted-foreground">{item.reason || transactionLabel(item.type, t)}</p>
                  <time className="mt-1 block text-[11px] text-muted-foreground" dateTime={item.created_at}>{formatDateTime(item.created_at, locale)}</time>
                </div>
                <span className={`shrink-0 text-sm font-semibold tabular-nums ${item.delta > 0 ? "text-emerald-600 dark:text-emerald-400" : item.delta < 0 ? "text-foreground" : "text-muted-foreground"}`}>{transactionDelta(item)}</span>
              </li>
            ))}
          </ul>
        )}
        {history.length < historyTotal && <Button type="button" variant="outline" className="w-full" onClick={() => void loadHistory(historyPage + 1, true)}>{t("common.loadMore")}</Button>}
      </section>
    </div>
  )
}
