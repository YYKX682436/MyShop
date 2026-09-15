"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Ban, Copy, Download, Eye, FileText, KeyRound, Package, Upload } from "lucide-react"
import { toast } from "sonner"

import { CardKeyImportDialog } from "@/components/admin/products/card-key-import-dialog"
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
import { formatCardContents } from "@/lib/card-display"
import { formatDateTime, stripInvisible } from "@/lib/utils"
import { adminCardKeyApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { AdminCardKeyPickupResult, AdminProductDetail, CardImportBatch, CardKeyListItem, CardKeyStockSummary } from "@/types"

const PAGE_SIZE = 20

const cardStatusTone = {
  AVAILABLE: "success",
  LOCKED: "warning",
  SOLD: "info",
  INVALID: "danger",
} as const

function hasProductDetails(product: AdminProductDetail | undefined): product is AdminProductDetail {
  return Boolean(
    product
      && Array.isArray(product.specs)
      && typeof product.spec_enabled === "boolean"
      && typeof product.delivery_type === "string"
      && typeof product.stock_mode === "string",
  )
}

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
  const [importProduct, setImportProduct] = useState<AdminProductDetail | null>(null)
  const [importInitialSpecId, setImportInitialSpecId] = useState<string | null>(null)
  const [importProductLoading, setImportProductLoading] = useState(false)
  const [batchImportSelectorOpen, setBatchImportSelectorOpen] = useState(false)
  const [batchImportProductId, setBatchImportProductId] = useState("")
  const [batchImportProductLoading, setBatchImportProductLoading] = useState(false)

  const [batchPickupDialogOpen, setBatchPickupDialogOpen] = useState(false)
  const [pickupProductId, setPickupProductId] = useState("")
  const [pickupProduct, setPickupProduct] = useState<AdminProductDetail | null>(null)
  const [pickupSpecId, setPickupSpecId] = useState("")
  const [pickupQuantity, setPickupQuantity] = useState("1")
  const [pickupProductLoading, setPickupProductLoading] = useState(false)
  const [pickupStockList, setPickupStockList] = useState<CardKeyStockSummary[]>([])
  const [pickupStockLoading, setPickupStockLoading] = useState(false)
  const [pickupStockError, setPickupStockError] = useState(false)
  const [batchPickingUp, setBatchPickingUp] = useState(false)
  const [pickupResult, setPickupResult] = useState<AdminCardKeyPickupResult | null>(null)

  const [detailItem, setDetailItem] = useState<CardKeyStockSummary | null>(null)
  const [detailKeys, setDetailKeys] = useState<CardKeyListItem[]>([])
  const [detailPage, setDetailPage] = useState(1)
  const [detailTotal, setDetailTotal] = useState(0)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(false)
  const [singleInvalidateTarget, setSingleInvalidateTarget] = useState<CardKeyListItem | null>(null)
  const [singleInvalidating, setSingleInvalidating] = useState(false)
  const [singlePickupTarget, setSinglePickupTarget] = useState<CardKeyListItem | null>(null)
  const [singlePickingUp, setSinglePickingUp] = useState(false)
  const [batchInvalidateTarget, setBatchInvalidateTarget] = useState<CardKeyStockSummary | null>(null)
  const [batchInvalidating, setBatchInvalidating] = useState(false)
  const detailRequestRef = useRef(0)
  const pickupProductRequestRef = useRef(0)

  const importableProducts = products.filter(
    (product) => (product.delivery_type || "AUTO") === "AUTO" && (product.stock_mode || "CARD_KEY") === "CARD_KEY",
  )
  const pickupProducts = products.filter((product) => (product.stock_mode || "CARD_KEY") === "CARD_KEY")

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

  const ensureProductDetail = useCallback(async (productId: string) => {
    const existing = products.find((product) => product.id === productId)
    if (hasProductDetails(existing)) return existing
    const detail = await requestOrThrow(() => adminProductApi.getDetail(productId))
    setProducts((current) => current.map((product) => product.id === detail.id ? detail : product))
    return detail
  }, [products])

  const resetBatchImportSelector = () => {
    setBatchImportProductId("")
    setBatchImportProductLoading(false)
  }

  const openBatchImport = () => {
    resetBatchImportSelector()
    setImportInitialSpecId(null)
    setImportProduct(null)
    setBatchImportSelectorOpen(true)
  }

  const closeBatchImportSelector = () => {
    if (batchImportProductLoading) return
    setBatchImportSelectorOpen(false)
    resetBatchImportSelector()
  }

  const handleBatchImportSelectorChange = (open: boolean) => {
    if (!open) closeBatchImportSelector()
    else setBatchImportSelectorOpen(true)
  }

  const handleBatchImportProduct = async () => {
    if (!batchImportProductId) {
      toast.error(t("admin.selectProductReq"))
      return
    }

    setBatchImportProductLoading(true)
    try {
      const product = await ensureProductDetail(batchImportProductId)
      if (product.stock_mode !== "CARD_KEY" || product.delivery_type !== "AUTO") {
        throw new Error(t("admin.cardImportOnlyAutoCard"))
      }
      setImportInitialSpecId(null)
      setImportProduct(product)
      setBatchImportSelectorOpen(false)
      resetBatchImportSelector()
      setImportDialogOpen(true)
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.importFailed"))
      await Promise.all([loadProducts(), loadStock()])
    } finally {
      setBatchImportProductLoading(false)
    }
  }

  const openImportForStockRow = async (item: CardKeyStockSummary) => {
    setImportProductLoading(true)
    try {
      const product = await ensureProductDetail(item.product_id)
      if (product.stock_mode !== "CARD_KEY" || product.delivery_type !== "AUTO") {
        throw new Error(t("admin.cardImportOnlyAutoCard"))
      }
      if (item.spec_id !== null && (!product.spec_enabled || !product.specs.some((spec) => spec.id === item.spec_id))) {
        throw new Error(t("admin.importSpecInvalid"))
      }
      setImportInitialSpecId(item.spec_id)
      setImportProduct(product)
      setImportDialogOpen(true)
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.importFailed"))
      await Promise.all([loadProducts(), loadStock()])
    } finally {
      setImportProductLoading(false)
    }
  }

  const handleImportDialogChange = (open: boolean) => {
    setImportDialogOpen(open)
    if (!open) {
      setImportProduct(null)
      setImportInitialSpecId(null)
    }
  }

  const resetBatchPickup = () => {
    pickupProductRequestRef.current += 1
    setPickupProductId("")
    setPickupProduct(null)
    setPickupSpecId("")
    setPickupQuantity("1")
    setPickupStockList([])
    setPickupProductLoading(false)
    setPickupStockLoading(false)
    setPickupStockError(false)
  }

  const openBatchPickup = () => {
    resetBatchPickup()
    setBatchPickupDialogOpen(true)
  }

  const closeBatchPickup = () => {
    if (batchPickingUp) return
    setBatchPickupDialogOpen(false)
    resetBatchPickup()
  }

  const handleBatchPickupDialogChange = (open: boolean) => {
    if (!open) closeBatchPickup()
    else setBatchPickupDialogOpen(true)
  }

  const handlePickupProductChange = async (productId: string) => {
    const requestId = ++pickupProductRequestRef.current
    setPickupProductId(productId)
    setPickupProduct(null)
    setPickupSpecId("")
    setPickupQuantity("1")
    setPickupStockList([])
    setPickupStockError(false)
    if (!productId) {
      setPickupProductLoading(false)
      setPickupStockLoading(false)
      return
    }

    setPickupProductLoading(true)
    setPickupStockLoading(true)
    try {
      const [product, stock] = await Promise.all([
        ensureProductDetail(productId),
        requestOrThrow(() => adminCardKeyApi.getStock({ product_id: productId })),
      ])
      if (product.stock_mode !== "CARD_KEY") throw new Error(t("admin.cardImportOnlyAutoCard"))
      if (pickupProductRequestRef.current === requestId) {
        setPickupProduct(product)
        setPickupStockList(stock)
        setPickupStockError(false)
      }
    } catch (requestError: unknown) {
      if (pickupProductRequestRef.current === requestId) {
        setPickupStockError(true)
        toast.error(requestError instanceof Error ? requestError.message : t("admin.pickupFailed"))
      }
    } finally {
      if (pickupProductRequestRef.current === requestId) setPickupProductLoading(false)
      if (pickupProductRequestRef.current === requestId) setPickupStockLoading(false)
    }
  }

  const pickupStock = pickupProductId && pickupProduct
    && (!pickupProduct.spec_enabled || Boolean(pickupSpecId))
    ? pickupStockList.find((row) => row.product_id === pickupProductId && (row.spec_id || null) === (pickupSpecId || null))
    : undefined
  const pickupAvailable = pickupStock?.available ?? null
  const pickupQuantityNumber = Number(pickupQuantity)
  const pickupQuantityValid = Number.isSafeInteger(pickupQuantityNumber) && pickupQuantityNumber > 0

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

  const refreshCardKeyData = useCallback(async () => {
    const detailRefresh = detailItem
      ? fetchDetailKeys(detailItem, detailPage)
      : Promise.resolve()
    const pickupStockRefresh = pickupProductId
      ? requestOrThrow(() => adminCardKeyApi.getStock({ product_id: pickupProductId }))
          .then((data) => {
            setPickupStockList(data)
            setPickupStockError(false)
          })
          .catch(() => setPickupStockError(true))
      : Promise.resolve()
    await Promise.all([loadProducts(), loadStock(), detailRefresh, pickupStockRefresh])
  }, [detailItem, detailPage, fetchDetailKeys, loadProducts, loadStock, pickupProductId])

  const handleImportCompleted = async () => {
    await Promise.all([refreshCardKeyData(), loadImportBatches()])
  }

  const handleBatchPickup = async () => {
    if (!pickupProductId || !pickupProduct) {
      toast.error(t("admin.selectProductReq"))
      return
    }
    if (pickupProduct.spec_enabled && !pickupSpecId) {
      toast.error(t("admin.specRequiredForImport"))
      return
    }
    if (!pickupQuantityValid) {
      toast.error(t("admin.pickupQuantityRequired"))
      return
    }

    setBatchPickingUp(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.batchPickup({
        product_id: pickupProductId,
        spec_id: pickupSpecId || null,
        quantity: pickupQuantityNumber,
      }))
      setBatchPickupDialogOpen(false)
      resetBatchPickup()
      setPickupResult(result)
      await refreshCardKeyData()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.pickupFailed"))
      // The server is authoritative after a shortage or lock conflict; never
      // infer a partial success from the requested quantity.
      await refreshCardKeyData()
    } finally {
      setBatchPickingUp(false)
    }
  }

  const handleSinglePickup = async () => {
    if (!singlePickupTarget) return
    setSinglePickingUp(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.pickup(singlePickupTarget.id))
      setSinglePickupTarget(null)
      setPickupResult(result)
      await refreshCardKeyData()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.pickupFailed"))
      await refreshCardKeyData()
    } finally {
      setSinglePickingUp(false)
    }
  }

  const copyAllPickedUpCards = () => {
    if (!pickupResult) return
    copyToClipboard(formatCardContents(pickupResult.cards))
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
        actions={
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" onClick={openBatchImport} disabled={productsError || importableProducts.length === 0 || batchImportProductLoading}>
              <Upload className="h-4 w-4" aria-hidden="true" />{t("admin.batchImport")}
            </Button>
            <Button type="button" onClick={openBatchPickup} disabled={productsError || pickupProducts.length === 0}>
              <Download className="h-4 w-4" aria-hidden="true" />{t("admin.batchPickup")}
            </Button>
          </div>
        }
      />

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        {[
          { label: t("admin.totalKeys"), value: totalKeys, icon: KeyRound, tone: "text-blue-500" },
          { label: t("admin.availableStock"), value: totalAvailable, icon: Package, tone: "text-emerald-500" },
          { label: t("admin.soldOut"), value: totalSold, icon: FileText, tone: "text-muted-foreground" },
          { label: t("admin.invalidKeys"), value: totalInvalid, icon: Ban, tone: "text-destructive" },
        ].map((stat) => (
          <div key={stat.label} className="content-surface p-4">
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
            <select className="h-11 w-full max-w-xs rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm focus:outline-none focus:ring-2 focus:ring-ring" value={filterProductId} onChange={(event) => setFilterProductId(event.target.value)} disabled={productsError} aria-label={t("admin.allProducts")}>
              <option value="">{t("admin.allProducts")}</option>
              {products.map((product) => <option key={product.id} value={product.id}>{product.title}</option>)}
            </select>
            {productsError && <Button type="button" variant="outline" size="sm" onClick={() => void loadProducts()}>{t("common.retry")}</Button>}
          </PageToolbar>

          <DataTableShell>
            <table className="w-full min-w-[1000px] text-sm">
              <thead><tr className="border-b border-border-subtle bg-surface-muted">
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
                    <tr key={`${item.product_id}-${item.spec_id || "default"}-${index}`} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                      <td className="px-4 py-3 font-medium text-foreground">{item.product_title}</td>
                      <td className="px-4 py-3 text-muted-foreground">{item.spec_name || t("admin.defaultSpec")}{item.spec_enabled === false && <span className="ml-1 text-xs text-amber-600">· {t("admin.disabled")}</span>}</td>
                      <td className="px-4 py-3"><StatusBadge label={stockModeLabel(item)} tone={item.stock_mode === "MANUAL" ? "warning" : item.stock_mode === "UNLIMITED" || item.unlimited ? "success" : "info"} /></td>
                      <td className="px-4 py-3">{item.unlimited ? t("admin.unlimited") : item.total ?? 0}</td>
                      <td className="px-4 py-3">{item.sold}</td>
                      <td className="px-4 py-3 font-medium">{item.unlimited ? t("admin.unlimited") : item.available ?? 0}</td>
                      <td className={item.invalid > 0 ? "px-4 py-3 text-destructive" : "px-4 py-3"}>{item.invalid}</td>
                      <td className="px-4 py-3"><div className="flex justify-end gap-1">
                        {isCardKey && <>
                          <Button type="button" variant="ghost" size="icon" title={t("admin.import")} aria-label={t("admin.import")} disabled={importProductLoading} onClick={() => void openImportForStockRow(item)}><Upload className="h-4 w-4" aria-hidden="true" /></Button>
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
              <thead><tr className="border-b border-border-subtle bg-surface-muted">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.product")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.columnCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.importCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.successCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.duplicateCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.failCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
              </tr></thead>
              <tbody>
                {importLoading && <TableLoadingState colSpan={7} />}
                {!importLoading && importError && <TableErrorState colSpan={7} onRetry={() => void loadImportBatches()} />}
                {!importLoading && !importError && importBatches.length === 0 && <TableEmptyState colSpan={7}>{t("admin.noImportData")}</TableEmptyState>}
                {!importLoading && !importError && importBatches.map((batch) => (
                  <tr key={batch.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                    <td className="px-4 py-3">{products.find((product) => product.id === batch.product_id)?.title || t("admin.systemNoData")}</td>
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

      <Dialog open={batchImportSelectorOpen} onOpenChange={handleBatchImportSelectorChange}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{t("admin.batchImport")}</DialogTitle>
            <DialogDescription>{t("admin.batchImportProductHint")}</DialogDescription>
          </DialogHeader>
          <div className="py-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectProductReq")}
              <select
                className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={batchImportProductId}
                onChange={(event) => setBatchImportProductId(event.target.value)}
                disabled={batchImportProductLoading}
              >
                <option value="">{t("admin.selectProductPlaceholder")}</option>
                {importableProducts.map((product) => <option key={product.id} value={product.id}>{product.title}</option>)}
              </select>
            </label>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={closeBatchImportSelector} disabled={batchImportProductLoading}>{t("admin.cancel")}</Button>
            <Button type="button" onClick={() => void handleBatchImportProduct()} disabled={batchImportProductLoading || !batchImportProductId}>
              {batchImportProductLoading ? t("common.loading") : t("common.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <CardKeyImportDialog
        product={importProduct}
        initialSpecId={importInitialSpecId}
        open={importDialogOpen}
        onOpenChange={handleImportDialogChange}
        onImported={handleImportCompleted}
      />

      <Dialog open={batchPickupDialogOpen} onOpenChange={handleBatchPickupDialogChange}>
        <DialogContent className="max-h-[90vh] max-w-xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t("admin.batchPickupTitle")}</DialogTitle>
            <DialogDescription>{t("admin.pickupConfirmDescription")}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-4 py-2">
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectProductReq")}
              <select
                className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={pickupProductId}
                onChange={(event) => void handlePickupProductChange(event.target.value)}
                disabled={batchPickingUp}
              >
                <option value="">{t("admin.selectProductPlaceholder")}</option>
                {pickupProducts.map((product) => <option key={product.id} value={product.id}>{product.title}</option>)}
              </select>
            </label>

            {(pickupProductLoading || pickupStockLoading) && <p className="text-sm text-muted-foreground">{t("common.loading")}</p>}

            {pickupProduct?.spec_enabled && (
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {t("admin.selectSpec")}
                <select
                  className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={pickupSpecId}
                  onChange={(event) => setPickupSpecId(event.target.value)}
                  disabled={batchPickingUp}
                >
                  <option value="">{t("admin.chooseSpec")}</option>
                  {pickupProduct.specs.map((spec) => <option key={spec.id} value={spec.id}>{spec.name}</option>)}
                </select>
              </label>
            )}

            <div className="flex items-center justify-between rounded-card border border-border-subtle bg-surface-muted px-3 py-3 text-sm">
              <span className="text-muted-foreground">{t("admin.availableToPickup")}</span>
              <span className="font-semibold text-foreground">{pickupAvailable === null ? "—" : pickupAvailable}</span>
            </div>
            {pickupAvailable === 0 && <p className="text-sm text-destructive">{t("admin.pickupNoStock")}</p>}

            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.pickupQuantity")}
              <input
                className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                type="number"
                min={1}
                max={pickupAvailable ?? undefined}
                step={1}
                inputMode="numeric"
                value={pickupQuantity}
                onChange={(event) => setPickupQuantity(event.target.value)}
                disabled={batchPickingUp}
              />
            </label>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={closeBatchPickup} disabled={batchPickingUp}>{t("admin.cancel")}</Button>
            <Button
              type="button"
              onClick={() => void handleBatchPickup()}
              disabled={batchPickingUp || pickupProductLoading || pickupStockLoading || pickupStockError || !pickupProduct || pickupAvailable === null || !pickupQuantityValid
                || (pickupProduct?.spec_enabled === true && !pickupSpecId)
                || (pickupAvailable !== null && pickupQuantityNumber > pickupAvailable)}
            >
              {batchPickingUp ? t("admin.pickingUp") : t("admin.confirmPickup")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={pickupResult !== null} onOpenChange={(open) => { if (!open) setPickupResult(null) }}>
        <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t("admin.pickupResult")}</DialogTitle>
            <DialogDescription>
              {pickupResult ? `${t("admin.pickedUpCount").replace("{count}", String(pickupResult.count))} · ${t("admin.orderNo")} ${pickupResult.order_no}` : ""}
            </DialogDescription>
          </DialogHeader>
          {pickupResult && (
            <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto py-2">
              {pickupResult.cards.map((card, index) => (
                <div key={card.id} className="rounded-card border border-border-subtle bg-surface-muted/40 p-3">
                  <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-semibold text-foreground">#{index + 1}</span>
                  </div>
                  <CardContentParts parts={card.content_parts} onCopy={copyToClipboard} />
                </div>
              ))}
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={copyAllPickedUpCards} disabled={!pickupResult || pickupResult.cards.length === 0}>
              <Copy className="h-4 w-4" aria-hidden="true" />{t("admin.copyAllContent")}
            </Button>
            <Button type="button" onClick={() => setPickupResult(null)}>{t("admin.cancel")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Sheet open={detailItem !== null} onOpenChange={closeDetail}>
        <SheetContent className="flex w-full flex-col p-0 sm:max-w-5xl">
          <SheetHeader className="border-b border-border-subtle px-6 py-5 pr-14">
            <SheetTitle>{t("admin.cardDetail")}</SheetTitle>
            <SheetDescription>{detailItem ? `${detailItem.product_title} · ${detailItem.spec_name || t("admin.defaultSpec")}` : ""}</SheetDescription>
          </SheetHeader>
          <div className="min-h-0 flex-1 overflow-y-auto p-6">
            <DataTableShell footer={<AdminPagination page={detailPage} total={detailTotal} pageSize={PAGE_SIZE} onPageChange={handleDetailPageChange} />}>
              <table className="w-full min-w-[1000px] text-sm" onCopy={(event) => { const selected = window.getSelection()?.toString(); if (selected) { event.clipboardData.setData("text/plain", stripInvisible(selected)); event.preventDefault() } }}>
                <thead><tr className="border-b border-border-subtle bg-surface-muted">
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardContentColumnLabel")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardStatus")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardCreatedAt")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.cardSoldAt")}</th>
                  <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                </tr></thead>
                <tbody>
                  {detailLoading && <TableLoadingState colSpan={6} />}
                  {!detailLoading && detailError && <TableErrorState colSpan={6} onRetry={() => detailItem && void fetchDetailKeys(detailItem, detailPage)} />}
                  {!detailLoading && !detailError && detailKeys.length === 0 && <TableEmptyState colSpan={6}>{t("admin.noCardData")}</TableEmptyState>}
                  {!detailLoading && !detailError && detailKeys.map((key) => (
                    <tr key={key.id} className="border-b border-border-subtle last:border-0">
                      <td className="px-4 py-3 align-top"><CardContentParts parts={key.content_parts} onCopy={copyToClipboard} /></td>
                      <td className="px-4 py-3 align-top"><StatusBadge label={cardStatusLabel(key.status)} tone={cardStatusTone[key.status]} /></td>
                      <td className="whitespace-nowrap px-4 py-3 align-top text-xs text-muted-foreground">{formatDateTime(key.created_at, locale)}</td>
                      <td className="px-4 py-3 align-top font-mono text-xs text-muted-foreground">{key.order_no ?? t("admin.systemNoData")}</td>
                      <td className="whitespace-nowrap px-4 py-3 align-top text-xs text-muted-foreground">{key.sold_at ? formatDateTime(key.sold_at, locale) : t("admin.systemNoData")}</td>
                      <td className="px-4 py-3 text-right align-top"><div className="flex justify-end gap-1">
                        {key.status === "AVAILABLE" && <Button type="button" variant="ghost" size="icon" title={t("admin.pickupCard")} aria-label={t("admin.pickupCard")} disabled={singlePickingUp} onClick={() => setSinglePickupTarget(key)}><Download className="h-4 w-4" aria-hidden="true" /></Button>}
                        {(key.status === "AVAILABLE" || key.status === "LOCKED") && <Button type="button" variant="ghost" size="icon" title={t("admin.invalidateCard")} aria-label={t("admin.invalidateCard")} onClick={() => setSingleInvalidateTarget(key)}><Ban className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>}
                      </div></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </DataTableShell>
          </div>
        </SheetContent>
      </Sheet>

      <ConfirmAlert
        open={singlePickupTarget !== null}
        onOpenChange={(open) => { if (!open && !singlePickingUp) setSinglePickupTarget(null) }}
        title={t("admin.pickupConfirmTitle")}
        description={t("admin.pickupConfirmDescription")}
        confirmLabel={singlePickingUp ? t("admin.pickingUp") : t("admin.confirmPickup")}
        cancelLabel={t("admin.cancel")}
        loading={singlePickingUp}
        onConfirm={() => void handleSinglePickup()}
      />

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
