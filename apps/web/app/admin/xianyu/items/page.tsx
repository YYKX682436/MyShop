"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { Link2, Loader2, Package, RefreshCw } from "lucide-react"
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
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import {
  adminSalesChannelApi,
  requestOrThrow,
  type SalesChannelAccountSummary,
  type XianyuCatalogItemSummary,
  type XianyuItemBindingResponse,
  type XianyuTakeoverResponse,
  type XianyuLinkCandidate,
  type XianyuLinkCandidatesPage,
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

function candidateReason(eligibility: XianyuLinkCandidate["eligibility"]): string {
  switch (eligibility) {
    case "ALREADY_LINKED_THIS_ITEM": return "已经关联此闲鱼商品"
    case "LINKED_TO_OTHER_ITEM": return "已经关联其它闲鱼商品"
    case "LISTING_NOT_LINKABLE": return "已有 listing 不满足安全复用条件"
    default: return "可关联"
  }
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
  const [linkDialogOpen, setLinkDialogOpen] = useState(false)
  const [selectedItem, setSelectedItem] = useState<XianyuCatalogItemSummary | null>(null)
  const [candidateKeyword, setCandidateKeyword] = useState("")
  const [debouncedCandidateKeyword, setDebouncedCandidateKeyword] = useState("")
  const [candidatePage, setCandidatePage] = useState(1)
  const [candidatePageSize] = useState(10)
  const [candidatesPage, setCandidatesPage] = useState<XianyuLinkCandidatesPage | null>(null)
  const [candidateLoading, setCandidateLoading] = useState(false)
  const [candidateLoadError, setCandidateLoadError] = useState(false)
  const [selectedProductId, setSelectedProductId] = useState("")
  const [bindingSubmitting, setBindingSubmitting] = useState(false)
  const [takeoverDialogOpen, setTakeoverDialogOpen] = useState(false)
  const [takeoverItem, setTakeoverItem] = useState<XianyuCatalogItemSummary | null>(null)
  const [takeoverSubmitting, setTakeoverSubmitting] = useState(false)

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

  useEffect(() => {
    if (!linkDialogOpen) return
    const timer = setTimeout(() => {
      setDebouncedCandidateKeyword(candidateKeyword.trim())
      setCandidatePage(1)
    }, 300)
    return () => clearTimeout(timer)
  }, [candidateKeyword, linkDialogOpen])

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

  const loadCandidates = useCallback(async () => {
    if (!linkDialogOpen || !accountId || !selectedItem) {
      setCandidatesPage(null)
      return
    }
    setCandidateLoading(true)
    setCandidateLoadError(false)
    try {
      setCandidatesPage(await requestOrThrow(() => adminSalesChannelApi.getXianyuLinkCandidates(
        accountId,
        selectedItem.itemId,
        { page: candidatePage, pageSize: candidatePageSize, keyword: debouncedCandidateKeyword },
      )))
    } catch {
      setCandidatesPage(null)
      setCandidateLoadError(true)
    } finally {
      setCandidateLoading(false)
    }
  }, [accountId, candidatePage, candidatePageSize, debouncedCandidateKeyword, linkDialogOpen, selectedItem])

  useEffect(() => { void loadCandidates() }, [loadCandidates])

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

  const openLinkDialog = (item: XianyuCatalogItemSummary) => {
    setSelectedItem(item)
    setCandidateKeyword("")
    setDebouncedCandidateKeyword("")
    setCandidatePage(1)
    setSelectedProductId("")
    setCandidateLoadError(false)
    setLinkDialogOpen(true)
  }

  const closeLinkDialog = (open: boolean) => {
    setLinkDialogOpen(open)
    if (!open) {
      setSelectedItem(null)
      setCandidatesPage(null)
      setSelectedProductId("")
    }
  }

  const confirmBinding = async () => {
    if (!accountId || !selectedItem || !selectedProductId || bindingSubmitting) return
    setBindingSubmitting(true)
    try {
      const result: XianyuItemBindingResponse = await requestOrThrow(() => adminSalesChannelApi.bindXianyuItem(
        accountId, selectedItem.itemId, selectedProductId,
      ))
      toast.success(result.result === "ALREADY_LINKED"
        ? "该商品已经关联。本次仅建立本地关联，未修改闲鱼商品。"
        : "关联成功。本次仅建立本地关联，未修改闲鱼商品。")
      closeLinkDialog(false)
      setRefreshVersion((version) => version + 1)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "关联商品失败")
    } finally {
      setBindingSubmitting(false)
    }
  }

  const openTakeoverDialog = (item: XianyuCatalogItemSummary) => {
    setTakeoverItem(item)
    setTakeoverDialogOpen(true)
  }

  const closeTakeoverDialog = (open: boolean) => {
    setTakeoverDialogOpen(open)
    if (!open) setTakeoverItem(null)
  }

  const confirmTakeover = async () => {
    if (!accountId || !takeoverItem || takeoverSubmitting) return
    setTakeoverSubmitting(true)
    try {
      const result: XianyuTakeoverResponse = await requestOrThrow(() => adminSalesChannelApi.takeoverXianyuItem(
        accountId, takeoverItem.itemId,
      ))
      if (result.result === "TAKEOVER_PENDING") {
        toast.info("接管仍待完成，可稍后继续接管。")
      } else if (result.result === "OWNERSHIP_REPAIRED") {
        toast.success("Agent ownership 已安全修复，MyShop 保持原有 listing。")
      } else if (result.result === "ALREADY_MANAGED") {
        toast.success("该商品已经由 MyShop 管理。")
      } else {
        toast.success("接管完成。闲鱼商品仍保持原样，MyShop 已成为管理方。")
      }
      closeTakeoverDialog(false)
      setRefreshVersion((version) => version + 1)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "接管闲鱼商品失败")
    } finally {
      setTakeoverSubmitting(false)
    }
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
            <table className="w-full min-w-[1240px] text-sm">
              <thead><tr className="border-b border-border-subtle bg-surface-muted">
                <th className="px-4 py-3 text-left">商品 ID</th>
                <th className="px-4 py-3 text-left">商品标题</th>
                <th className="px-4 py-3 text-left">价格</th>
                <th className="px-4 py-3 text-left">闲鱼状态</th>
                <th className="px-4 py-3 text-left">库存</th>
                <th className="px-4 py-3 text-left">规格</th>
                <th className="px-4 py-3 text-left">MyShop 关联</th>
                <th className="px-4 py-3 text-left">操作</th>
                <th className="px-4 py-3 text-left">最近缓存更新</th>
              </tr></thead>
              <tbody>
                {loading && <TableLoadingState colSpan={9} />}
                {!loading && loadError && <TableErrorState colSpan={9} onRetry={() => setRefreshVersion((version) => version + 1)} />}
                {!loading && !loadError && (itemsPage?.items.length ?? 0) === 0 && (
                  <TableEmptyState colSpan={9}>{debouncedKeyword ? "没有找到匹配的闲鱼商品" : "该闲鱼账号当前没有缓存商品"}</TableEmptyState>
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
                        {item.bindingState === "LINKED" && (
                          <StatusBadge
                            label={item.managementMode === "LINKED_ONLY" ? "仅关联" : item.managementMode === "TAKEOVER_PENDING" ? "接管待完成" : "MyShop 管理"}
                            tone={item.managementMode === "MANAGED" ? "success" : "info"}
                          />
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
                    <td className="px-4 py-3 whitespace-nowrap">
                      {item.bindingState === "UNLINKED" && (
                        <Button type="button" variant="outline" size="sm" onClick={() => openLinkDialog(item)}>
                          <Link2 />关联商品
                        </Button>
                      )}
                      {item.bindingState === "LINKED" && (item.managementMode === "LINKED_ONLY" || item.managementMode === "TAKEOVER_PENDING") && (
                        <Button type="button" variant="outline" size="sm" onClick={() => openTakeoverDialog(item)} disabled={takeoverSubmitting}>
                          <Link2 />{item.managementMode === "TAKEOVER_PENDING" ? "继续接管" : "接管到 MyShop"}
                        </Button>
                      )}
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

      <Dialog open={linkDialogOpen} onOpenChange={closeLinkDialog}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>关联 MyShop 商品</DialogTitle>
            <DialogDescription>
              只建立本地 identity binding，不会接管闲鱼商品。
            </DialogDescription>
          </DialogHeader>

          {selectedItem && (
            <div className="rounded-card border border-border-subtle bg-surface-muted/40 p-4 text-sm">
              <div className="font-medium">闲鱼商品：{selectedItem.title || "—"}</div>
              <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span className="font-mono">itemId {selectedItem.itemId}</span>
                <span>状态：{selectedItem.statusDesc || "—"}</span>
              </div>
            </div>
          )}

          <div className="space-y-3">
            <label className="text-sm font-medium" htmlFor="xianyu-link-product-search">MyShop 商品搜索</label>
            <Input
              id="xianyu-link-product-search"
              value={candidateKeyword}
              onChange={(event) => setCandidateKeyword(event.target.value)}
              placeholder="搜索商品编号或标题"
              aria-label="搜索 MyShop 商品"
            />
          </div>

          <div className="rounded-card border border-border-subtle">
            {candidateLoading && <p className="p-4 text-sm text-muted-foreground" role="status">正在加载商品候选…</p>}
            {!candidateLoading && candidateLoadError && (
              <div className="p-4 text-sm text-destructive">
                <p>加载商品候选失败。</p>
                <Button type="button" variant="outline" size="sm" className="mt-3" onClick={() => void loadCandidates()}>重试</Button>
              </div>
            )}
            {!candidateLoading && !candidateLoadError && candidatesPage && candidatesPage.candidates.length === 0 && (
              <p className="p-4 text-sm text-muted-foreground">没有找到匹配的 MyShop 商品。</p>
            )}
            {!candidateLoading && !candidateLoadError && candidatesPage && candidatesPage.candidates.length > 0 && (
              <div className="divide-y divide-border-subtle">
                {candidatesPage.candidates.map((candidate) => {
                  const available = candidate.eligibility === "AVAILABLE"
                  const selected = candidate.productId === selectedProductId
                  return (
                    <div key={candidate.productId} className="flex items-center gap-3 p-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                          <span className="font-mono text-xs">#{candidate.productNo}</span>
                          <span className="truncate">{candidate.title}</span>
                          <StatusBadge label={candidate.isEnabled ? "启用" : "停用"} tone={candidate.isEnabled ? "success" : "neutral"} />
                        </div>
                        <div className={`mt-1 text-xs ${available ? "text-emerald-700 dark:text-emerald-300" : "text-muted-foreground"}`}>
                          {candidateReason(candidate.eligibility)}
                          {candidate.managementMode && ` · ${candidate.managementMode === "LINKED_ONLY" ? "仅关联" : candidate.managementMode === "TAKEOVER_PENDING" ? "接管待完成" : "MyShop 管理"}`}
                        </div>
                      </div>
                      <Button
                        type="button"
                        size="sm"
                        variant={selected ? "default" : "outline"}
                        disabled={!available || bindingSubmitting}
                        onClick={() => setSelectedProductId(candidate.productId)}
                      >
                        {selected ? "已选择" : "选择"}
                      </Button>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          {candidatesPage && (
            <AdminPagination
              page={candidatePage}
              total={candidatesPage.total}
              pageSize={candidatePageSize}
              onPageChange={setCandidatePage}
            />
          )}

          <div className="rounded-card border border-amber-500/40 bg-amber-500/10 p-4 text-sm leading-6">
            本操作只建立 MyShop 与该闲鱼商品的本地关联关系。不会修改闲鱼商品，不会同步价格或库存，
            不会上下架商品，也不会开启自动发货。
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => closeLinkDialog(false)} disabled={bindingSubmitting}>取消</Button>
            <Button type="button" onClick={() => void confirmBinding()} disabled={!selectedProductId || bindingSubmitting}>
              {bindingSubmitting && <Loader2 className="animate-spin" />}
              {bindingSubmitting ? "正在关联…" : "确认关联"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={takeoverDialogOpen} onOpenChange={closeTakeoverDialog}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{takeoverItem?.managementMode === "TAKEOVER_PENDING" ? "继续接管闲鱼商品" : "接管闲鱼商品"}</DialogTitle>
            <DialogDescription>
              请确认 ownership/control-plane handoff。接管不会立即修改闲鱼商品。
            </DialogDescription>
          </DialogHeader>
          {takeoverItem && (
            <div className="space-y-3 rounded-card border border-border-subtle bg-surface-muted/40 p-4 text-sm leading-6">
              <div className="font-medium">闲鱼商品：{takeoverItem.title || "—"}</div>
              <div className="font-mono text-xs text-muted-foreground">itemId {takeoverItem.itemId}</div>
              <p>接管后 MyShop 将成为该商品的管理方，Agent 原生自动发货将避让该商品。</p>
              <p>本操作不会自动开启：</p>
              <ul className="list-disc space-y-1 pl-5">
                <li>价格同步</li>
                <li>库存同步</li>
                <li>上下架</li>
                <li>订单同步</li>
                <li>自动发货</li>
              </ul>
              <p>自动发货仍需后续单独开启。</p>
              <p>本阶段暂不支持将已接管商品恢复为“仅关联”。</p>
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => closeTakeoverDialog(false)} disabled={takeoverSubmitting}>取消</Button>
            <Button type="button" onClick={() => void confirmTakeover()} disabled={!takeoverItem || takeoverSubmitting}>
              {takeoverSubmitting && <Loader2 className="animate-spin" />}
              {takeoverSubmitting ? "接管处理中…" : "确认接管"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminPage>
  )
}
