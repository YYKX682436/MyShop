"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Ban, Eye, FileText, KeyRound, Package, Upload } from "lucide-react"
import { toast } from "sonner"

import { CardContentImportFields } from "@/components/admin/card-content-import-fields"
import { CardContentParts } from "@/components/shared/card-content-parts"
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useLocale } from "@/lib/context"
import { validateCardContentColumns } from "@/lib/card-content"
import { formatDateTime, stripInvisible } from "@/lib/utils"
import { adminCardKeyApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail, AdminProductSpec, CardImportBatch, CardKeyListItem, CardKeyStockSummary } from "@/types"

const PAGE_SIZE = 20

const cardStatusTone = {
  AVAILABLE: "success",
  LOCKED: "warning",
  SOLD: "info",
  INVALID: "danger",
} as const

export default function AdminCardKeysPage() {
  const { t, locale } = useLocale()
  const [tab, setTab] = useState<"stock" | "import">("stock")
  const [products, setProducts] = useState<AdminProductDetail[]>([])
  const [productsError, setProductsError] = useState(false)
  const [stockList, setStockList] = useState<CardKeyStockSummary[]>([])
  const [stockLoading, setStockLoading] = useState(true)
  const [stockError, setStockError] = useState(false)
  const [filterProductId, setFilterProductId] = useState("")
  const [importBatches, setImportBatches] = useState<CardImportBatch[]>([])
  const [importPage, setImportPage] = useState(1)
  const [importTotal, setImportTotal] = useState(0)
  const [importLoading, setImportLoading] = useState(true)
  const [importError, setImportError] = useState(false)

  const [importDialogOpen, setImportDialogOpen] = useState(false)
  const [importProductId, setImportProductId] = useState("")
  const [importSpecId, setImportSpecId] = useState("")
  const [importSpecs, setImportSpecs] = useState<AdminProductSpec[]>([])
  const [loadingSpecs, setLoadingSpecs] = useState(false)
  const [importColumns, setImportColumns] = useState([""])
  const [checkDuplicate, setCheckDuplicate] = useState(true)
  const [importing, setImporting] = useState(false)
  const [importErrors, setImportErrors] = useState<Record<string, boolean>>({})
  const importProductRef = useRef<HTMLSelectElement>(null)

  const [detailItem, setDetailItem] = useState<CardKeyStockSummary | null>(null)
  const [detailKeys, setDetailKeys] = useState<CardKeyListItem[]>([])
  const [detailPage, setDetailPage] = useState(1)
  const [detailTotal, setDetailTotal] = useState(0)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(false)
  const [singleInvalidateTarget, setSingleInvalidateTarget] = useState<CardKeyListItem | null>(null)
  const [singleInvalidating, setSingleInvalidating] = useState(false)
  const [batchInvalidateTarget, setBatchInvalidateTarget] = useState<CardKeyStockSummary | null>(null)
  const [batchInvalidating, setBatchInvalidating] = useState(false)
  const detailRequestRef = useRef(0)

  const importableProducts = products.filter((product) => (product.delivery_type || "AUTO") === "AUTO" && (product.stock_mode || "CARD_KEY") === "CARD_KEY")

  const loadProducts = useCallback(async () => {
    setProductsError(false)
    try {
      const data = await requestOrThrow(() => adminProductApi.getList({ page: 1, page_size: 100 }))
      setProducts(data.list)
    } catch {
      setProducts([])
      setProductsError(true)
    }
  }, [])

  const loadStock = useCallback(async () => {
    setStockLoading(true)
    setStockError(false)
    try {
      const data = await requestOrThrow(() => adminCardKeyApi.getStock(filterProductId ? { product_id: filterProductId } : undefined))
      setStockList(data)
    } catch {
      setStockList([])
      setStockError(true)
    } finally {
      setStockLoading(false)
    }
  }, [filterProductId])

  const loadImportBatches = useCallback(async () => {
    setImportLoading(true)
    setImportError(false)
    try {
      const data = await requestOrThrow(() => adminCardKeyApi.getImportBatches({ page: importPage, page_size: PAGE_SIZE }))
      setImportBatches(data.list)
      setImportTotal(data.pagination.total)
    } catch {
      setImportBatches([])
      setImportTotal(0)
      setImportError(true)
    } finally {
      setImportLoading(false)
    }
  }, [importPage])

  useEffect(() => { void loadProducts() }, [loadProducts])
  useEffect(() => { void loadStock() }, [loadStock])
  useEffect(() => { void loadImportBatches() }, [loadImportBatches])

  const totalKeys = stockList.reduce((sum, row) => sum + (row.total ?? 0), 0)
  const totalAvailable = stockList.reduce((sum, row) => sum + (row.available ?? 0), 0)
  const totalSold = stockList.reduce((sum, row) => sum + row.sold, 0)
  const totalInvalid = stockList.reduce((sum, row) => sum + row.invalid, 0)

  const resetImport = () => {
    setImportProductId("")
    setImportSpecId("")
    setImportSpecs([])
    setImportColumns([""])
    setCheckDuplicate(true)
    setImportErrors({})
  }

  const closeImportDialog = () => {
    setImportDialogOpen(false)
    resetImport()
  }

  const handleImportDialogChange = (open: boolean) => {
    if (!open && !importing) {
      closeImportDialog()
    } else if (open) {
      setImportDialogOpen(true)
    }
  }

  const handleProductChange = async (productId: string) => {
    setImportProductId(productId)
    setImportSpecId("")
    setImportSpecs([])
    setImportErrors((current) => ({ ...current, product: false }))
    if (!productId) return
    setLoadingSpecs(true)
    try {
      setImportSpecs(await requestOrThrow(() => adminProductApi.getSpecs(productId)))
    } catch {
      setImportSpecs([])
    } finally {
      setLoadingSpecs(false)
    }
  }

  const handleImport = async () => {
    const validationError = validateCardContentColumns(importColumns)
    if (!importProductId || validationError) {
      setImportErrors({ product: !importProductId, content: Boolean(validationError) })
      if (!importProductId) {
        toast.error(t("admin.selectProductReq"))
        importProductRef.current?.focus()
      } else if (validationError) {
        toast.error(validationError)
      }
      return
    }

    setImporting(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.import({
        product_id: importProductId,
        spec_id: importSpecId || null,
        content_columns: importColumns,
        check_duplicate: checkDuplicate,
      }))
      toast.success(
        t("admin.importSummary")
          .replace("{total}", String(result.total_count))
          .replace("{success}", String(result.success_count))
          .replace("{duplicate}", String(result.duplicate_count))
          .replace("{failed}", String(result.fail_count)),
      )
      closeImportDialog()
      await Promise.all([loadStock(), loadImportBatches()])
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.importFailed"))
    } finally {
      setImporting(false)
    }
  }

  const fetchDetailKeys = useCallback(async (item: CardKeyStockSummary, page: number) => {
    const detailRequestId = ++detailRequestRef.current
    setDetailLoading(true)
    setDetailError(false)
    try {
      const data = await requestOrThrow(() => adminCardKeyApi.getList({ product_id: item.product_id, spec_id: item.spec_id, page, page_size: PAGE_SIZE }))
      if (detailRequestRef.current !== detailRequestId) return
      setDetailKeys(data.list)
      setDetailTotal(data.pagination.total)
    } catch {
      if (detailRequestRef.current !== detailRequestId) return
      setDetailKeys([])
      setDetailTotal(0)
      setDetailError(true)
    } finally {
      if (detailRequestRef.current === detailRequestId) setDetailLoading(false)
    }
  }, [])

  const openDetail = (item: CardKeyStockSummary) => {
    setDetailItem(item)
    setDetailPage(1)
    void fetchDetailKeys(item, 1)
  }

  const closeDetail = (open: boolean) => {
    if (!open) {
      detailRequestRef.current += 1
      setDetailItem(null)
      setDetailKeys([])
      setDetailTotal(0)
      setDetailLoading(false)
      setDetailError(false)
    }
  }

  const handleDetailPageChange = (page: number) => {
    setDetailPage(page)
    if (detailItem) void fetchDetailKeys(detailItem, page)
  }

  const copyToClipboard = (value: string) => {
    const cleanValue = stripInvisible(value)
    const fallback = () => {
      const textarea = document.createElement("textarea")
      textarea.value = cleanValue
      textarea.style.position = "fixed"
      textarea.style.opacity = "0"
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand("copy")
      document.body.removeChild(textarea)
      toast.success(t("order.copied"))
    }
    if (navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(cleanValue).then(() => toast.success(t("order.copied")), fallback)
    } else fallback()
  }

  const handleSingleInvalidate = async () => {
    if (!singleInvalidateTarget) return
    setSingleInvalidating(true)
    try {
      await requestOrThrow(() => adminCardKeyApi.invalidate(singleInvalidateTarget.id))
      toast.success(t("admin.keyInvalidated"))
      setSingleInvalidateTarget(null)
      if (detailItem) await fetchDetailKeys(detailItem, detailPage)
      await loadStock()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.invalidateFailed"))
    } finally {
      setSingleInvalidating(false)
    }
  }

  const handleBatchInvalidate = async () => {
    if (!batchInvalidateTarget) return
    setBatchInvalidating(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.batchInvalidate({ product_id: batchInvalidateTarget.product_id, spec_id: batchInvalidateTarget.spec_id }))
      toast.success(t("admin.keysInvalidated").replace("{count}", String(result.invalidated_count)))
      setBatchInvalidateTarget(null)
      await loadStock()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.invalidateFailed"))
    } finally {
      setBatchInvalidating(false)
    }
  }

  const stockModeLabel = (item: CardKeyStockSummary) => {
    if (item.unlimited || item.stock_mode === "UNLIMITED") return t("admin.inventoryUnlimited")
    if (item.stock_mode === "MANUAL") return t("admin.inventoryManual")
    return t("admin.inventoryCard")
  }

  const cardStatusLabel = (status: CardKeyListItem["status"]) => ({
    AVAILABLE: t("admin.cardAvailable"),
    LOCKED: t("admin.cardLocked"),
    SOLD: t("admin.cardSold"),
    INVALID: t("admin.cardInvalid"),
  })[status]

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.cardKeys")}
        description={t("admin.cardKeysDesc")}
        actions={<Button type="button" onClick={() => handleImportDialogChange(true)} disabled={productsError || importableProducts.length === 0}><Upload className="h-4 w-4" aria-hidden="true" />{t("admin.batchImport")}</Button>}
      />

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        {[
          { label: t("admin.totalKeys"), value: totalKeys, icon: KeyRound, tone: "text-blue-500" },
          { label: t("admin.availableStock"), value: totalAvailable, icon: Package, tone: "text-emerald-500" },
          { label: t("admin.soldOut"), value: totalSold, icon: FileText, tone: "text-muted-foreground" },
          { label: t("admin.invalidKeys"), value: totalInvalid, icon: Ban, tone: "text-destructive" },
        ].map((stat) => (
          <div key={stat.label} className="rounded-xl border border-border bg-card p-4">
            <div className="flex items-center gap-2"><stat.icon className={`h-4 w-4 ${stat.tone}`} aria-hidden="true" /><span className="text-sm text-muted-foreground">{stat.label}</span></div>
            <p className="mt-1 text-2xl font-semibold text-foreground">{stat.value}</p>
          </div>
        ))}
      </div>

      <Tabs value={tab} onValueChange={(value) => setTab(value as "stock" | "import")}>
        <TabsList>
          <TabsTrigger value="stock">{t("admin.stockOverview")}</TabsTrigger>
          <TabsTrigger value="import">{t("admin.importRecords")}</TabsTrigger>
        </TabsList>

        <TabsContent value="stock" className="flex flex-col gap-4">
          <PageToolbar>
            <select className="h-10 w-full max-w-xs rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" value={filterProductId} onChange={(event) => setFilterProductId(event.target.value)} disabled={productsError} aria-label={t("admin.allProducts")}>
              <option value="">{t("admin.allProducts")}</option>
              {products.map((product) => <option key={product.id} value={product.id}>{product.title}</option>)}
            </select>
            {productsError && <Button type="button" variant="outline" size="sm" onClick={() => void loadProducts()}>{t("common.retry")}</Button>}
          </PageToolbar>

          <DataTableShell>
            <table className="w-full min-w-[900px] text-sm">
              <thead><tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.productName2")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.specLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.inventoryMode")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.totalKeys")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.soldKeys")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.availableKeys")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.invalidKeys")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr></thead>
              <tbody>
                {stockLoading && <TableLoadingState colSpan={8} />}
                {!stockLoading && stockError && <TableErrorState colSpan={8} onRetry={() => void loadStock()} />}
                {!stockLoading && !stockError && stockList.length === 0 && <TableEmptyState colSpan={8}>{t("admin.noStockData")}</TableEmptyState>}
                {!stockLoading && !stockError && stockList.map((item, index) => {
                  const isCardKey = item.stock_mode === "CARD_KEY" || !item.stock_mode
                  return (
                    <tr key={`${item.product_id}-${item.spec_id || "default"}-${index}`} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                      <td className="px-4 py-3 font-medium text-foreground">{item.product_title}</td>
                      <td className="px-4 py-3 text-muted-foreground">{item.spec_name || t("admin.defaultSpec")}{item.spec_enabled === false && <span className="ml-1 text-xs text-amber-600">· {t("admin.disabled")}</span>}</td>
                      <td className="px-4 py-3"><StatusBadge label={stockModeLabel(item)} tone={item.stock_mode === "MANUAL" ? "warning" : item.stock_mode === "UNLIMITED" || item.unlimited ? "success" : "info"} /></td>
                      <td className="px-4 py-3">{item.unlimited ? t("admin.unlimited") : item.total ?? 0}</td>
                      <td className="px-4 py-3">{item.sold}</td>
                      <td className="px-4 py-3 font-medium">{item.unlimited ? t("admin.unlimited") : item.available ?? 0}</td>
                      <td className={item.invalid > 0 ? "px-4 py-3 text-destructive" : "px-4 py-3"}>{item.invalid}</td>
                      <td className="px-4 py-3"><div className="flex justify-end gap-1">
                        {isCardKey && <>
                          <Button type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")} onClick={() => openDetail(item)}><Eye className="h-4 w-4" aria-hidden="true" /></Button>
                          <Button type="button" variant="ghost" size="icon" title={t("admin.batchInvalidate")} aria-label={t("admin.batchInvalidate")} disabled={(item.available ?? 0) <= 0} onClick={() => setBatchInvalidateTarget(item)}><Ban className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>
                        </>}
                      </div></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </DataTableShell>
        </TabsContent>

        <TabsContent value="import" className="flex flex-col gap-4">
          <DataTableShell footer={<AdminPagination page={importPage} total={importTotal} pageSize={PAGE_SIZE} onPageChange={setImportPage} />}>
            <table className="w-full min-w-[850px] text-sm">
              <thead><tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.batchId")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.product")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.columnCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.importCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.successCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.duplicateCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.failCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
              </tr></thead>
              <tbody>
                {importLoading && <TableLoadingState colSpan={8} />}
                {!importLoading && importError && <TableErrorState colSpan={8} onRetry={() => void loadImportBatches()} />}
                {!importLoading && !importError && importBatches.length === 0 && <TableEmptyState colSpan={8}>{t("admin.noImportData")}</TableEmptyState>}
                {!importLoading && !importError && importBatches.map((batch) => (
                  <tr key={batch.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                    <td className="px-4 py-3 font-mono text-xs">{batch.id.length > 16 ? `${batch.id.slice(0, 8)}…` : batch.id}</td>
                    <td className="px-4 py-3">{products.find((product) => product.id === batch.product_id)?.title || batch.product_id}</td>
                    <td className="px-4 py-3">{batch.column_count ?? 1}</td>
                    <td className="px-4 py-3">{batch.total_count}</td>
                    <td className="px-4 py-3 text-emerald-600">{batch.success_count}</td>
                    <td className="px-4 py-3 text-amber-600">{batch.duplicate_count}</td>
                    <td className={batch.fail_count > 0 ? "px-4 py-3 text-destructive" : "px-4 py-3"}>{batch.fail_count}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(batch.created_at, locale)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </DataTableShell>
        </TabsContent>
      </Tabs>

      <Dialog open={importDialogOpen} onOpenChange={handleImportDialogChange}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
          <DialogHeader><DialogTitle>{t("admin.batchImportKeys")}</DialogTitle><DialogDescription>{t("admin.cardImportHint")}</DialogDescription></DialogHeader>
          <div className="flex flex-col gap-4 py-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectProductReq")}
              <select ref={importProductRef} className={`h-10 rounded-md border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 ${importErrors.product ? "border-destructive ring-2 ring-destructive/20" : "border-input focus:ring-ring"}`} value={importProductId} onChange={(event) => void handleProductChange(event.target.value)} disabled={importing}>
                <option value="">{t("admin.selectProductPlaceholder")}</option>
                {importableProducts.map((product) => <option key={product.id} value={product.id}>{product.title}</option>)}
              </select>
            </label>
            {loadingSpecs && <p className="text-sm text-muted-foreground">{t("admin.loadingSpecs")}</p>}
            {!loadingSpecs && importSpecs.length > 0 && <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectSpec")}
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" value={importSpecId} onChange={(event) => setImportSpecId(event.target.value)} disabled={importing}>
                <option value="">{t("admin.defaultSpec")}</option>
                {importSpecs.map((spec) => <option key={spec.id} value={spec.id}>{spec.name}</option>)}
              </select>
            </label>}
            <div className={importErrors.content ? "rounded-lg ring-1 ring-destructive" : undefined}><CardContentImportFields columns={importColumns} onChange={(columns) => { setImportColumns(columns); setImportErrors((current) => ({ ...current, content: false })) }} disabled={importing} /></div>
            <label className="flex items-start gap-2 text-sm text-foreground"><input type="checkbox" className="mt-0.5 h-4 w-4 accent-primary" checked={checkDuplicate} onChange={(event) => setCheckDuplicate(event.target.checked)} disabled={importing} /><span>{t("admin.checkDuplicate")}</span></label>
          </div>
          <DialogFooter><Button type="button" variant="outline" onClick={() => handleImportDialogChange(false)} disabled={importing}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleImport()} disabled={importing}>{importing ? t("admin.importing") : t("admin.import")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Sheet open={detailItem !== null} onOpenChange={closeDetail}>
        <SheetContent className="flex w-full flex-col p-0 sm:max-w-5xl">
          <SheetHeader className="border-b border-border px-6 py-5 pr-14">
            <SheetTitle>{t("admin.cardDetail")}</SheetTitle>
            <SheetDescription>{detailItem ? `${detailItem.product_title} · ${detailItem.spec_name || t("admin.defaultSpec")}` : ""}</SheetDescription>
          </SheetHeader>
          <div className="min-h-0 flex-1 overflow-y-auto p-6">
            <DataTableShell footer={<AdminPagination page={detailPage} total={detailTotal} pageSize={PAGE_SIZE} onPageChange={handleDetailPageChange} />}>
              <table className="w-full min-w-[900px] text-sm" onCopy={(event) => { const selected = window.getSelection()?.toString(); if (selected) { event.clipboardData.setData("text/plain", stripInvisible(selected)); event.preventDefault() } }}>
                <thead><tr className="border-b border-border bg-muted/30">
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardContentColumnLabel")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardStatus")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardCreatedAt")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardOrderNo")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardSoldAt")}</th>
                  <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                </tr></thead>
                <tbody>
                  {detailLoading && <TableLoadingState colSpan={6} />}
                  {!detailLoading && detailError && <TableErrorState colSpan={6} onRetry={() => detailItem && void fetchDetailKeys(detailItem, detailPage)} />}
                  {!detailLoading && !detailError && detailKeys.length === 0 && <TableEmptyState colSpan={6}>{t("admin.noCardData")}</TableEmptyState>}
                  {!detailLoading && !detailError && detailKeys.map((key) => (
                    <tr key={key.id} className="border-b border-border/60 last:border-0">
                      <td className="px-4 py-3 align-top"><CardContentParts parts={key.content_parts} onCopy={copyToClipboard} /></td>
                      <td className="px-4 py-3 align-top"><StatusBadge label={cardStatusLabel(key.status)} tone={cardStatusTone[key.status]} /></td>
                      <td className="whitespace-nowrap px-4 py-3 align-top text-xs text-muted-foreground">{formatDateTime(key.created_at, locale)}</td>
                      <td className="px-4 py-3 align-top font-mono text-xs text-muted-foreground">{key.order_id ? `${key.order_id.slice(0, 8)}…` : t("admin.systemNoData")}</td>
                      <td className="whitespace-nowrap px-4 py-3 align-top text-xs text-muted-foreground">{key.sold_at ? formatDateTime(key.sold_at, locale) : t("admin.systemNoData")}</td>
                      <td className="px-4 py-3 text-right align-top">{(key.status === "AVAILABLE" || key.status === "LOCKED") && <Button type="button" variant="ghost" size="icon" title={t("admin.invalidateCard")} aria-label={t("admin.invalidateCard")} onClick={() => setSingleInvalidateTarget(key)}><Ban className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </DataTableShell>
          </div>
        </SheetContent>
      </Sheet>

      <ConfirmAlert
        open={singleInvalidateTarget !== null}
        onOpenChange={(open) => { if (!open && !singleInvalidating) setSingleInvalidateTarget(null) }}
        title={t("admin.invalidateCardTitle")}
        description={singleInvalidateTarget ? `${t("admin.invalidateCardDescription")} ${singleInvalidateTarget.content_parts.join(" · ")}` : undefined}
        confirmLabel={singleInvalidating ? t("admin.invalidating") : t("admin.confirmInvalidate")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={singleInvalidating}
        onConfirm={() => void handleSingleInvalidate()}
      />

      <ConfirmAlert
        open={batchInvalidateTarget !== null}
        onOpenChange={(open) => { if (!open && !batchInvalidating) setBatchInvalidateTarget(null) }}
        title={t("admin.batchInvalidateTitle")}
        description={batchInvalidateTarget ? t("admin.batchInvalidateDescription").replace("{name}", `${batchInvalidateTarget.product_title} · ${batchInvalidateTarget.spec_name || t("admin.defaultSpec")}`).replace("{count}", String(batchInvalidateTarget.available ?? 0)) : undefined}
        confirmLabel={batchInvalidating ? t("admin.invalidating") : t("admin.confirmInvalidate")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={batchInvalidating}
        onConfirm={() => void handleBatchInvalidate()}
      />
    </AdminPage>
  )
}
