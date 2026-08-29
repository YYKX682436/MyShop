"use client"

import { useCallback, useEffect, useState } from "react"
import { AlertCircle, Edit, Eye, EyeOff, KeyRound, Plus, Trash2 } from "lucide-react"
import Link from "next/link"
import { toast } from "sonner"

import { CardKeyImportDialog } from "@/components/admin/products/card-key-import-dialog"
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
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useLocale } from "@/lib/context"
import { cn, formatYuan } from "@/lib/utils"
import { adminCategoryApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { Category, ProductDetail } from "@/types"

const PAGE_SIZE = 20

export default function AdminProductsPage() {
  const { t } = useLocale()
  const [products, setProducts] = useState<ProductDetail[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [categoryError, setCategoryError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [categoryFilter, setCategoryFilter] = useState("")
  const [statusFilter, setStatusFilter] = useState("")
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [deleteProduct, setDeleteProduct] = useState<ProductDetail | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [importProduct, setImportProduct] = useState<ProductDetail | null>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const loadCategories = useCallback(async () => {
    setCategoryError(false)
    try {
      setCategories(await requestOrThrow(() => adminCategoryApi.getList()))
    } catch {
      setCategoryError(true)
    }
  }, [])

  const loadProducts = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminProductApi.getList({
        page,
        page_size: PAGE_SIZE,
        category_id: categoryFilter || undefined,
        is_enabled: statusFilter ? (statusFilter === "enabled" ? 1 : 0) : undefined,
        keyword: debouncedSearch || undefined,
      }))
      setProducts(data.list)
      setTotal(data.pagination.total)
    } catch {
      setProducts([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [categoryFilter, debouncedSearch, page, statusFilter])

  useEffect(() => { void loadCategories() }, [loadCategories])
  useEffect(() => { void loadProducts() }, [loadProducts])

  const getCategoryName = (categoryId: string) => categories.find((category) => category.id === categoryId)?.name || "—"
  const isUnlimited = (product: ProductDetail) => product.unlimited === true || product.stock_mode === "UNLIMITED"
  const isCardKeyStock = (product: ProductDetail) => product.stock_mode !== "MANUAL" && product.stock_mode !== "UNLIMITED"

  const handleToggleStatus = async (product: ProductDetail) => {
    try {
      await requestOrThrow(() => adminProductApi.update(product.id, { is_enabled: product.is_enabled === false }))
      toast.success(product.is_enabled === false ? t("admin.productPublished") : t("admin.productUnpublished"))
      await loadProducts()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    }
  }

  const handleDelete = async () => {
    if (!deleteProduct) return
    setDeleting(true)
    try {
      await requestOrThrow(() => adminProductApi.delete(deleteProduct.id))
      toast.success(t("admin.productDeleted"))
      setDeleteProduct(null)
      await loadProducts()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.deleteConfirm"))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.products")}
        description={t("admin.manageProducts")}
        actions={
          <Button asChild>
            <Link href="/admin/products/new"><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addProduct")}</Link>
          </Button>
        }
      />

      <PageToolbar>
        <div className="flex min-w-0 flex-1 flex-col gap-3 sm:flex-row sm:items-center">
          <Input
            className="min-w-0 sm:max-w-sm"
            value={search}
            onChange={(event) => { setSearch(event.target.value); setPage(1) }}
            placeholder={t("admin.searchProduct")}
            aria-label={t("admin.searchProduct")}
          />
          <select
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={categoryFilter}
            onChange={(event) => { setCategoryFilter(event.target.value); setPage(1) }}
            aria-label={t("admin.category")}
            disabled={categoryError}
          >
            <option value="">{t("admin.allCategoriesFilter")}</option>
            {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
          </select>
          <select
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={statusFilter}
            onChange={(event) => { setStatusFilter(event.target.value); setPage(1) }}
            aria-label={t("admin.statusLabel")}
          >
            <option value="">{t("admin.allStatus")}</option>
            <option value="enabled">{t("admin.active")}</option>
            <option value="disabled">{t("admin.inactive")}</option>
          </select>
        </div>
        {categoryError && (
          <Button type="button" variant="outline" size="sm" onClick={() => void loadCategories()}>
            {t("common.retry")}
          </Button>
        )}
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("admin.product")}</TableHead>
              <TableHead>{t("admin.category")}</TableHead>
              <TableHead>{t("admin.basePrice")}</TableHead>
              <TableHead>{t("admin.stockLabel")}</TableHead>
              <TableHead>{t("admin.sold")}</TableHead>
              <TableHead>{t("admin.statusLabel")}</TableHead>
              <TableHead className="text-right">{t("admin.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && <TableLoadingState colSpan={7} />}
            {!loading && error && <TableErrorState colSpan={7} onRetry={() => void loadProducts()} />}
            {!loading && !error && products.length === 0 && <TableEmptyState colSpan={7}>{t("admin.noProductData")}</TableEmptyState>}
            {!loading && !error && products.map((product) => {
              const unlimited = isUnlimited(product)
              const lowStock = !unlimited && product.stock_available != null && product.stock_available > 0 && product.stock_available <= (product.low_stock_threshold ?? 10)
              const cardKeyStock = isCardKeyStock(product)
              return (
                <TableRow key={product.id}>
                  <TableCell className="min-w-[16rem]">
                    <div className="flex items-center gap-3">
                      <Link href={`/product/${product.id}`} className="h-10 w-10 shrink-0 overflow-hidden rounded-lg bg-muted" aria-label={product.title}>
                        {product.cover_url ? <img src={product.cover_url} alt={product.title} className="h-full w-full object-cover" /> : <span className="flex h-full w-full items-center justify-center text-xs text-muted-foreground">{t("admin.systemNoData")}</span>}
                      </Link>
                      <div className="min-w-0">
                        <Link href={`/product/${product.id}`} className="block truncate font-medium text-foreground hover:text-primary hover:underline">{product.title}</Link>
                        {lowStock && <span className="mt-0.5 inline-flex items-center gap-1 text-xs text-amber-600"><AlertCircle className="h-3 w-3" aria-hidden="true" />{t("admin.lowStock")}</span>}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell><StatusBadge label={getCategoryName(product.category_id)} /></TableCell>
                  <TableCell className="whitespace-nowrap font-medium">{formatYuan(product.base_price)}</TableCell>
                  <TableCell>
                    <span className={cn("font-medium", unlimited ? "text-emerald-600" : product.stock_available === 0 ? "text-destructive" : lowStock ? "text-amber-600" : "text-foreground")}>
                      {unlimited ? t("admin.unlimited") : product.stock_available ?? 0}
                    </span>
                  </TableCell>
                  <TableCell>{product.sales_count ?? 0}</TableCell>
                  <TableCell><StatusBadge label={product.is_enabled !== false ? t("admin.active") : t("admin.inactive")} tone={product.is_enabled !== false ? "success" : "neutral"} /></TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button asChild variant="ghost" size="icon" title={t("admin.editProduct")} aria-label={t("admin.editProduct")}>
                        <Link href={`/admin/products/${product.id}/edit`}><Edit className="h-4 w-4" aria-hidden="true" /></Link>
                      </Button>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        title={cardKeyStock ? t("admin.importKeys") : t("admin.stockModeHint")}
                        aria-label={cardKeyStock ? t("admin.importKeys") : t("admin.stockModeHint")}
                        disabled={!cardKeyStock}
                        onClick={() => setImportProduct(product)}
                      >
                        <KeyRound className="h-4 w-4" aria-hidden="true" />
                      </Button>
                      <Button type="button" variant="ghost" size="icon" title={product.is_enabled !== false ? t("admin.inactive") : t("admin.active")} aria-label={product.is_enabled !== false ? t("admin.inactive") : t("admin.active")} onClick={() => void handleToggleStatus(product)}>
                        {product.is_enabled !== false ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
                      </Button>
                      <Button type="button" variant="ghost" size="icon" title={t("admin.delete")} aria-label={t("admin.delete")} onClick={() => setDeleteProduct(product)}>
                        <Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </DataTableShell>

      <ConfirmAlert
        open={deleteProduct !== null}
        onOpenChange={(open) => { if (!open && !deleting) setDeleteProduct(null) }}
        title={t("admin.deleteConfirm")}
        description={deleteProduct ? `${deleteProduct.title} · ${t("admin.deleteProductMsg")}` : undefined}
        confirmLabel={t("admin.delete")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={deleting}
        onConfirm={() => void handleDelete()}
      />

      <CardKeyImportDialog
        product={importProduct}
        open={importProduct !== null}
        onOpenChange={(open) => { if (!open) setImportProduct(null) }}
        onImported={loadProducts}
      />
    </AdminPage>
  )
}
