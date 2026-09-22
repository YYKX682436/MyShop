"use client"

import { useCallback, useEffect, useState } from "react"
import { Eye, RefreshCw } from "lucide-react"
import { toast } from "sonner"

import { AdminPage, DataTableShell, PageHeader, PageToolbar, StatusBadge, TableEmptyState, TableErrorState, TableLoadingState } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useLocale } from "@/lib/context"
import { formatDateTime, formatYuan } from "@/lib/utils"
import { adminSalesChannelApi, requestOrThrow, type SalesChannelOrderDetail, type SalesChannelOrderSummary } from "@/services/api"

const tone = (value: string): "success" | "warning" | "danger" | "info" | "neutral" => {
  if (["SUCCEEDED", "IMPORTED", "DELIVERED"].includes(value)) return "success"
  if (["UNKNOWN", "MESSAGE_UNKNOWN", "CONFIRM_UNKNOWN", "REVIEW_REQUIRED", "MAPPING_ERROR"].includes(value)) return "warning"
  if (["FAILED", "SOURCE_CONFLICT"].includes(value)) return "danger"
  return "info"
}

export default function SalesChannelOrdersPage() {
  const { locale } = useLocale()
  const [orders, setOrders] = useState<SalesChannelOrderSummary[]>([])
  const [keyword, setKeyword] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [detail, setDetail] = useState<SalesChannelOrderDetail | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      setOrders(await requestOrThrow(() => adminSalesChannelApi.getOrders({ keyword: keyword.trim() || undefined })))
    } catch {
      setOrders([])
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [keyword])

  useEffect(() => { void load() }, [load])

  const openDetail = async (id: string) => {
    try {
      setDetail(await requestOrThrow(() => adminSalesChannelApi.getOrder(id)))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载渠道订单详情失败")
    }
  }

  return (
    <AdminPage>
      <PageHeader title="渠道订单" description="查看闲鱼订单导入、MyShop 履约与消息/确认发货状态；不会显示卡密明文。" />
      <PageToolbar>
        <div className="flex w-full gap-3">
          <Input className="max-w-sm" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索闲鱼订单号或商品" />
          <Button type="button" variant="outline" onClick={() => void load()} disabled={loading}><RefreshCw className={loading ? "animate-spin" : ""} />刷新</Button>
        </div>
      </PageToolbar>
      <DataTableShell>
        <table className="w-full min-w-[1100px] text-sm">
          <thead><tr className="border-b border-border-subtle bg-surface-muted">
            <th className="px-4 py-3 text-left">闲鱼订单</th><th className="px-4 py-3 text-left">商品 / 规格</th><th className="px-4 py-3 text-left">数量 / 金额</th><th className="px-4 py-3 text-left">外部状态</th><th className="px-4 py-3 text-left">导入 / 履约</th><th className="px-4 py-3 text-left">更新时间</th><th className="px-4 py-3 text-right">操作</th>
          </tr></thead>
          <tbody>
            {loading && <TableLoadingState colSpan={7} />}
            {!loading && error && <TableErrorState colSpan={7} onRetry={() => void load()} />}
            {!loading && !error && orders.length === 0 && <TableEmptyState colSpan={7}>暂无渠道订单</TableEmptyState>}
            {!loading && !error && orders.map((order) => (
              <tr key={order.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                <td className="px-4 py-3"><div className="font-mono text-xs">{order.externalOrderNo}</div><div className="text-xs text-muted-foreground">MyShop {order.myshopOrderNo || "未创建"}</div></td>
                <td className="px-4 py-3"><div className="font-medium">{order.productTitle || "未映射商品"}</div><div className="text-xs text-muted-foreground">{order.specName || "无规格"}</div></td>
                <td className="px-4 py-3 whitespace-nowrap">{order.quantity} · {formatYuan(Number(order.amount))}</td>
                <td className="px-4 py-3"><StatusBadge label={order.externalStatus} tone={tone(order.externalStatus)} /></td>
                <td className="px-4 py-3"><div className="flex flex-col items-start gap-1"><StatusBadge label={order.ingestStatus} tone={tone(order.ingestStatus)} /><StatusBadge label={order.deliveryStatus} tone={tone(order.deliveryStatus)} /></div></td>
                <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">{order.sourceUpdatedAt ? formatDateTime(order.sourceUpdatedAt, locale) : "—"}</td>
                <td className="px-4 py-3 text-right"><Button type="button" variant="ghost" size="icon" title="查看详情" onClick={() => void openDetail(order.id)}><Eye className="size-4" /></Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </DataTableShell>
      {detail && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" role="dialog" aria-modal="true">
        <div className="max-h-[85vh] w-full max-w-3xl overflow-y-auto rounded-surface bg-surface p-5 shadow-xl">
          <div className="flex items-start justify-between gap-3"><div><h2 className="text-lg font-semibold">渠道订单详情</h2><p className="mt-1 font-mono text-xs text-muted-foreground">{detail.order.externalOrderNo}</p></div><Button type="button" variant="outline" onClick={() => setDetail(null)}>关闭</Button></div>
          <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2"><div><dt className="text-muted-foreground">商品</dt><dd>{detail.order.productTitle || "—"}</dd></div><div><dt className="text-muted-foreground">规格</dt><dd>{detail.order.specName || "无规格"}</dd></div><div><dt className="text-muted-foreground">金额</dt><dd>{formatYuan(Number(detail.order.amount))}</dd></div><div><dt className="text-muted-foreground">MyShop 订单</dt><dd>{detail.order.myshopOrderNo || "未创建"}</dd></div><div><dt className="text-muted-foreground">错误</dt><dd>{detail.order.lastErrorCode || "—"}{detail.order.lastErrorMessage ? ` · ${detail.order.lastErrorMessage}` : ""}</dd></div></dl>
          <h3 className="mt-6 font-semibold">Delivery operations</h3>
          <div className="mt-2 space-y-2">{detail.deliveryOperations.length === 0 ? <p className="text-sm text-muted-foreground">暂无交付操作。</p> : detail.deliveryOperations.map((operation) => <div key={operation.id} className="rounded-card border border-border-subtle p-3 text-sm"><div className="flex flex-wrap items-center gap-2"><StatusBadge label={operation.operationType} tone="info" /><StatusBadge label={operation.status} tone={tone(operation.status)} /><span className="text-xs text-muted-foreground">unit {operation.unitIndex ?? "—"}</span></div><div className="mt-2 break-all font-mono text-xs text-muted-foreground">{operation.requestId}</div>{operation.lastErrorCode && <div className="mt-1 text-xs text-destructive">{operation.lastErrorCode} · {operation.lastErrorMessage || ""}</div>}</div>)}</div>
        </div>
      </div>}
    </AdminPage>
  )
}
