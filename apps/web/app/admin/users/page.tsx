"use client"

import Link from "next/link"
import { useCallback, useEffect, useMemo, useState } from "react"
import { Eye } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  AdminPagination,
  CopyableValue,
  DataTableShell,
  PageHeader,
  PageToolbar,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { UserLevelQuickEdit } from "@/components/admin/users/user-level-quick-edit"
import { UserStatusQuickEdit } from "@/components/admin/users/user-status-quick-edit"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { copyText } from "@/lib/clipboard"
import { formatCents, formatYuanFromCents, formatYuanValue, MAX_BALANCE_CENTS, parseMoneyValueToCents, parsePositiveMoneyToCents } from "@/lib/money"
import { formatBalanceAdjustmentResult, isOptionalReasonValid, normalizeOptionalReason } from "@/lib/user-management"
import { formatDateTime } from "@/lib/utils"
import { adminUserApi, adminUserLevelApi, requestOrThrow } from "@/services/api"
import type { AdminUserItem, BalanceTransaction, UserLevel } from "@/types"

const PAGE_SIZE = 20

type CopyStatus = "pending" | "success" | "failed"

export default function AdminUsersPage() {
  const { t, locale } = useLocale()
  const [users, setUsers] = useState<AdminUserItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<"" | "0" | "1">("")
  const [levelFilter, setLevelFilter] = useState("")
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [levelSavingId, setLevelSavingId] = useState<string | null>(null)
  const [statusSavingId, setStatusSavingId] = useState<string | null>(null)

  const [balanceTarget, setBalanceTarget] = useState<AdminUserItem | null>(null)
  const [balanceType, setBalanceType] = useState<"INCREASE" | "DECREASE">("INCREASE")
  const [balanceAmount, setBalanceAmount] = useState("")
  const [balanceReason, setBalanceReason] = useState("")
  const [balanceLoading, setBalanceLoading] = useState(false)
  const [balanceResult, setBalanceResult] = useState<{ text: string; transaction: BalanceTransaction; copyStatus: CopyStatus } | null>(null)

  useEffect(() => {
    void adminUserLevelApi.getList().then(setLevels).catch(() => setLevels([]))
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedSearch(search.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const loadUsers = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminUserApi.getList({
        page,
        page_size: PAGE_SIZE,
        keyword: debouncedSearch || undefined,
        is_deleted: statusFilter ? Number(statusFilter) as 0 | 1 : undefined,
        user_level_id: levelFilter || undefined,
      }))
      setUsers(data.list)
      setTotal(data.pagination.total)
    } catch {
      setUsers([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [debouncedSearch, levelFilter, page, statusFilter])

  useEffect(() => { void loadUsers() }, [loadUsers])

  const changeLevel = async (user: AdminUserItem, userLevelId: string): Promise<boolean> => {
    if (!userLevelId || userLevelId === user.user_level_id) return true
    setLevelSavingId(user.id)
    try {
      const level = await requestOrThrow(() => adminUserApi.changeLevel(user.id, userLevelId))
      setUsers((current) => current.map((candidate) => candidate.id === user.id
        ? { ...candidate, user_level_id: level.id, user_level_name: level.name }
        : candidate))
      toast.success(t("admin.userLevelChanged"))
      return true
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
      return false
    } finally {
      setLevelSavingId(null)
    }
  }

  const toggleStatus = async (user: AdminUserItem, nextIsDeleted: 0 | 1): Promise<boolean> => {
    if (user.is_deleted === nextIsDeleted) return true
    setStatusSavingId(user.id)
    try {
      await requestOrThrow(() => adminUserApi.toggleStatus(user.id, nextIsDeleted))
      setUsers((current) => current.map((candidate) => candidate.id === user.id
        ? { ...candidate, is_deleted: nextIsDeleted }
        : candidate))
      toast.success(nextIsDeleted === 1 ? t("admin.userDisabled") : t("admin.userEnabled"))
      return true
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
      return false
    } finally {
      setStatusSavingId(null)
    }
  }

  const previewBalanceCents = useMemo(() => {
    const current = parseMoneyValueToCents(balanceTarget?.balance ?? 0) ?? 0
    const amount = parsePositiveMoneyToCents(balanceAmount)
    if (amount === null) return current
    return current + (balanceType === "INCREASE" ? amount : -amount)
  }, [balanceAmount, balanceTarget?.balance, balanceType])

  const openBalance = (user: AdminUserItem) => {
    setBalanceTarget(user)
    setBalanceType("INCREASE")
    setBalanceAmount("")
    setBalanceReason("")
  }

  const handleBalance = async () => {
    if (!balanceTarget) return
    const amountCents = parsePositiveMoneyToCents(balanceAmount)
    const reason = normalizeOptionalReason(balanceReason)
    if (amountCents === null) {
      toast.error(t("admin.balanceAmountInvalid"))
      return
    }
    if (!isOptionalReasonValid(balanceReason)) {
      toast.error(t("admin.balanceReasonTooLong"))
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
      const transaction = await requestOrThrow(() => adminUserApi.adjustBalance(balanceTarget.id, {
        type: balanceType,
        amount: formatCents(amountCents),
        reason,
      }))
      setUsers((current) => current.map((candidate) => candidate.id === balanceTarget.id
        ? { ...candidate, balance: Number(transaction.balance_after) }
        : candidate))
      setBalanceTarget(null)
      setBalanceAmount("")
      setBalanceReason("")
      const text = formatBalanceAdjustmentResult(balanceTarget.user_no, balanceTarget.username, transaction)
      setBalanceResult({ text, transaction, copyStatus: "pending" })
      void copyText(text).then((success) => setBalanceResult((current) => current ? { ...current, copyStatus: success ? "success" : "failed" } : current))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setBalanceLoading(false)
    }
  }

  const copyBalanceResult = async () => {
    if (!balanceResult) return
    const success = await copyText(balanceResult.text)
    setBalanceResult((current) => current ? { ...current, copyStatus: success ? "success" : "failed" } : current)
  }

  return (
    <AdminPage>
      <PageHeader title={t("admin.users")} description={t("admin.usersDesc")} />
      <PageToolbar>
        <div className="flex min-w-0 flex-1 flex-col gap-3 sm:flex-row">
          <Input
            className="min-w-0 sm:max-w-md"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t("admin.searchUser")}
            aria-label={t("admin.searchUser")}
          />
          <select
            className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm"
            value={statusFilter}
            onChange={(event) => { setStatusFilter(event.target.value as "" | "0" | "1"); setPage(1) }}
            aria-label={t("admin.statusLabel")}
          >
            <option value="">{t("admin.allStatus")}</option>
            <option value="0">{t("admin.normal")}</option>
            <option value="1">{t("admin.banned")}</option>
          </select>
          <select
            className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm"
            value={levelFilter}
            onChange={(event) => { setLevelFilter(event.target.value); setPage(1) }}
            aria-label={t("admin.userLevel")}
          >
            <option value="">{t("admin.allUserLevels")}</option>
            {levels.map((level) => <option key={level.id} value={level.id}>{level.name}{!level.enabled ? ` · ${t("admin.inactive")}` : ""}</option>)}
          </select>
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1040px] text-sm">
          <thead>
            <tr className="border-b border-border-subtle bg-surface-muted">
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.userNumber")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.usernameLabel")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.userLevel")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceLabel")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderCount")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.totalSpent")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.registeredAt")}</th>
              <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {loading && <TableLoadingState colSpan={9} />}
            {!loading && error && <TableErrorState colSpan={9} onRetry={() => void loadUsers()} />}
            {!loading && !error && users.length === 0 && <TableEmptyState colSpan={9}>{t("admin.noUserData")}</TableEmptyState>}
            {!loading && !error && users.map((user) => (
              <tr key={user.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                <td className="whitespace-nowrap px-4 py-3">
                  <CopyableValue value={String(user.user_no)} label={t("admin.userNumber")} mono />
                </td>
                <td className="px-4 py-3 font-medium">
                  <CopyableValue value={user.username} label={t("admin.usernameLabel")} />
                </td>
                <td className="px-4 py-3">
                  <UserLevelQuickEdit
                    username={user.username}
                    value={user.user_level_id}
                    label={user.user_level_name || t("admin.systemNoData")}
                    levels={levels}
                    saving={levelSavingId === user.id}
                    onSave={(levelId) => changeLevel(user, levelId)}
                  />
                </td>
                <td className="whitespace-nowrap px-4 py-3">
                  <button type="button" className="rounded-control px-2 py-1 text-left font-medium transition-colors hover:bg-surface-muted hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35" onClick={() => openBalance(user)} aria-label={`${t("admin.adjustBalance")}: ${user.username}`}>
                    {formatYuanValue(user.balance)}
                  </button>
                </td>
                <td className="px-4 py-3">{user.order_count}</td>
                <td className="whitespace-nowrap px-4 py-3">{formatYuanValue(user.total_spent)}</td>
                <td className="px-4 py-3">
                  <UserStatusQuickEdit
                    username={user.username}
                    isDeleted={user.is_deleted}
                    saving={statusSavingId === user.id}
                    onSave={(next) => toggleStatus(user, next)}
                  />
                </td>
                <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(user.created_at, locale)}</td>
                <td className="px-4 py-3 text-right">
                  <Button asChild type="button" variant="ghost" size="sm">
                    <Link href={`/admin/users/${user.id}`}><Eye className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.viewUser")}</Link>
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </DataTableShell>

      <Dialog open={balanceTarget !== null} onOpenChange={(open) => { if (!balanceLoading && !open) setBalanceTarget(null) }}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{t("admin.adjustBalance")}</DialogTitle><DialogDescription>{balanceTarget?.username} · {t("admin.balanceAdjustmentDesc")}</DialogDescription></DialogHeader>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-card border border-border-subtle bg-surface-muted p-3"><p className="text-xs text-muted-foreground">{t("admin.currentBalance")}</p><p className="mt-1 text-xl font-semibold">{formatYuanValue(balanceTarget?.balance ?? 0)}</p></div>
            <div className={`rounded-card border border-border-subtle p-3 ${previewBalanceCents < 0 ? "bg-destructive/10 text-destructive" : "bg-primary/10"}`}><p className="text-xs text-muted-foreground">{t("admin.balancePreview")}</p><p className="mt-1 text-xl font-semibold">{formatYuanFromCents(previewBalanceCents)}</p></div>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceAdjustmentType")}</span><select className="h-11 rounded-control border border-input bg-surface px-3 text-sm" value={balanceType} onChange={(event) => setBalanceType(event.target.value as "INCREASE" | "DECREASE")}><option value="INCREASE">{t("admin.balanceIncrease")}</option><option value="DECREASE">{t("admin.balanceDecrease")}</option></select></label>
            <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceAmount")}</span><Input type="text" inputMode="decimal" value={balanceAmount} onChange={(event) => setBalanceAmount(event.target.value)} placeholder="0.00" /></label>
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.balanceReason")}</span><Textarea value={balanceReason} onChange={(event) => setBalanceReason(event.target.value)} placeholder={t("admin.balanceReasonPlaceholder")} maxLength={500} rows={4} /></label>
          <DialogFooter><Button type="button" variant="outline" onClick={() => setBalanceTarget(null)} disabled={balanceLoading}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleBalance()} disabled={balanceLoading || previewBalanceCents < 0 || previewBalanceCents > MAX_BALANCE_CENTS || !balanceAmount}>{balanceLoading ? t("admin.processing") : t("admin.confirmAdjustment")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={balanceResult !== null} onOpenChange={(open) => { if (!open) setBalanceResult(null) }}>
        <DialogContent className="max-w-sm">
          <DialogHeader><DialogTitle>{t("admin.balanceAdjusted")}</DialogTitle><DialogDescription>{t("admin.balanceResultAuthoritative")}</DialogDescription></DialogHeader>
          <pre className="whitespace-pre-wrap rounded-card border border-border-subtle bg-surface-muted p-4 text-sm leading-6 text-foreground">{balanceResult?.text}</pre>
          <p className={`text-xs ${balanceResult?.copyStatus === "failed" ? "text-destructive" : "text-muted-foreground"}`} role="status">
            {balanceResult?.copyStatus === "pending" ? t("admin.copying") : balanceResult?.copyStatus === "success" ? t("admin.autoCopySuccess") : t("admin.autoCopyFailed")}
          </p>
          <DialogFooter><Button type="button" variant="outline" onClick={() => void copyBalanceResult()}>{t("admin.copyAll")}</Button><Button type="button" onClick={() => setBalanceResult(null)}>{t("common.close")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminPage>
  )
}
