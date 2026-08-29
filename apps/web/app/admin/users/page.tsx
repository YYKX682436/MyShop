"use client"

import { useCallback, useEffect, useState } from "react"
import { KeyRound, UserCheck, UserX } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  AdminPagination,
  ConfirmAlert,
  DataTableShell,
  PageHeader,
  PageToolbar,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { useLocale } from "@/lib/context"
import { formatDateTime, formatYuan } from "@/lib/utils"
import { adminUserApi, requestOrThrow } from "@/services/api"
import type { AdminUserItem } from "@/types"

const PAGE_SIZE = 20

export default function AdminUsersPage() {
  const { t, locale } = useLocale()
  const [users, setUsers] = useState<AdminUserItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusTarget, setStatusTarget] = useState<AdminUserItem | null>(null)
  const [statusLoading, setStatusLoading] = useState(false)
  const [resetTarget, setResetTarget] = useState<AdminUserItem | null>(null)
  const [newPassword, setNewPassword] = useState("")
  const [resetting, setResetting] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => { setDebouncedSearch(search.trim()); setPage(1) }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const loadUsers = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminUserApi.getList({ page, page_size: PAGE_SIZE, keyword: debouncedSearch || undefined }))
      setUsers(data.list)
      setTotal(data.pagination.total)
    } catch {
      setUsers([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [debouncedSearch, page])

  useEffect(() => { void loadUsers() }, [loadUsers])

  const handleToggleStatus = async () => {
    if (!statusTarget) return
    const nextStatus: 0 | 1 = statusTarget.is_deleted === 0 ? 1 : 0
    setStatusLoading(true)
    try {
      await requestOrThrow(() => adminUserApi.toggleStatus(statusTarget.id, nextStatus))
      toast.success(nextStatus === 1 ? t("admin.userDisabled") : t("admin.userEnabled"))
      setStatusTarget(null)
      await loadUsers()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setStatusLoading(false)
    }
  }

  const handleResetPassword = async () => {
    if (!resetTarget) return
    if (newPassword.length < 12) {
      toast.error(t("admin.passwordMinLength"))
      return
    }
    setResetting(true)
    try {
      await requestOrThrow(() => adminUserApi.resetPassword(resetTarget.id, newPassword))
      toast.success(t("admin.passwordReset"))
      setResetTarget(null)
      setNewPassword("")
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setResetting(false)
    }
  }

  const roleLabel = (role: string) => role === "ADMIN" ? t("admin.roleAdmin") : role === "USER" ? t("admin.roleUser") : role

  return (
    <AdminPage>
      <PageHeader title={t("admin.users")} description={t("admin.usersDesc")} />
      <PageToolbar><Input className="w-full sm:max-w-sm" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("admin.searchUser")} aria-label={t("admin.searchUser")} /></PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[900px] text-sm">
          <thead><tr className="border-b border-border bg-muted/30">
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.usernameLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.role")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.registeredAt")}</th>
            <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
          </tr></thead>
          <tbody>
            {loading && <TableLoadingState colSpan={7} />}
            {!loading && error && <TableErrorState colSpan={7} onRetry={() => void loadUsers()} />}
            {!loading && !error && users.length === 0 && <TableEmptyState colSpan={7}>{t("admin.noUserData")}</TableEmptyState>}
            {!loading && !error && users.map((user) => {
              const enabled = user.is_deleted === 0
              return <tr key={user.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                <td className="px-4 py-3 font-medium">{user.username}</td>
                <td className="px-4 py-3 text-muted-foreground">{user.email}</td>
                <td className="px-4 py-3">{roleLabel(user.role)}</td>
                <td className="whitespace-nowrap px-4 py-3">{formatYuan(user.balance)}</td>
                <td className="px-4 py-3"><StatusBadge label={enabled ? t("admin.normal") : t("admin.banned")} tone={enabled ? "success" : "danger"} /></td>
                <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(user.created_at, locale)}</td>
                <td className="px-4 py-3"><div className="flex justify-end gap-1">
                  <Button type="button" variant="ghost" size="sm" className="text-amber-600" onClick={() => { setResetTarget(user); setNewPassword("") }}><KeyRound className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.resetPassword")}</Button>
                  <Button type="button" variant="ghost" size="sm" className={enabled ? "text-destructive" : "text-emerald-600"} onClick={() => setStatusTarget(user)}>{enabled ? <UserX className="h-3.5 w-3.5" aria-hidden="true" /> : <UserCheck className="h-3.5 w-3.5" aria-hidden="true" />}{enabled ? t("admin.ban") : t("admin.unban")}</Button>
                </div></td>
              </tr>
            })}
          </tbody>
        </table>
      </DataTableShell>

      <Dialog open={resetTarget !== null} onOpenChange={(open) => { if (!open && !resetting) { setResetTarget(null); setNewPassword("") } }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{t("admin.resetPasswordTitle")}</DialogTitle><DialogDescription>{resetTarget ? t("admin.resetPasswordDescription").replace("{username}", resetTarget.username) : ""}</DialogDescription></DialogHeader>
          <Input type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} placeholder={t("admin.newPasswordPlaceholder")} minLength={12} autoComplete="new-password" />
          <DialogFooter><Button type="button" variant="outline" onClick={() => { setResetTarget(null); setNewPassword("") }} disabled={resetting}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleResetPassword()} disabled={resetting || newPassword.length < 12}>{resetting ? t("admin.processing") : t("admin.confirmReset")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert
        open={statusTarget !== null}
        onOpenChange={(open) => { if (!open && !statusLoading) setStatusTarget(null) }}
        title={statusTarget?.is_deleted === 0 ? t("admin.confirmDisable") : t("admin.confirmEnable")}
        description={statusTarget ? t("admin.userStatusDescription").replace("{action}", statusTarget.is_deleted === 0 ? t("admin.ban") : t("admin.unban")).replace("{username}", statusTarget.username) : undefined}
        confirmLabel={statusTarget?.is_deleted === 0 ? t("admin.ban") : t("admin.unban")}
        cancelLabel={t("admin.cancel")}
        destructive={statusTarget?.is_deleted === 0}
        loading={statusLoading}
        onConfirm={() => void handleToggleStatus()}
      />
    </AdminPage>
  )
}
