"use client"

import { useCallback, useEffect, useState } from "react"

import { AdminPage, AdminPagination, DataTableShell, PageHeader, PageToolbar, StatusBadge, TableEmptyState, TableErrorState, TableLoadingState } from "@/components/admin/shared"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import { adminLogApi, requestOrThrow } from "@/services/api"
import type { OperationLog } from "@/types"

const PAGE_SIZE = 20

const ACTIONS: Record<string, { key: "admin.actionProductCreate" | "admin.actionProductUpdate" | "admin.actionProductDelete" | "admin.actionCardImport" | "admin.actionCardInvalidate" | "admin.actionOrderMarkPaid" | "admin.actionUserDisable" | "admin.actionUserEnable" | "admin.actionConfigUpdate" | "admin.actionCategoryCreate" | "admin.actionCategoryUpdate" | "admin.actionCategoryDelete" | "admin.actionPaymentCreate" | "admin.actionPaymentUpdate" | "admin.actionPaymentDelete" | "admin.actionUserToggle"; tone: "success" | "info" | "danger" | "warning" | "purple" }> = {
  "product.create": { key: "admin.actionProductCreate", tone: "success" },
  "product.update": { key: "admin.actionProductUpdate", tone: "info" },
  "product.delete": { key: "admin.actionProductDelete", tone: "danger" },
  "cardkey.import": { key: "admin.actionCardImport", tone: "purple" },
  "cardkey.invalidate": { key: "admin.actionCardInvalidate", tone: "danger" },
  "order.mark_paid": { key: "admin.actionOrderMarkPaid", tone: "warning" },
  "user.disable": { key: "admin.actionUserDisable", tone: "danger" },
  "user.enable": { key: "admin.actionUserEnable", tone: "success" },
  "config.update": { key: "admin.actionConfigUpdate", tone: "info" },
  "category.create": { key: "admin.actionCategoryCreate", tone: "success" },
  "category.update": { key: "admin.actionCategoryUpdate", tone: "info" },
  "category.delete": { key: "admin.actionCategoryDelete", tone: "danger" },
  "payment.create": { key: "admin.actionPaymentCreate", tone: "success" },
  "payment.update": { key: "admin.actionPaymentUpdate", tone: "info" },
  "payment.delete": { key: "admin.actionPaymentDelete", tone: "danger" },
  "user.toggle": { key: "admin.actionUserToggle", tone: "warning" },
}

export default function AdminOperationLogsPage() {
  const { t, locale } = useLocale()
  const [logs, setLogs] = useState<OperationLog[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [actionFilter, setActionFilter] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const loadLogs = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminLogApi.getList({ page, page_size: PAGE_SIZE, action: actionFilter || undefined }))
      setLogs(data.list)
      setTotal(data.pagination.total)
    } catch {
      setLogs([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [actionFilter, page])

  useEffect(() => { void loadLogs() }, [loadLogs])

  return (
    <AdminPage>
      <PageHeader title={t("admin.logs")} description={t("admin.logsDesc")} />
      <PageToolbar><select className="h-10 w-full max-w-xs rounded-md border border-input bg-background px-3 text-sm text-foreground" value={actionFilter} onChange={(event) => { setActionFilter(event.target.value); setPage(1) }} aria-label={t("admin.allActionTypes")}><option value="">{t("admin.allActionTypes")}</option><option value="product">{t("admin.productOps")}</option><option value="cardkey">{t("admin.cardKeyOps")}</option><option value="order">{t("admin.orderOps")}</option><option value="user">{t("admin.userOps")}</option><option value="config">{t("admin.configOps")}</option><option value="category">{t("admin.categoryOps")}</option><option value="payment">{t("admin.paymentOps")}</option></select></PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1000px] text-sm"><thead><tr className="border-b border-border bg-muted/30"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.operator")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.actionType")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.targetLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.detailLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.ipLabel")}</th></tr></thead><tbody>
          {loading && <TableLoadingState colSpan={6} />}
          {!loading && error && <TableErrorState colSpan={6} onRetry={() => void loadLogs()} />}
          {!loading && !error && logs.length === 0 && <TableEmptyState colSpan={6}>{t("admin.noLogData")}</TableEmptyState>}
          {!loading && !error && logs.map((log) => { const action = ACTIONS[log.action]; return <tr key={log.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30"><td className="whitespace-nowrap px-4 py-3 text-xs text-muted-foreground">{formatDateTime(log.created_at, locale)}</td><td className="px-4 py-3 font-medium">{log.username}</td><td className="px-4 py-3"><StatusBadge label={action ? t(action.key) : t("admin.unknownAction")} tone={action?.tone || "neutral"} /></td><td className="px-4 py-3">{log.target_type}{log.target_id && <span className="ml-1 font-mono text-xs text-muted-foreground">({log.target_id.length > 12 ? `${log.target_id.slice(0, 8)}…` : log.target_id})</span>}</td><td className="max-w-[28rem] truncate px-4 py-3 text-muted-foreground">{log.detail || t("admin.systemNoData")}</td><td className="px-4 py-3 font-mono text-xs text-muted-foreground">{log.ip_address}</td></tr> })}
        </tbody></table>
      </DataTableShell>
    </AdminPage>
  )
}
