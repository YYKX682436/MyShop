"use client"

import { useCallback, useEffect, useState } from "react"
import { Eye, KeyRound, Pencil, Plus, ShieldCheck, UserCheck, UserX } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  AdminPagination,
  DataTableShell,
  PageHeader,
  PageToolbar,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
  maskTechnicalIdsInText,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { ConfirmAlert } from "@/components/admin/shared"
import { useLocale } from "@/lib/context"
import { useRequireSuperAdmin } from "@/lib/hooks"
import { formatDateTime } from "@/lib/utils"
import { adminAdministratorApi, requestOrThrow } from "@/services/api"
import type { AdministratorItem, AdministratorOperationLog } from "@/types"

const PAGE_SIZE = 20
const LOG_PAGE_SIZE = 20

type ContactForm = { email: string; phone: string }

export default function AdministratorsPage() {
  const { t, locale } = useLocale()
  const currentUser = useRequireSuperAdmin()
  const [administrators, setAdministrators] = useState<AdministratorItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<"" | "0" | "1">("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const [createOpen, setCreateOpen] = useState(false)
  const [createLoading, setCreateLoading] = useState(false)
  const [createForm, setCreateForm] = useState({ username: "", password: "", email: "", phone: "" })

  const [editTarget, setEditTarget] = useState<AdministratorItem | null>(null)
  const [editForm, setEditForm] = useState<ContactForm>({ email: "", phone: "" })
  const [editLoading, setEditLoading] = useState(false)

  const [resetTarget, setResetTarget] = useState<AdministratorItem | null>(null)
  const [resetPassword, setResetPassword] = useState("")
  const [resetLoading, setResetLoading] = useState(false)

  const [toggleTarget, setToggleTarget] = useState<AdministratorItem | null>(null)
  const [toggleLoading, setToggleLoading] = useState(false)

  const [logsTarget, setLogsTarget] = useState<AdministratorItem | null>(null)
  const [logs, setLogs] = useState<AdministratorOperationLog[]>([])
  const [logsPage, setLogsPage] = useState(1)
  const [logsTotal, setLogsTotal] = useState(0)
  const [logsLoading, setLogsLoading] = useState(false)
  const [logsError, setLogsError] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedSearch(search.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const loadAdministrators = useCallback(async () => {
    if (!currentUser) return
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminAdministratorApi.getList({
        page,
        page_size: PAGE_SIZE,
        keyword: debouncedSearch || undefined,
        is_deleted: statusFilter ? Number(statusFilter) as 0 | 1 : undefined,
      }))
      setAdministrators(data.list)
      setTotal(data.pagination.total)
    } catch {
      setAdministrators([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [currentUser, debouncedSearch, page, statusFilter])

  useEffect(() => { void loadAdministrators() }, [loadAdministrators])

  const handleCreate = async () => {
    if (!createForm.username.trim() || !createForm.password) {
      toast.error(t("admin.administratorUsername") + " / " + t("admin.administratorPassword"))
      return
    }
    setCreateLoading(true)
    try {
      await requestOrThrow(() => adminAdministratorApi.create({
        username: createForm.username,
        password: createForm.password,
        email: createForm.email || undefined,
        phone: createForm.phone || undefined,
      }))
      setCreateOpen(false)
      setCreateForm({ username: "", password: "", email: "", phone: "" })
      await loadAdministrators()
      toast.success(t("admin.administratorCreateSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.loadError"))
    } finally {
      setCreateLoading(false)
    }
  }

  const openEdit = (administrator: AdministratorItem) => {
    setEditTarget(administrator)
    setEditForm({ email: administrator.email || "", phone: administrator.phone || "" })
  }

  const handleEdit = async () => {
    if (!editTarget) return
    setEditLoading(true)
    try {
      await requestOrThrow(() => adminAdministratorApi.update(editTarget.id, {
        email: editForm.email,
        phone: editForm.phone,
      }))
      setEditTarget(null)
      await loadAdministrators()
      toast.success(t("admin.administratorUpdateSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.loadError"))
    } finally {
      setEditLoading(false)
    }
  }

  const handleToggle = async () => {
    if (!toggleTarget) return
    setToggleLoading(true)
    try {
      await requestOrThrow(() => adminAdministratorApi.toggleStatus(
        toggleTarget.id, toggleTarget.is_deleted === 0 ? 1 : 0,
      ))
      setToggleTarget(null)
      await loadAdministrators()
      toast.success(t("admin.administratorStatusUpdated"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.loadError"))
    } finally {
      setToggleLoading(false)
    }
  }

  const handleReset = async () => {
    if (!resetTarget) return
    if (resetPassword.length < 6 || resetPassword.length > 72 || !/^[!-~]+$/.test(resetPassword)) {
      toast.error(t("admin.passwordMinLength"))
      return
    }
    setResetLoading(true)
    try {
      await requestOrThrow(() => adminAdministratorApi.resetPassword(resetTarget.id, resetPassword))
      setResetTarget(null)
      setResetPassword("")
      await loadAdministrators()
      toast.success(t("admin.administratorPasswordResetSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.loadError"))
    } finally {
      setResetLoading(false)
    }
  }

  const loadLogs = useCallback(async (administrator: AdministratorItem, requestedPage: number) => {
    setLogsLoading(true)
    setLogsError(false)
    try {
      const data = await requestOrThrow(() => adminAdministratorApi.getOperationLogs(administrator.id, {
        page: requestedPage,
        page_size: LOG_PAGE_SIZE,
      }))
      setLogs(data.list)
      setLogsTotal(data.pagination.total)
      setLogsPage(requestedPage)
    } catch {
      setLogs([])
      setLogsTotal(0)
      setLogsError(true)
    } finally {
      setLogsLoading(false)
    }
  }, [])

  const openLogs = (administrator: AdministratorItem) => {
    setLogsTarget(administrator)
    setLogs([])
    setLogsPage(1)
    void loadLogs(administrator, 1)
  }

  if (!currentUser) return null

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.administrators")}
        description={t("admin.administratorsDesc")}
        actions={
          <Button type="button" onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            {t("admin.createAdministrator")}
          </Button>
        }
      />

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
            onChange={(event) => {
              setStatusFilter(event.target.value as "" | "0" | "1")
              setPage(1)
            }}
            aria-label={t("admin.administratorStatus")}
          >
            <option value="">{t("admin.allStatus")}</option>
            <option value="0">{t("admin.normal")}</option>
            <option value="1">{t("admin.banned")}</option>
          </select>
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1080px] text-sm">
          <thead>
            <tr className="border-b border-border-subtle bg-surface-muted">
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.administratorUsername")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.administratorEmail")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.administratorPhone")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.administratorRole")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.administratorStatus")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.registeredAt")}</th>
              <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {loading && <TableLoadingState colSpan={7} />}
            {!loading && error && <TableErrorState colSpan={7} onRetry={() => void loadAdministrators()} />}
            {!loading && !error && administrators.length === 0 && <TableEmptyState colSpan={7}>{t("admin.administratorNoData")}</TableEmptyState>}
            {!loading && !error && administrators.map((administrator) => {
              const isSuperAdmin = administrator.role === "SUPER_ADMIN"
              const enabled = administrator.is_deleted === 0
              return (
                <tr key={administrator.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                  <td data-label={t("admin.administratorUsername")} className="px-4 py-3 font-medium">{administrator.username}</td>
                  <td data-label={t("admin.administratorEmail")} className="px-4 py-3 text-muted-foreground">{administrator.email || "—"}</td>
                  <td data-label={t("admin.administratorPhone")} className="px-4 py-3 text-muted-foreground">{administrator.phone || "—"}</td>
                  <td data-label={t("admin.administratorRole")} className="px-4 py-3">
                    <StatusBadge
                      label={isSuperAdmin ? t("admin.administratorSuperAdmin") : t("admin.administratorOrdinary")}
                      tone={isSuperAdmin ? "purple" : "info"}
                    />
                  </td>
                  <td data-label={t("admin.administratorStatus")} className="px-4 py-3"><StatusBadge label={enabled ? t("admin.normal") : t("admin.banned")} tone={enabled ? "success" : "danger"} /></td>
                  <td data-label={t("admin.registeredAt")} className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(administrator.created_at, locale)}</td>
                  <td data-label={t("admin.actions")} className="px-4 py-3">
                    <div className="flex justify-end gap-1">
                      <Button type="button" variant="ghost" size="sm" onClick={() => openLogs(administrator)}>
                        <Eye className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.administratorLogs")}
                      </Button>
                      {!isSuperAdmin && (
                        <>
                          <Button type="button" variant="ghost" size="icon" onClick={() => openEdit(administrator)} title={t("admin.edit")} aria-label={t("admin.edit")}>
                            <Pencil className="h-4 w-4" aria-hidden="true" />
                          </Button>
                          <Button type="button" variant="ghost" size="icon" onClick={() => setResetTarget(administrator)} title={t("admin.administratorResetPassword")} aria-label={t("admin.administratorResetPassword")}>
                            <KeyRound className="h-4 w-4" aria-hidden="true" />
                          </Button>
                          <Button type="button" variant="ghost" size="icon" onClick={() => setToggleTarget(administrator)} title={enabled ? t("admin.administratorDisable") : t("admin.administratorEnable")} aria-label={enabled ? t("admin.administratorDisable") : t("admin.administratorEnable")}>
                            {enabled ? <UserX className="h-4 w-4 text-destructive" aria-hidden="true" /> : <UserCheck className="h-4 w-4" aria-hidden="true" />}
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </DataTableShell>

      <Dialog open={createOpen} onOpenChange={(open) => { if (!createLoading) setCreateOpen(open) }}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{t("admin.createAdministratorTitle")}</DialogTitle>
            <DialogDescription>{t("admin.administratorContactHint")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium sm:col-span-2">
              <span>{t("admin.administratorUsername")}</span>
              <Input value={createForm.username} onChange={(event) => setCreateForm({ ...createForm, username: event.target.value })} placeholder={t("admin.administratorUsernameHint")} autoComplete="off" />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium sm:col-span-2">
              <span>{t("admin.administratorPassword")}</span>
              <Input type="password" value={createForm.password} onChange={(event) => setCreateForm({ ...createForm, password: event.target.value })} placeholder={t("admin.administratorPasswordHint")} autoComplete="new-password" minLength={6} maxLength={72} />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              <span>{t("admin.administratorEmail")}</span>
              <Input type="email" value={createForm.email} onChange={(event) => setCreateForm({ ...createForm, email: event.target.value })} placeholder={t("admin.administratorEmailPlaceholder")} autoComplete="email" />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              <span>{t("admin.administratorPhone")}</span>
              <Input inputMode="tel" value={createForm.phone} onChange={(event) => setCreateForm({ ...createForm, phone: event.target.value })} placeholder={t("admin.administratorPhonePlaceholder")} autoComplete="tel" />
            </label>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setCreateOpen(false)} disabled={createLoading}>{t("admin.cancel")}</Button>
            <Button type="button" onClick={() => void handleCreate()} disabled={createLoading}>{createLoading ? t("admin.processing") : t("admin.createAdministrator")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!editTarget} onOpenChange={(open) => { if (!editLoading && !open) setEditTarget(null) }}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{t("admin.editAdministratorTitle")}</DialogTitle>
            <DialogDescription>{editTarget ? editTarget.username : ""}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              <span>{t("admin.administratorEmail")}</span>
              <Input type="email" value={editForm.email} onChange={(event) => setEditForm({ ...editForm, email: event.target.value })} placeholder={t("admin.administratorEmailPlaceholder")} />
            </label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              <span>{t("admin.administratorPhone")}</span>
              <Input inputMode="tel" value={editForm.phone} onChange={(event) => setEditForm({ ...editForm, phone: event.target.value })} placeholder={t("admin.administratorPhonePlaceholder")} />
            </label>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setEditTarget(null)} disabled={editLoading}>{t("admin.cancel")}</Button>
            <Button type="button" onClick={() => void handleEdit()} disabled={editLoading}>{editLoading ? t("admin.processing") : t("admin.save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!resetTarget} onOpenChange={(open) => { if (!resetLoading && !open) { setResetTarget(null); setResetPassword("") } }}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("admin.administratorResetPasswordTitle")}</DialogTitle>
            <DialogDescription>{resetTarget ? t("admin.administratorResetPasswordDescription").replace("{username}", resetTarget.username) : ""}</DialogDescription>
          </DialogHeader>
          <Input type="password" value={resetPassword} onChange={(event) => setResetPassword(event.target.value)} placeholder={t("admin.administratorPasswordHint")} minLength={6} maxLength={72} autoComplete="new-password" />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => { setResetTarget(null); setResetPassword("") }} disabled={resetLoading}>{t("admin.cancel")}</Button>
            <Button type="button" onClick={() => void handleReset()} disabled={resetLoading || resetPassword.length < 6}>{resetLoading ? t("admin.processing") : t("admin.confirmReset")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert
        open={!!toggleTarget}
        onOpenChange={(open) => { if (!toggleLoading && !open) setToggleTarget(null) }}
        title={toggleTarget?.is_deleted === 0 ? t("admin.administratorConfirmDisable") : t("admin.administratorConfirmEnable")}
        description={toggleTarget ? t("admin.administratorStatusDescription").replace("{action}", toggleTarget.is_deleted === 0 ? t("admin.administratorDisable") : t("admin.administratorEnable")).replace("{username}", toggleTarget.username) : ""}
        confirmLabel={toggleTarget?.is_deleted === 0 ? t("admin.administratorDisable") : t("admin.administratorEnable")}
        cancelLabel={t("admin.cancel")}
        destructive={toggleTarget?.is_deleted === 0}
        loading={toggleLoading}
        onConfirm={() => void handleToggle()}
      />

      <Dialog open={!!logsTarget} onOpenChange={(open) => { if (!open) setLogsTarget(null) }}>
        <DialogContent className="max-w-4xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />{t("admin.administratorOperationLogs")}</DialogTitle>
            <DialogDescription>{logsTarget?.username || ""} · {logsTarget?.role === "SUPER_ADMIN" ? t("admin.administratorSuperAdminReadonly") : t("admin.administratorOrdinary")}</DialogDescription>
          </DialogHeader>
          <div className="admin-table-region max-h-[55vh] overflow-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead><tr className="border-b border-border-subtle bg-surface-muted"><th className="px-3 py-2 text-left">{t("admin.time")}</th><th className="px-3 py-2 text-left">{t("admin.actionType")}</th><th className="px-3 py-2 text-left">{t("admin.detailLabel")}</th><th className="px-3 py-2 text-left">{t("admin.ipLabel")}</th></tr></thead>
              <tbody>
                {logsLoading && <TableLoadingState colSpan={4} />}
                {!logsLoading && logsError && <TableErrorState colSpan={4} onRetry={() => logsTarget && void loadLogs(logsTarget, logsPage)} />}
                {!logsLoading && !logsError && logs.length === 0 && <TableEmptyState colSpan={4}>{t("admin.administratorNoLogs")}</TableEmptyState>}
                {!logsLoading && !logsError && logs.map((log) => { const detail = maskTechnicalIdsInText(log.detail); return <tr key={log.id} className="border-b border-border-subtle last:border-0"><td data-label={t("admin.time")} className="whitespace-nowrap px-3 py-2 text-xs text-muted-foreground">{formatDateTime(log.created_at, locale)}</td><td data-label={t("admin.actionType")} className="px-3 py-2">{log.action}</td><td data-label={t("admin.detailLabel")} className="max-w-[28rem] truncate px-3 py-2" title={detail}>{detail || "—"}</td><td data-label={t("admin.ipLabel")} className="px-3 py-2 font-mono text-xs text-muted-foreground">{log.ip_address}</td></tr> })}
              </tbody>
            </table>
          </div>
          <AdminPagination page={logsPage} total={logsTotal} pageSize={LOG_PAGE_SIZE} onPageChange={(nextPage) => { if (logsTarget) void loadLogs(logsTarget, nextPage) }} />
        </DialogContent>
      </Dialog>
    </AdminPage>
  )
}
