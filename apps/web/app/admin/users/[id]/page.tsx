"use client"

import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { useCallback, useEffect, useMemo, useState } from "react"
import { ArrowLeft, Edit3, KeyRound, Plus, ShieldCheck, UserCheck, UserX } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  AdminPagination,
  ConfirmAlert,
  DataTableShell,
  FormSection,
  PageHeader,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import { formatCents, formatYuanFromCents, formatYuanValue, MAX_BALANCE_CENTS, parseMoneyValueToCents, parsePositiveMoneyToCents } from "@/lib/money"
import { adminUserApi, adminUserLevelApi, requestOrThrow } from "@/services/api"
import type { AdminOperationLogDetail, AdminUserDetail, AdminUserOrder, BalanceTransaction, UserLevel } from "@/types"

type DetailTab = "overview" | "orders" | "balance" | "logs"
type RecordTab = Exclude<DetailTab, "overview">
const PAGE_SIZE = 10

interface AdminUserDetailPageProps {
  embedded?: boolean
  onDirtyChange?: (dirty: boolean) => void
}

export default function AdminUserDetailPage({ embedded = false, onDirtyChange }: AdminUserDetailPageProps = {}) {
  const { t, locale } = useLocale()
  const router = useRouter()
  const params = useParams<{ id: string }>()
  const userId = params.id
  const [user, setUser] = useState<AdminUserDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [activeTab, setActiveTab] = useState<DetailTab>("overview")
  const [recordPage, setRecordPage] = useState(1)
  const [recordTotal, setRecordTotal] = useState(0)
  const [orders, setOrders] = useState<AdminUserOrder[]>([])
  const [transactions, setTransactions] = useState<BalanceTransaction[]>([])
  const [logs, setLogs] = useState<AdminOperationLogDetail[]>([])
  const [recordsLoading, setRecordsLoading] = useState(false)
  const [recordsError, setRecordsError] = useState(false)

  const [editOpen, setEditOpen] = useState(false)
  const [editEmail, setEditEmail] = useState("")
  const [editLoading, setEditLoading] = useState(false)
  const [balanceOpen, setBalanceOpen] = useState(false)
  const [balanceType, setBalanceType] = useState<"INCREASE" | "DECREASE">("INCREASE")
  const [balanceAmount, setBalanceAmount] = useState("")
  const [balanceReason, setBalanceReason] = useState("")
  const [balanceLoading, setBalanceLoading] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)
  const [newPassword, setNewPassword] = useState("")
  const [resetLoading, setResetLoading] = useState(false)
  const [statusOpen, setStatusOpen] = useState(false)
  const [statusLoading, setStatusLoading] = useState(false)
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [levelLoading, setLevelLoading] = useState(false)

  const loadUser = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminUserApi.getDetail(userId))
      setUser(data)
      setEditEmail(data.email)
    } catch {
      setUser(null)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [userId])

  const loadRecords = useCallback(async (tab: RecordTab, page: number) => {
    setRecordsLoading(true)
    setRecordsError(false)
    try {
      if (tab === "orders") {
        const data = await requestOrThrow(() => adminUserApi.getOrders(userId, { page, page_size: PAGE_SIZE }))
        setOrders(data.list)
        setRecordTotal(data.pagination.total)
      } else if (tab === "balance") {
        const data = await requestOrThrow(() => adminUserApi.getBalanceTransactions(userId, { page, page_size: PAGE_SIZE }))
        setTransactions(data.list)
        setRecordTotal(data.pagination.total)
      } else {
        const data = await requestOrThrow(() => adminUserApi.getOperationLogs(userId, { page, page_size: PAGE_SIZE }))
        setLogs(data.list)
        setRecordTotal(data.pagination.total)
      }
    } catch {
      if (tab === "orders") setOrders([])
      if (tab === "balance") setTransactions([])
      if (tab === "logs") setLogs([])
      setRecordTotal(0)
      setRecordsError(true)
    } finally {
      setRecordsLoading(false)
    }
  }, [userId])

  useEffect(() => { void loadUser() }, [loadUser])
  useEffect(() => { void adminUserLevelApi.getList().then(setLevels).catch(() => setLevels([])) }, [])
  useEffect(() => {
    if (activeTab !== "overview") void loadRecords(activeTab, recordPage)
  }, [activeTab, loadRecords, recordPage])

  const previewBalanceCents = useMemo(() => {
    const current = parseMoneyValueToCents(user?.balance ?? 0) ?? 0
    const amount = parsePositiveMoneyToCents(balanceAmount)
    if (amount === null) return current
    return current + (balanceType === "INCREASE" ? amount : -amount)
  }, [balanceAmount, balanceType, user?.balance])

  const refreshRecords = useCallback(async (tab: RecordTab) => {
    // The request is intentionally explicit: changing the selected tab/page
    // is only a display concern and must not be the refresh trigger.
    await loadRecords(tab, 1)
    setActiveTab(tab)
    setRecordPage(1)
  }, [loadRecords])

  const retryActiveRecords = () => {
    if (activeTab !== "overview") void loadRecords(activeTab, recordPage)
  }

  const openEdit = () => {
    if (!user) return
    setEditEmail(user.email)
    setEditOpen(true)
  }

  const handleEdit = async () => {
    const email = editEmail.trim().toLowerCase()
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error(t("admin.userEmailInvalid"))
      return
    }
    setEditLoading(true)
    try {
      const data = await requestOrThrow(() => adminUserApi.updateProfile(userId, { email }))
      setUser(data)
      setEditOpen(false)
      await refreshRecords("logs")
      toast.success(t("admin.userProfileSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setEditLoading(false)
    }
  }

  const handleChangeLevel = async (userLevelId: string) => {
    if (!user || !userLevelId || userLevelId === user.user_level_id) return
    setLevelLoading(true)
    try {
      const level = await requestOrThrow(() => adminUserApi.changeLevel(user.id, userLevelId))
      setUser((current) => current ? { ...current, user_level_id: level.id, user_level_name: level.name } : current)
      await refreshRecords("logs")
      toast.success(t("admin.userLevelChanged"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setLevelLoading(false)
    }
  }

  const handleBalance = async () => {
    const amountCents = parsePositiveMoneyToCents(balanceAmount)
    const reason = balanceReason.trim()
    if (amountCents === null) {
      toast.error(t("admin.balanceAmountInvalid"))
      return
    }
    if (!reason) {
      toast.error(t("admin.balanceReasonRequired"))
      return
    }
    if (previewBalanceCents < 0) {
      toast.error(t("admin.balanceNegative"))
      return
    }
    if (previewBalanceCents > MAX_BALANCE_CENTS) {
      toast.error(t("admin.balanceExceedsMax"))
      return
    }
    setBalanceLoading(true)
    try {
      await requestOrThrow(() => adminUserApi.adjustBalance(userId, {
        type: balanceType,
        amount: formatCents(amountCents),
        reason,
      }))
      await loadUser()
      await refreshRecords("balance")
      setBalanceOpen(false)
      setBalanceAmount("")
      setBalanceReason("")
      toast.success(t("admin.balanceAdjusted"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setBalanceLoading(false)
    }
  }

  const handleReset = async () => {
    if (newPassword.length < 6 || newPassword.length > 72 || !/^[!-~]+$/.test(newPassword)) {
      toast.error(t("admin.passwordMinLength"))
      return
    }
    setResetLoading(true)
    try {
      await requestOrThrow(() => adminUserApi.resetPassword(userId, newPassword))
      setResetOpen(false)
      setNewPassword("")
      await refreshRecords("logs")
      toast.success(t("admin.passwordReset"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setResetLoading(false)
    }
  }

  const handleToggle = async () => {
    if (!user) return
    setStatusLoading(true)
    try {
      await requestOrThrow(() => adminUserApi.toggleStatus(userId, user.is_deleted === 0 ? 1 : 0))
      await loadUser()
      setStatusOpen(false)
      await refreshRecords("logs")
      toast.success(user.is_deleted === 0 ? t("admin.userDisabled") : t("admin.userEnabled"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setStatusLoading(false)
    }
  }

  const formDirty = Boolean(
    (editOpen && user && editEmail !== user.email)
    || (balanceOpen && (balanceAmount.trim() || balanceReason.trim()))
    || (resetOpen && newPassword.trim())
  )

  useEffect(() => {
    onDirtyChange?.(formDirty)
  }, [formDirty, onDirtyChange])

  if (loading) {
    return <AdminPage className={embedded ? "max-w-none" : undefined}>{!embedded && <PageHeader title={t("admin.userDetail")} />}<div className="h-64 animate-pulse rounded-card border border-border-subtle bg-surface-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  }
  if (error || !user) {
    return <AdminPage className={embedded ? "max-w-none" : undefined}>{!embedded && <PageHeader title={t("admin.userDetail")} />}<div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={() => void loadUser()}>{t("common.retry")}</Button></div></AdminPage>
  }

  const enabled = user.is_deleted === 0
  const tabs: { key: DetailTab; label: string }[] = [
    { key: "overview", label: t("admin.overview") },
    { key: "orders", label: t("admin.orderRecords") },
    { key: "balance", label: t("admin.balanceRecords") },
    { key: "logs", label: t("admin.managementRecords") },
  ]

  return (
    <AdminPage className={embedded ? "max-w-none gap-5" : undefined}>
      {!embedded && <PageHeader
        title={t("admin.userDetail")}
        description={t("admin.userDetailDesc")}
        actions={
          <Button type="button" variant="outline" onClick={() => router.push("/admin/users")}>
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />{t("admin.backToUsers")}
          </Button>
        }
      />}

      <section className="surface p-5 sm:p-6">
        <div className="flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
          <div className="flex min-w-0 items-start gap-4">
            <div className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"><ShieldCheck className="size-7" aria-hidden="true" /></div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2"><h2 className="truncate text-xl font-semibold">{user.username}</h2><StatusBadge label={enabled ? t("admin.normal") : t("admin.banned")} tone={enabled ? "success" : "danger"} /></div>
              <p className="mt-1 truncate text-sm text-muted-foreground">{user.email}</p>
              <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground"><span>{t("admin.userNumber")}: <strong className="font-mono text-foreground">{user.user_no}</strong></span><span>{t("admin.internalId")}: <span className="font-mono">{user.id}</span></span></div>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={openEdit}><Edit3 className="h-4 w-4" aria-hidden="true" />{t("admin.editProfile")}</Button>
            <Button type="button" variant="outline" onClick={() => { setBalanceType("INCREASE"); setBalanceAmount(""); setBalanceReason(""); setBalanceOpen(true) }}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.adjustBalance")}</Button>
            <Button type="button" variant="outline" onClick={() => { setNewPassword(""); setResetOpen(true) }}><KeyRound className="h-4 w-4" aria-hidden="true" />{t("admin.resetPassword")}</Button>
            <Button type="button" variant={enabled ? "destructive" : "outline"} onClick={() => setStatusOpen(true)}>{enabled ? <UserX className="h-4 w-4" aria-hidden="true" /> : <UserCheck className="h-4 w-4" aria-hidden="true" />}{enabled ? t("admin.ban") : t("admin.unban")}</Button>
          </div>
        </div>
      </section>

      <section className="surface p-5 sm:p-6">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div><h2 className="font-semibold">{t("admin.userLevel")}</h2><p className="mt-1 text-sm text-muted-foreground">{t("admin.userLevelImmediateHint")}</p></div>
          <select className="h-11 min-w-52 rounded-control border border-input bg-surface px-3 text-sm shadow-sm" value={user.user_level_id || ""} onChange={(event) => void handleChangeLevel(event.target.value)} disabled={levelLoading} aria-label={t("admin.userLevel")}>
            <option value="">{user.user_level_name || t("admin.systemNoData")}</option>
            {levels.filter((level) => level.enabled).map((level) => <option key={level.id} value={level.id}>{level.name}</option>)}
          </select>
        </div>
      </section>

      <div className="flex max-w-full gap-1 overflow-x-auto rounded-card border border-border-subtle bg-surface-muted p-1.5" role="tablist" aria-label={t("admin.userDetailTabs")}>
        {tabs.map((tab) => <button key={tab.key} type="button" role="tab" aria-selected={activeTab === tab.key} onClick={() => { setActiveTab(tab.key); setRecordPage(1) }} className={`whitespace-nowrap rounded-control px-3 py-2 text-sm font-medium transition-colors ${activeTab === tab.key ? "bg-surface text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}>{tab.label}</button>)}
      </div>

      {activeTab === "overview" && <Overview user={user} t={t} locale={locale} />}
      {activeTab === "orders" && <OrdersTab orders={orders} loading={recordsLoading} error={recordsError} onRetry={retryActiveRecords} page={recordPage} total={recordTotal} onPageChange={setRecordPage} t={t} locale={locale} />}
      {activeTab === "balance" && <BalanceTab transactions={transactions} loading={recordsLoading} error={recordsError} onRetry={retryActiveRecords} page={recordPage} total={recordTotal} onPageChange={setRecordPage} t={t} locale={locale} />}
      {activeTab === "logs" && <LogsTab logs={logs} loading={recordsLoading} error={recordsError} onRetry={retryActiveRecords} page={recordPage} total={recordTotal} onPageChange={setRecordPage} t={t} locale={locale} />}

      <Dialog open={editOpen} onOpenChange={(open) => { if (!editLoading) setEditOpen(open) }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{t("admin.editProfile")}</DialogTitle><DialogDescription>{t("admin.editProfileDesc")}</DialogDescription></DialogHeader>
          <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.emailLabel")}</span><Input type="email" value={editEmail} onChange={(event) => setEditEmail(event.target.value)} autoComplete="email" /></label>
          <p className="text-xs text-muted-foreground">{t("admin.profileReadonlyHint")}</p>
          <DialogFooter><Button type="button" variant="outline" onClick={() => setEditOpen(false)} disabled={editLoading}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleEdit()} disabled={editLoading}>{editLoading ? t("admin.processing") : t("admin.save")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={balanceOpen} onOpenChange={(open) => { if (!balanceLoading) setBalanceOpen(open) }}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{t("admin.adjustBalance")}</DialogTitle><DialogDescription>{t("admin.balanceAdjustmentDesc")}</DialogDescription></DialogHeader>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-card border border-border-subtle bg-surface-muted p-3"><p className="text-xs text-muted-foreground">{t("admin.currentBalance")}</p><p className="mt-1 text-xl font-semibold">{formatYuanValue(user.balance)}</p></div>
            <div className={`rounded-card border border-border-subtle p-3 ${previewBalanceCents < 0 ? "bg-destructive/10 text-destructive" : "bg-primary/10"}`}><p className="text-xs text-muted-foreground">{t("admin.balancePreview")}</p><p className="mt-1 text-xl font-semibold">{formatYuanFromCents(previewBalanceCents)}</p></div>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceAdjustmentType")}</span><select className="h-11 rounded-control border border-input bg-surface px-3 text-sm" value={balanceType} onChange={(event) => setBalanceType(event.target.value as "INCREASE" | "DECREASE")}><option value="INCREASE">{t("admin.balanceIncrease")}</option><option value="DECREASE">{t("admin.balanceDecrease")}</option></select></label>
            <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceAmount")}</span><Input type="text" inputMode="decimal" value={balanceAmount} onChange={(event) => setBalanceAmount(event.target.value)} placeholder="0.00" /></label>
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceReason")}</span><Textarea value={balanceReason} onChange={(event) => setBalanceReason(event.target.value)} placeholder={t("admin.balanceReasonPlaceholder")} maxLength={500} rows={4} /></label>
          <DialogFooter><Button type="button" variant="outline" onClick={() => setBalanceOpen(false)} disabled={balanceLoading}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleBalance()} disabled={balanceLoading || previewBalanceCents < 0 || previewBalanceCents > MAX_BALANCE_CENTS || !balanceAmount || !balanceReason.trim()}>{balanceLoading ? t("admin.processing") : t("admin.confirmAdjustment")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={resetOpen} onOpenChange={(open) => { if (!resetLoading) setResetOpen(open) }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{t("admin.resetPasswordTitle")}</DialogTitle><DialogDescription>{t("admin.resetPasswordDescription").replace("{username}", user.username)}</DialogDescription></DialogHeader>
          <Input type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} placeholder={t("admin.newPasswordPlaceholder")} minLength={6} maxLength={72} autoComplete="new-password" />
          <DialogFooter><Button type="button" variant="outline" onClick={() => setResetOpen(false)} disabled={resetLoading}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleReset()} disabled={resetLoading || newPassword.length < 6}>{resetLoading ? t("admin.processing") : t("admin.confirmReset")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert open={statusOpen} onOpenChange={(open) => { if (!statusLoading) setStatusOpen(open) }} title={enabled ? t("admin.confirmDisable") : t("admin.confirmEnable")} description={t("admin.userStatusDescription").replace("{action}", enabled ? t("admin.ban") : t("admin.unban")).replace("{username}", user.username)} confirmLabel={enabled ? t("admin.ban") : t("admin.unban")} cancelLabel={t("admin.cancel")} destructive={enabled} loading={statusLoading} onConfirm={() => void handleToggle()} />
    </AdminPage>
  )
}

type Translator = (key: Parameters<ReturnType<typeof useLocale>["t"]>[0]) => string

function Overview({ user, t, locale }: { user: AdminUserDetail; t: Translator; locale: string }) {
  return <div className="grid gap-6 lg:grid-cols-2"><FormSection title={t("admin.accountOverview")} description={t("admin.accountOverviewDesc")}><div className="grid gap-3 sm:grid-cols-2"><Info label={t("admin.userNumber")} value={String(user.user_no)} mono /><Info label={t("admin.usernameLabel")} value={user.username} /><Info label={t("admin.emailLabel")} value={user.email} /><Info label={t("admin.phoneLabel")} value={user.phone || t("admin.systemNoData")} /><Info label={t("admin.registeredAt")} value={formatDateTime(user.created_at, locale)} /></div></FormSection><FormSection title={t("admin.accountMetrics")}><div className="grid gap-3 sm:grid-cols-3"><Metric label={t("admin.currentBalance")} value={formatYuanValue(user.balance)} /><Metric label={t("admin.successfulOrders")} value={String(user.successful_order_count)} /><Metric label={t("admin.totalSpent")} value={formatYuanValue(user.total_spent)} /></div></FormSection></div>
}

function Info({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) { return <div className="rounded-card border border-border-subtle bg-surface-muted p-3"><p className="text-xs text-muted-foreground">{label}</p><p className={`mt-1 break-all text-sm font-medium ${mono ? "font-mono" : ""}`}>{value}</p></div> }
function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded-card border border-border-subtle bg-surface-muted p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="mt-1 text-xl font-semibold">{value}</p></div> }

function RecordShell({ children, loading, error, empty, onRetry, footer, colSpan, t }: { children: React.ReactNode; loading: boolean; error: boolean; empty: boolean; onRetry: () => void; footer: React.ReactNode; colSpan: number; t: Translator }) {
  return <DataTableShell footer={footer}><table className="w-full min-w-[900px] text-sm"><tbody>{loading && <TableLoadingState colSpan={colSpan} />}{!loading && error && <TableErrorState colSpan={colSpan} onRetry={onRetry} />}{!loading && !error && empty && <TableEmptyState colSpan={colSpan}>{t("admin.noRecordData")}</TableEmptyState>}{!loading && !error && !empty && children}</tbody></table></DataTableShell>
}

function OrdersTab({ orders, loading, error, onRetry, page, total, onPageChange, t, locale }: { orders: AdminUserOrder[]; loading: boolean; error: boolean; onRetry: () => void; page: number; total: number; onPageChange: (page: number) => void; t: Translator; locale: string }) {
  return <RecordShell loading={loading} error={error} empty={orders.length === 0} onRetry={onRetry} colSpan={6} t={t} footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={onPageChange} />}><tr className="border-b border-border bg-muted/30"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderProducts")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th></tr>{orders.map((order) => <tr key={order.id} className="border-b border-border/60 last:border-0"><td className="px-4 py-3 font-mono text-xs">{order.order_no}</td><td className="max-w-[260px] truncate px-4 py-3">{order.items.map((item) => `${item.product_title}${item.spec_name ? ` · ${item.spec_name}` : ""}`).join("、") || t("admin.systemNoData")}</td><td className="whitespace-nowrap px-4 py-3">{formatYuanValue(order.actual_amount ?? order.total_amount ?? 0)}</td><td className="px-4 py-3"><OrderStatusBadge status={order.status} /></td><td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td><td className="px-4 py-3 text-right"><Button asChild type="button" variant="ghost" size="sm"><Link href={`/admin/orders/${order.id}`}>{t("admin.viewDetail")}</Link></Button></td></tr>)}</RecordShell>
}

function BalanceTab({ transactions, loading, error, onRetry, page, total, onPageChange, t, locale }: { transactions: BalanceTransaction[]; loading: boolean; error: boolean; onRetry: () => void; page: number; total: number; onPageChange: (page: number) => void; t: Translator; locale: string }) {
  const typeLabel = (type: BalanceTransaction["type"]) => type === "ADMIN_ADJUSTMENT" ? t("admin.balanceAdminAdjustment") : type === "RECHARGE" ? t("admin.balanceRecharge") : type === "ORDER_PAYMENT" ? t("admin.balanceOrderPayment") : t("admin.balanceRefund")
  return <RecordShell loading={loading} error={error} empty={transactions.length === 0} onRetry={onRetry} colSpan={7} t={t} footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={onPageChange} />}><tr className="border-b border-border bg-muted/30"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceType")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceDelta")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceBeforeAfter")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceReason")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.operator")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.actions")}</th></tr>{transactions.map((transaction) => { const deltaCents = parseMoneyValueToCents(transaction.delta) ?? 0; return <tr key={transaction.id} className="border-b border-border/60 last:border-0"><td className="whitespace-nowrap px-4 py-3 text-xs text-muted-foreground">{formatDateTime(transaction.created_at, locale)}</td><td className="px-4 py-3"><StatusBadge label={typeLabel(transaction.type)} tone="info" /></td><td className={`whitespace-nowrap px-4 py-3 font-medium ${deltaCents >= 0 ? "text-emerald-600" : "text-destructive"}`}>{deltaCents >= 0 ? "+" : "-"}{formatYuanFromCents(Math.abs(deltaCents))}</td><td className="whitespace-nowrap px-4 py-3">{formatYuanValue(transaction.balance_before)} → {formatYuanValue(transaction.balance_after)}</td><td className="max-w-[260px] truncate px-4 py-3" title={transaction.reason}>{transaction.reason}</td><td className="px-4 py-3 font-mono text-xs">{transaction.operator_id.slice(0, 8)}…</td><td className="px-4 py-3">—</td></tr> })}</RecordShell>
}

function LogsTab({ logs, loading, error, onRetry, page, total, onPageChange, t, locale }: { logs: AdminOperationLogDetail[]; loading: boolean; error: boolean; onRetry: () => void; page: number; total: number; onPageChange: (page: number) => void; t: Translator; locale: string }) {
  return <RecordShell loading={loading} error={error} empty={logs.length === 0} onRetry={onRetry} colSpan={5} t={t} footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={onPageChange} />}><tr className="border-b border-border bg-muted/30"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.actionType")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.detailLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.operator")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.ipLabel")}</th></tr>{logs.map((log) => <tr key={log.id} className="border-b border-border/60 last:border-0"><td className="whitespace-nowrap px-4 py-3 text-xs text-muted-foreground">{formatDateTime(log.created_at, locale)}</td><td className="px-4 py-3"><StatusBadge label={log.action} tone="info" /></td><td className="max-w-[34rem] truncate px-4 py-3" title={log.detail}>{log.detail || t("admin.systemNoData")}</td><td className="px-4 py-3">{log.username}</td><td className="px-4 py-3 font-mono text-xs text-muted-foreground">{log.ip_address}</td></tr>)}</RecordShell>
}
