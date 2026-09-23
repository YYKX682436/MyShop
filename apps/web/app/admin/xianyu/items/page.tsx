"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { Loader2, Package, RefreshCw } from "lucide-react"
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
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import {
  adminSalesChannelApi,
  requestOrThrow,
  type SalesChannelAccountSummary,
  type XianyuCatalogItemSummary,
  type XianyuItemsPage,
} from "@/services/api"

const bindingTone = (state: XianyuCatalogItemSummary["bindingState"]): "success" | "warning" | "danger" | "info" | "neutral" => {
  if (state === "LINKED") return "success"
  if (state === "CONFLICT") return "danger"
  return "neutral"
}

function apiErrorCode(error: unknown): string | undefined {
  if (!(error instanceof Error)) return undefined
  const params = (error as Error & { params?: Record<string, string | number> }).params
  return typeof params?.errorCode === "string" ? params.errorCode : undefined
}

export default function XianyuItemsPage() {
  const { locale } = useLocale()
  const [accounts, setAccounts] = useState<SalesChannelAccountSummary[]>([])
  const [accountsLoading, setAccountsLoading] = useState(true)
  const [accountsError, setAccountsError] = useState(false)
  const [accountId, setAccountId] = useState("")
  const [keyword, setKeyword] = useState("")
  const [debouncedKeyword, setDebouncedKeyword] = useState("")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [itemsPage, setItemsPage] = useState<XianyuItemsPage | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [authRequired, setAuthRequired] = useState(false)
  const [refreshVersion, setRefreshVersion] = useState(0)

  const loadAccounts = useCallback(async () => {
    setAccountsLoading(true)
    setAccountsError(false)
    try {
      const rows = await requestOrThrow(() => adminSalesChannelApi.getXianyuAccounts())
      setAccounts(rows)
      if (rows.length === 1) setAccountId(rows[0].id)
      else if (accountId && !rows.some((row) => row.id === accountId)) setAccountId("")
    } catch {
      setAccounts([])
      setAccountId("")
      setAccountsError(true)
    } finally {
      setAccountsLoading(false)
    }
  }, [accountId])

  useEffect(() => { void loadAccounts() }, [loadAccounts])

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedKeyword(keyword.trim())
      setPage(1)
    }, 300)
    return () => clearTimeout(timer)
  }, [keyword])

  const loadItems = useCallback(async () => {
    if (!accountId) {
      setItemsPage(null)
      setLoadError(false)
      return
    }
    setLoading(true)
    setLoadError(false)
    try {
      setItemsPage(await requestOrThrow(() => adminSalesChannelApi.getXianyuItems({
        accountId,
        page,
        pageSize,
        keyword: debouncedKeyword,
      })))
    } catch {
      setItemsPage(null)
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [accountId, page, pageSize, debouncedKeyword])

  useEffect(() => { void loadItems() }, [loadItems, refreshVersion])

  const syncItems = async () => {
    if (!accountId) return
    setSyncing(true)
    setAuthRequired(false)
    try {
      const result = await requestOrThrow(() => adminSalesChannelApi.syncXianyuItems(accountId))
      toast.success(`已同步 ${result.fetchedCount} 个闲鱼商品`)
      setPage(1)
      setRefreshVersion((version) => version + 1)
    } catch (error) {
      const requiresLogin = apiErrorCode(error) === "ACCOUNT_AUTH_REQUIRED"
      setAuthRequired(requiresLogin)
      toast.error(requiresLogin
        ? "闲鱼登录已失效，请先重新登录账号。"
        : error instanceof Error ? error.message : "同步闲鱼商品失败")
    } finally {
      setSyncing(false)
    }
  }

  const chooseAccount = (value: string) => {
    setAccountId(value)
    setPage(1)
    setAuthRequired(false)
  }

  return (
    <AdminPage>
      <PageHeader title="闲鱼商品" description="查看并同步闲鱼账号中的商品。" />

      {accountsLoading && <p className="text-sm text-muted-foreground" role="status">正在加载闲鱼账号…</p>}
      {!accountsLoading && accountsError && (
        <div className="rounded-card border border-border-subtle p-4 text-sm text-destructive">
          <p>加载闲鱼账号失败。</p>
          <Button type="button" variant="outline" size="sm" className="mt-3" onClick={() => void loadAccounts()}>
            重试
          </Button>
        </div>
      )}
      {!accountsLoading && !accountsError && accounts.length === 0 && (
        <div className="flex flex-col items-start gap-3 rounded-card border border-border-subtle p-6">
          <p className="text-sm text-muted-foreground">请先添加闲鱼账号</p>
          <Button asChild><Link href="/admin/xianyu/accounts">前往闲鱼账号</Link></Button>
        </div>
      )}

      {!accountsLoading && !accountsError && accounts.length > 0 && (
        <>
          <PageToolbar>
            <div className="flex w-full flex-col gap-3 sm:flex-row sm:items-center">
              <label className="flex min-w-56 items-center gap-3 text-sm">
                <span className="shrink-0 text-muted-foreground">闲鱼账号</span>
                <Select value={accountId} onValueChange={chooseAccount}>
                  <SelectTrigger aria-label="闲鱼账号" className="min-w-56">
                    <SelectValue placeholder="请选择闲鱼账号" />
                  </SelectTrigger>
                  <SelectContent>
                    {accounts.map((account) => (
                      <SelectItem key={account.id} value={account.id}>
                        {account.displayName || account.providerAccountId} · {account.providerAccountId}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>
              <Input
                className="sm:max-w-sm"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜索商品 ID 或标题"
                aria-label="搜索闲鱼商品 ID 或标题"
              />
              <div className="flex flex-wrap gap-2 sm:ml-auto">
                <Button type="button" onClick={() => void syncItems()} disabled={!accountId || loading || syncing}>
                  {syncing ? <Loader2 className="animate-spin" /> : <Package />}
                  {syncing ? "正在从闲鱼同步商品…" : "同步闲鱼商品"}
                </Button>
                <Button type="button" variant="outline" onClick={() => setRefreshVersion((version) => version + 1)} disabled={!accountId || loading || syncing}>
                  <RefreshCw className={loading ? "animate-spin" : ""} />刷新列表
                </Button>
              </div>
            </div>
          </PageToolbar>

          {authRequired && (
            <div className="rounded-card border border-amber-500/40 bg-amber-500/10 p-4 text-sm" role="alert">
              闲鱼登录已失效，请先重新登录账号。 <Link className="font-medium underline" href="/admin/xianyu/accounts">前往账号管理</Link>
            </div>
          )}

          <DataTableShell>
            <table className="w-full min-w-[1120px] text-sm">
              <thead><tr className="border-b border-border-subtle bg-surface-muted">
                <th className="px-4 py-3 text-left">商品 ID</th>
                <th className="px-4 py-3 text-left">商品标题</th>
                <th className="px-4 py-3 text-left">价格</th>
                <th className="px-4 py-3 text-left">闲鱼状态</th>
                <th className="px-4 py-3 text-left">库存</th>
                <th className="px-4 py-3 text-left">规格</th>
                <th className="px-4 py-3 text-left">MyShop 关联</th>
                <th className="px-4 py-3 text-left">最近缓存更新</th>
              </tr></thead>
              <tbody>
                {loading && <TableLoadingState colSpan={8} />}
                {!loading && loadError && <TableErrorState colSpan={8} onRetry={() => setRefreshVersion((version) => version + 1)} />}
                {!loading && !loadError && (itemsPage?.items.length ?? 0) === 0 && (
                  <TableEmptyState colSpan={8}>{debouncedKeyword ? "没有找到匹配的闲鱼商品" : "该闲鱼账号当前没有缓存商品"}</TableEmptyState>
                )}
                {!loading && !loadError && itemsPage?.items.map((item) => (
                  <tr key={item.itemId} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                    <td className="px-4 py-3 font-mono text-xs">{item.itemId}</td>
                    <td className="max-w-[300px] whitespace-normal px-4 py-3 font-medium">{item.title || "—"}</td>
                    <td className="px-4 py-3 whitespace-nowrap">{item.price ? `¥${item.price}` : "—"}</td>
                    <td className="px-4 py-3">{item.statusDesc || "—"}</td>
                    <td className="px-4 py-3">{item.quantity ?? "—"}</td>
                    <td className="px-4 py-3 whitespace-nowrap">{item.isMultiSpec ? `${item.skuCount} 个规格/SKU` : "单规格"}</td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col items-start gap-1">
                        <StatusBadge
                          label={item.bindingState === "LINKED" ? "已关联" : item.bindingState === "CONFLICT" ? "绑定冲突" : "未关联"}
                          tone={bindingTone(item.bindingState)}
                        />
                        {item.bindingState === "LINKED" && item.productId && (
                          <Link className="text-xs text-primary underline-offset-2 hover:underline" href={`/admin/products/${encodeURIComponent(item.productId)}/edit`}>
                            {item.productTitle || "MyShop 商品"}
                          </Link>
                        )}
                        {item.bindingState === "LINKED" && item.listingId && (
                          <span className="font-mono text-[10px] text-muted-foreground">listing {item.listingId}</span>
                        )}
                        {item.bindingState === "CONFLICT" && (
                          <ul className="space-y-1 text-xs text-destructive">
                            {item.bindings.map((binding) => (
                              <li key={binding.listingId}>
                                {binding.productTitle || "MyShop 商品"} · product {binding.productId} · listing {binding.listingId}
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                      {item.catalogUpdatedAt || item.catalogCreatedAt
                        ? formatDateTime(item.catalogUpdatedAt || item.catalogCreatedAt || "", locale)
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </DataTableShell>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              每页
              <select
                className="h-9 rounded-control border border-input bg-surface px-2 text-foreground"
                value={pageSize}
                onChange={(event) => { setPageSize(Number(event.target.value)); setPage(1) }}
                aria-label="每页商品数"
              >
                {[20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}
              </select>
              条
            </label>
            {itemsPage && <AdminPagination page={page} total={itemsPage.total} pageSize={pageSize} onPageChange={setPage} />}
          </div>
        </>
      )}
    </AdminPage>
  )
}
