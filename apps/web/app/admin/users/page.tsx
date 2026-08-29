"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { Eye } from "lucide-react"

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
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
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
  const [statusFilter, setStatusFilter] = useState<"" | "0" | "1">("")

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
  }, [debouncedSearch, page, statusFilter])

  useEffect(() => { void loadUsers() }, [loadUsers])

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
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground"
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value as "" | "0" | "1")
              setPage(1)
            }}
            aria-label={t("admin.statusLabel")}
          >
            <option value="">{t("admin.allStatus")}</option>
            <option value="0">{t("admin.normal")}</option>
            <option value="1">{t("admin.banned")}</option>
          </select>
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1120px] text-sm">
          <thead>
            <tr className="border-b border-border bg-muted/30">
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.userNumber")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.user")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailLabel")}</th>
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
            {!loading && !error && users.map((user) => {
              const enabled = user.is_deleted === 0
              return (
                <tr key={user.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                  <td className="whitespace-nowrap px-4 py-3 font-mono font-medium">{user.user_no}</td>
                  <td className="px-4 py-3 font-medium">{user.username}</td>
                  <td className="px-4 py-3 text-muted-foreground">{user.email}</td>
                  <td className="whitespace-nowrap px-4 py-3">{formatYuan(user.balance)}</td>
                  <td className="px-4 py-3">{user.order_count}</td>
                  <td className="whitespace-nowrap px-4 py-3">{formatYuan(user.total_spent)}</td>
                  <td className="px-4 py-3"><StatusBadge label={enabled ? t("admin.normal") : t("admin.banned")} tone={enabled ? "success" : "danger"} /></td>
                  <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(user.created_at, locale)}</td>
                  <td className="px-4 py-3 text-right">
                    <Button asChild type="button" variant="ghost" size="sm">
                      <Link href={`/admin/users/${user.id}`}>
                        <Eye className="h-3.5 w-3.5" aria-hidden="true" />
                        {t("admin.viewUser")}
                      </Link>
                    </Button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </DataTableShell>
    </AdminPage>
  )
}
