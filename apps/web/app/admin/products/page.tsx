"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { AlertCircle, Copy, Edit, Plus, Trash2 } from "lucide-react"
import Link from "next/link"
import { toast } from "sonner"

import { AdminPage, AdminPagination, ConfirmAlert, DataTableShell, PageHeader, PageToolbar, TableEmptyState, TableErrorState, TableLoadingState } from "@/components/admin/shared"
import { ProductCategoryQuickEdit } from "@/components/admin/products/product-category-quick-edit"
import { ProductImagePreviewDialog } from "@/components/admin/products/product-image-preview-dialog"
import { ProductPriceQuickEdit } from "@/components/admin/products/product-price-quick-edit"
import { ProductStatusQuickEdit } from "@/components/admin/products/product-status-quick-edit"
import { ProductStockQuickAction } from "@/components/admin/products/product-stock-quick-action"
import { ProductTitleQuickEdit } from "@/components/admin/products/product-title-quick-edit"
import { ProductWeightQuickEdit } from "@/components/admin/products/product-weight-quick-edit"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useLocale } from "@/lib/context"
import { getProductCategoryPathLabel, matchesCategory } from "@/lib/categories"
import { cn, stripInvisible } from "@/lib/utils"
import { adminCategoryApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail, Category } from "@/types"

const PAGE_SIZE = 20

const PRODUCT_SORT_OPTIONS = [
  { value: "weight_desc", sortBy: "sort_weight", sortDirection: "desc", labelKey: "admin.sortWeightDesc" },
  { value: "weight_asc", sortBy: "sort_weight", sortDirection: "asc", labelKey: "admin.sortWeightAsc" },
  { value: "created_desc", sortBy: "created_at", sortDirection: "desc", labelKey: "admin.sortNewest" },
  { value: "no_asc", sortBy: "product_no", sortDirection: "asc", labelKey: "admin.sortProductNo" },
  { value: "retail_asc", sortBy: "retail_price", sortDirection: "asc", labelKey: "admin.sortRetailPrice" },
  { value: "cost_asc", sortBy: "cost_price", sortDirection: "asc", labelKey: "admin.sortCostPrice" },
] as const

type QuickEditField = "title" | "category" | "prices" | "sort_weight" | "status"

export default function AdminProductsPage() {
  const { t } = useLocale()
  const [products, setProducts] = useState<AdminProductDetail[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [categoryError, setCategoryError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [categoryFilter, setCategoryFilter] = useState("")
  const [statusFilter, setStatusFilter] = useState("")
  const [sortOption, setSortOption] = useState("weight_desc")
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [deleteProduct, setDeleteProduct] = useState<AdminProductDetail | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [savingFields, setSavingFields] = useState<Record<string, boolean>>({})
  const savingFieldKeys = useRef(new Set<string>())

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
      const selectedSort = PRODUCT_SORT_OPTIONS.find((option) => option.value === sortOption) || PRODUCT_SORT_OPTIONS[0]
      const data = await requestOrThrow(() => adminProductApi.getList({
        page,
        page_size: PAGE_SIZE,
        category_id: categoryFilter || undefined,
        is_enabled: statusFilter ? (statusFilter === "enabled" ? 1 : 0) : undefined,
        keyword: debouncedSearch || undefined,
        sort_by: selectedSort.sortBy,
        sort_direction: selectedSort.sortDirection,
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
  }, [categoryFilter, debouncedSearch, page, sortOption, statusFilter])

  useEffect(() => { void loadCategories() }, [loadCategories])
  useEffect(() => { void loadProducts() }, [loadProducts])

  const getCategoryName = (product: AdminProductDetail) =>
    getProductCategoryPathLabel(product, categories) || t("admin.systemNoData")
  const isUnlimited = (product: AdminProductDetail) => product.stock_mode === "UNLIMITED"
  const isSaving = (product: AdminProductDetail, field: QuickEditField) => savingFields[`${product.id}:${field}`] === true

  const replaceProduct = useCallback((updated: AdminProductDetail) => {
    const normalizedSearch = debouncedSearch.toLocaleLowerCase()
    const matchesSearch = !normalizedSearch
      || updated.title.toLocaleLowerCase().includes(normalizedSearch)
      || (/^\d+$/.test(debouncedSearch) && String(updated.product_no) === debouncedSearch)
    const matchesFilters = (!categoryFilter || matchesCategory(updated.category_id, categoryFilter, categories))
      && (!statusFilter || (statusFilter === "enabled" ? updated.is_enabled !== false : updated.is_enabled === false))
      && matchesSearch
    const wasVisible = products.some((product) => product.id === updated.id)
    setProducts((current) => matchesFilters
      ? current.map((product) => product.id === updated.id ? updated : product)
      : current.filter((product) => product.id !== updated.id))
    if (wasVisible && !matchesFilters) setTotal((current) => Math.max(0, current - 1))
  }, [categories, categoryFilter, debouncedSearch, products, statusFilter])

  const patchProduct = useCallback((id: string, patch: Partial<AdminProductDetail>) => {
    setProducts((current) => current.map((product) => product.id === id ? { ...product, ...patch } : product))
  }, [])

  const runQuickSave = useCallback(async (
    product: AdminProductDetail,
    field: QuickEditField,
    action: () => Promise<AdminProductDetail | null | void>,
    onSuccess?: () => void | Promise<void>,
  ): Promise<boolean> => {
    const key = `${product.id}:${field}`
    if (savingFieldKeys.current.has(key)) return false
    savingFieldKeys.current.add(key)
    setSavingFields((current) => ({ ...current, [key]: true }))
    try {
      const result = await action()
      if (result && typeof result === "object" && "id" in result) replaceProduct(result as AdminProductDetail)
      await onSuccess?.()
      toast.success(t("admin.quickEditSaved"))
      return true
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
      return false
    } finally {
      savingFieldKeys.current.delete(key)
      setSavingFields((current) => {
        const next = { ...current }
        delete next[key]
        return next
      })
    }
  }, [replaceProduct, t])

  const copyProductNo = (value: number) => {
    const cleanValue = stripInvisible(String(value))
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
    if (navigator.clipboard?.writeText) void navigator.clipboard.writeText(cleanValue).then(() => toast.success(t("order.copied")), fallback)
    else fallback()
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
            className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm focus:outline-none focus:ring-2 focus:ring-ring"
            value={categoryFilter}
            onChange={(event) => { setCategoryFilter(event.target.value); setPage(1) }}
            aria-label={t("admin.category")}
            disabled={categoryError}
          >
            <option value="">{t("admin.allCategoriesFilter")}</option>
            {categories.map((category) => (
              <optgroup key={category.id} label={category.name}>
                <option value={category.id}>{t("admin.categoryOnly").replace("{name}", category.name)}</option>
                {category.children.map((child) => (
                  <option key={child.id} value={child.id}>↳ {child.name}</option>
                ))}
              </optgroup>
            ))}
          </select>
          <select
            className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm focus:outline-none focus:ring-2 focus:ring-ring"
            value={statusFilter}
            onChange={(event) => { setStatusFilter(event.target.value); setPage(1) }}
            aria-label={t("admin.statusLabel")}
          >
            <option value="">{t("admin.allStatus")}</option>
            <option value="enabled">{t("admin.active")}</option>
            <option value="disabled">{t("admin.inactive")}</option>
          </select>
          <select
            className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm focus:outline-none focus:ring-2 focus:ring-ring"
            value={sortOption}
            onChange={(event) => { setSortOption(event.target.value); setPage(1) }}
            aria-label={t("admin.sortBy")}
          >
            {PRODUCT_SORT_OPTIONS.map((option) => <option key={option.value} value={option.value}>{t(option.labelKey)}</option>)}
          </select>
        </div>
        {categoryError && (
          <Button type="button" variant="outline" size="sm" onClick={() => void loadCategories()}>
            {t("common.retry")}
          </Button>
        )}
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <Table className="min-w-[1180px] table-fixed">
          <colgroup>
            <col className="w-[280px]" />
            <col className="w-[160px]" />
            <col className="w-[160px]" />
            <col className="w-[180px]" />
            <col className="w-[90px]" />
            <col className="w-[90px]" />
            <col className="w-[100px]" />
            <col className="w-[110px]" />
          </colgroup>
          <TableHeader>
            <TableRow>
              <TableHead className="whitespace-nowrap">{t("admin.product")}</TableHead>
              <TableHead className="whitespace-nowrap">{t("admin.category")}</TableHead>
              <TableHead className="whitespace-nowrap text-right">{t("admin.costPriceShort")}</TableHead>
              <TableHead className="whitespace-nowrap text-right">{t("admin.retailPriceShort")}</TableHead>
              <TableHead className="whitespace-nowrap text-center">{t("admin.sortWeight")}</TableHead>
              <TableHead className="whitespace-nowrap text-center">{t("admin.stockLabel")}</TableHead>
              <TableHead className="whitespace-nowrap text-center">{t("admin.statusLabel")}</TableHead>
              <TableHead className="whitespace-nowrap px-2 text-right">{t("admin.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && <TableLoadingState colSpan={8} />}
            {!loading && error && <TableErrorState colSpan={8} onRetry={() => void loadProducts()} />}
            {!loading && !error && products.length === 0 && <TableEmptyState colSpan={8}>{t("admin.noProductData")}</TableEmptyState>}
            {!loading && !error && products.map((product) => {
              const unlimited = isUnlimited(product)
              const lowStock = !unlimited && product.stock_available != null && product.stock_available > 0 && product.stock_available <= (product.low_stock_threshold ?? 10)
              const stockClass = cn("font-medium", unlimited ? "text-emerald-600" : product.stock_available === 0 ? "text-destructive" : lowStock ? "text-amber-600" : "text-foreground")
              return (
                <TableRow key={product.id}>
                  <TableCell className="min-w-0">
                    <div className="flex min-w-0 items-center gap-3">
                      <ProductImagePreviewDialog product={product} />
                      <div className="min-w-0 flex-1">
                        <ProductTitleQuickEdit
                          product={product}
                          saving={isSaving(product, "title")}
                          onSave={(title) => runQuickSave(product, "title", () => requestOrThrow(() => adminProductApi.updateTitle(product.id, { title })))}
                        />
                        <div className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
                          <span className="font-mono">{product.product_no}</span>
                          <button type="button" className="rounded p-0.5 hover:bg-surface-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35" title={t("admin.copyProductNo")} aria-label={`${t("admin.copyProductNo")}: ${product.product_no}`} onClick={() => copyProductNo(product.product_no)}>
                            <Copy className="h-3 w-3" aria-hidden="true" />
                          </button>
                        </div>
                        {lowStock && <span className="mt-0.5 inline-flex items-center gap-1 text-xs text-amber-600"><AlertCircle className="h-3 w-3" aria-hidden="true" />{t("admin.lowStock")}</span>}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell className="min-w-0">
                    <ProductCategoryQuickEdit
                      product={product}
                      categories={categories}
                      categoryName={getCategoryName(product)}
                      saving={isSaving(product, "category")}
                      onSave={(categoryId) => runQuickSave(product, "category", () => requestOrThrow(() => adminProductApi.updateCategory(product.id, { category_id: categoryId })))}
                    />
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-right">
                    <ProductPriceQuickEdit
                      product={product}
                      target="cost"
                      saving={isSaving(product, "prices")}
                      onSave={(payload) => runQuickSave(product, "prices", () => requestOrThrow(() => adminProductApi.updatePrices(product.id, payload)))}
                    />
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-right">
                    <ProductPriceQuickEdit
                      product={product}
                      target="retail"
                      saving={isSaving(product, "prices")}
                      onSave={(payload) => runQuickSave(product, "prices", () => requestOrThrow(() => adminProductApi.updatePrices(product.id, payload)))}
                    />
                  </TableCell>
                  <TableCell className="text-center">
                    <div className="flex justify-center">
                      <ProductWeightQuickEdit
                        product={product}
                        saving={isSaving(product, "sort_weight")}
                        onSave={(weight) => runQuickSave(product, "sort_weight", () => requestOrThrow(() => adminProductApi.updateSortWeight(product.id, weight)), () => patchProduct(product.id, { sort_weight: weight }))}
                      />
                    </div>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-center">
                    <div className="flex justify-center">
                      <ProductStockQuickAction product={product} className={stockClass} onImported={loadProducts}>
                        {unlimited ? t("admin.unlimited") : product.stock_available ?? 0}
                      </ProductStockQuickAction>
                    </div>
                  </TableCell>
                  <TableCell className="text-center">
                    <div className="flex justify-center">
                      <ProductStatusQuickEdit
                        product={product}
                        saving={isSaving(product, "status")}
                        onSave={(enabled) => runQuickSave(product, "status", () => requestOrThrow(() => adminProductApi.setStatus(product.id, enabled)))}
                      />
                    </div>
                  </TableCell>
                  <TableCell className="px-2 text-right">
                    <div className="flex justify-end gap-1">
                      <Button asChild variant="ghost" size="icon" title={t("admin.editProduct")} aria-label={t("admin.editProduct")}>
                        <Link href={`/admin/products/${product.id}/edit`}><Edit className="h-4 w-4" aria-hidden="true" /></Link>
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
    </AdminPage>
  )
}
