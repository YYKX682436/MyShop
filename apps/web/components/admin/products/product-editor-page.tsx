"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { ArrowLeft } from "lucide-react"
import { useRouter } from "next/navigation"

import { ProductForm } from "@/components/admin/products/product-form"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { adminCategoryApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail, Category } from "@/types"

function ProductEditorSkeleton({ loadingLabel }: { loadingLabel: string }) {
  return (
    <div className="space-y-5" role="status" aria-label={loadingLabel}>
      <div className="h-11 rounded-control border border-border-subtle bg-surface-muted" />
      <div className="content-surface p-5 sm:p-6">
        <div className="mb-5 h-5 w-40 rounded-control bg-surface-muted" />
        <div className="grid gap-5 lg:grid-cols-2">
          <div className="h-11 rounded-control bg-surface-muted lg:col-span-2" />
          <div className="h-11 rounded-control bg-surface-muted lg:col-span-2" />
          <div className="h-11 rounded-control bg-surface-muted" />
          <div className="h-11 rounded-control bg-surface-muted" />
          <div className="h-11 rounded-control bg-surface-muted" />
          <div className="h-11 rounded-control bg-surface-muted" />
        </div>
      </div>
    </div>
  )
}

interface ProductEditorPageProps {
  productId?: string
  presentation?: "full" | "workspace"
  onSaved?: () => void | Promise<void>
  onCancel?: () => void
  onDirtyChange?: (dirty: boolean) => void
}

export function ProductEditorPage({ productId, presentation = "full", onSaved, onCancel, onDirtyChange }: ProductEditorPageProps) {
  const { t } = useLocale()
  const router = useRouter()
  const isWorkspace = presentation === "workspace"
  const [categories, setCategories] = useState<Category[]>([])
  const [categoryStatus, setCategoryStatus] = useState<"loading" | "ready" | "error">("loading")
  const [product, setProduct] = useState<AdminProductDetail | null>(null)
  const [productLoading, setProductLoading] = useState(Boolean(productId))
  const [productError, setProductError] = useState(false)
  const categoriesRequestIdRef = useRef(0)
  const productRequestIdRef = useRef(0)

  const loadCategories = useCallback(async () => {
    const requestId = ++categoriesRequestIdRef.current
    setCategoryStatus("loading")
    try {
      const data = await requestOrThrow(() => adminCategoryApi.getList())
      if (categoriesRequestIdRef.current !== requestId) return
      setCategories(data)
      setCategoryStatus("ready")
    } catch {
      if (categoriesRequestIdRef.current !== requestId) return
      setCategories([])
      setCategoryStatus("error")
    }
  }, [])

  const loadProduct = useCallback(async () => {
    const requestId = ++productRequestIdRef.current
    if (!productId) {
      setProduct(null)
      setProductLoading(false)
      setProductError(false)
      return
    }

    setProductLoading(true)
    setProductError(false)
    try {
      const data = await requestOrThrow(() => adminProductApi.getDetail(productId))
      if (productRequestIdRef.current !== requestId) return
      setProduct(data)
    } catch {
      if (productRequestIdRef.current !== requestId) return
      setProduct(null)
      setProductError(true)
    } finally {
      if (productRequestIdRef.current === requestId) setProductLoading(false)
    }
  }, [productId])

  useEffect(() => {
    void loadCategories()
    void loadProduct()
    return () => {
      categoriesRequestIdRef.current += 1
      productRequestIdRef.current += 1
    }
  }, [loadCategories, loadProduct])

  const refreshProduct = useCallback(async () => {
    if (!productId) return
    const productData = await requestOrThrow(() => adminProductApi.getDetail(productId))
    setProduct(productData)
  }, [productId])

  const handleSaved = onSaved || (() => router.push("/admin/products"))
  const handleCancel = onCancel || (() => router.push("/admin/products"))

  const currentProduct = productId && product?.id === productId ? product : null
  const loadingProduct = Boolean(productId) && (productLoading || !currentProduct)

  if (isWorkspace && loadingProduct && !productError) {
    return <ProductEditorSkeleton loadingLabel={t("common.loading")} />
  }

  if (isWorkspace && productError) {
    return (
      <div className="flex min-h-64 flex-col items-center justify-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center" role="alert">
        <p className="text-sm text-destructive">{t("admin.loadProductFailed")}</p>
        <Button type="button" variant="outline" onClick={() => void loadProduct()}>{t("common.retry")}</Button>
      </div>
    )
  }

  if (loadingProduct && !productError) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} />
        <ProductEditorSkeleton loadingLabel={t("common.loading")} />
      </AdminPage>
    )
  }

  if (productError) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} />
        <div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center" role="alert">
          <p className="text-sm text-destructive">{t("admin.loadProductFailed")}</p>
          <Button type="button" variant="outline" onClick={() => void loadProduct()}>{t("common.retry")}</Button>
        </div>
      </AdminPage>
    )
  }

  if (isWorkspace) {
    return (
      <ProductForm
        product={currentProduct}
        categories={categories}
        categoryStatus={categoryStatus}
        onRetryCategories={() => { void loadCategories() }}
        onSaved={handleSaved}
        onCancel={handleCancel}
        onDirtyChange={onDirtyChange}
        onProductRefresh={refreshProduct}
      />
    )
  }

  return (
    <AdminPage>
      <PageHeader
        title={productId ? t("admin.editProduct") : t("admin.addProduct")}
        actions={
          <Button type="button" variant="outline" onClick={() => router.push("/admin/products")}>
            <ArrowLeft className="mr-2 h-4 w-4" aria-hidden="true" />
            {t("admin.backToProducts")}
          </Button>
        }
      />
      <ProductForm
        product={currentProduct}
        categories={categories}
        categoryStatus={categoryStatus}
        onRetryCategories={() => { void loadCategories() }}
        onSaved={handleSaved}
        onCancel={handleCancel}
        onDirtyChange={onDirtyChange}
        onProductRefresh={refreshProduct}
      />
    </AdminPage>
  )
}
