"use client"

import { useState, useEffect } from "react"
import { Search, UserCheck, UserX, KeyRound, ChevronLeft, ChevronRight } from "lucide-react"
import { cn, formatYuan } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminUserApi, requestOrThrow } from "@/services/api"
import { Modal } from "@/components/ui/modal"
import type { AdminUserItem } from "@/types"

const ITEMS_PER_PAGE = 20

export default function AdminUsersPage() {
  const { t } = useLocale()
  const [users, setUsers] = useState<AdminUserItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState("")
  const [currentPage, setCurrentPage] = useState(1)

  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [resetTarget, setResetTarget] = useState<AdminUserItem | null>(null)
  const [newPassword, setNewPassword] = useState("")
  const [resetting, setResetting] = useState(false)

  const fetchUsers = async () => {
    setLoading(true)
    try {
      const data = await requestOrThrow(() => adminUserApi.getList({
          page: currentPage,
          page_size: ITEMS_PER_PAGE,
          keyword: debouncedSearch || undefined,
        }))
      setUsers(data.list)
      setTotal(data.pagination.total)
    } catch {
      setUsers([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }

  // Debounce search input → reset page + commit debounced value
  useEffect(() => {
    if (search === debouncedSearch) return
    const timer = setTimeout(() => {
      setCurrentPage(1)
      setDebouncedSearch(search)
    }, 300)
    return () => clearTimeout(timer)
  }, [search])

  // Single fetch effect for all dependencies
  useEffect(() => { fetchUsers() }, [currentPage, debouncedSearch])

  const totalPages = Math.ceil(total / ITEMS_PER_PAGE)

  const handleToggleStatus = async (user: AdminUserItem) => {
    const newStatus: 0 | 1 = user.is_deleted === 0 ? 1 : 0
    try {
      await requestOrThrow(() => adminUserApi.toggleStatus(user.id, newStatus))
      toast.success(newStatus === 1 ? "已禁用" : "已启用")
      await fetchUsers()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const handleResetPassword = async () => {
    if (!resetTarget || newPassword.length < 12) {
      toast.error("新密码至少需要 12 个字符")
      return
    }
    setResetting(true)
    try {
      await requestOrThrow(() => adminUserApi.resetPassword(resetTarget.id, newPassword))
      toast.success("密码已重置")
      setResetTarget(null)
      setNewPassword("")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "重置失败")
    } finally {
      setResetting(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.users")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.usersDesc")}</p>
      </div>

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("admin.searchUser")}
            className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.usernameLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.role")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.balanceLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.registeredAt")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : users.length === 0 ? (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noUserData")}</td>
                </tr>
              ) : (
                users.map((user) => (
                  <tr key={user.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3 font-medium text-foreground">{user.username}</td>
                    <td className="px-4 py-3 text-muted-foreground">{user.email}</td>
                    <td className="px-4 py-3 text-foreground">{user.role}</td>
                    <td className="px-4 py-3 text-foreground">{formatYuan(user.balance)}</td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          "rounded-full px-2.5 py-0.5 text-xs font-medium",
                          user.is_deleted === 0
                            ? "bg-emerald-500/10 text-emerald-600"
                            : "bg-red-500/10 text-red-500"
                        )}
                      >
                        {user.is_deleted === 0 ? t("admin.normal") : t("admin.banned")}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {new Date(user.created_at).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          className="flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium text-amber-600 transition-colors hover:bg-amber-500/10"
                          onClick={() => setResetTarget(user)}
                        >
                          <KeyRound className="h-3.5 w-3.5" />
                          重置密码
                        </button>
                        <button
                          type="button"
                          className={cn(
                            "flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors",
                            user.is_deleted === 0
                              ? "text-red-500 hover:bg-red-500/10"
                              : "text-emerald-600 hover:bg-emerald-500/10"
                          )}
                          onClick={() => handleToggleStatus(user)}
                        >
                          {user.is_deleted === 0 ? (
                            <>
                              <UserX className="h-3.5 w-3.5" />
                              {t("admin.ban")}
                            </>
                          ) : (
                            <>
                              <UserCheck className="h-3.5 w-3.5" />
                              {t("admin.unban")}
                            </>
                          )}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total} {t("admin.totalUsers")}</span>
          {totalPages > 1 && (
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((page) => (
                <button
                  key={page}
                  type="button"
                  onClick={() => setCurrentPage(page)}
                  className={cn(
                    "flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium",
                    currentPage === page
                      ? "bg-primary text-primary-foreground"
                      : "border border-input text-foreground hover:bg-accent"
                  )}
                >
                  {page}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}
        </div>
      </div>

      <Modal open={resetTarget !== null} onClose={() => { setResetTarget(null); setNewPassword("") }} className="max-w-sm">
        <div className="flex flex-col gap-4 p-6">
          <div>
            <h2 className="text-lg font-semibold text-foreground">重置用户密码</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {resetTarget?.username} 的旧密码不可查看，只能设置新的 BCrypt 密码。
            </p>
          </div>
          <input
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            placeholder="新密码（至少 12 个字符）"
            minLength={12}
            className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={() => { setResetTarget(null); setNewPassword("") }}
              className="rounded-lg border border-input px-4 py-2 text-sm text-foreground hover:bg-accent"
            >
              {t("admin.cancel")}
            </button>
            <button
              type="button"
              disabled={resetting}
              onClick={handleResetPassword}
              className="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            >
              {resetting ? "处理中..." : "确认重置"}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
