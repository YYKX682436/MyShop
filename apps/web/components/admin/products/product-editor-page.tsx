"use client"

import { useCallback, useEffect, useState } from "react"
import { ArrowLeft } from "lucide-react"
import { useRouter } from "next/navigation"

import { ProductForm } from "@/components/admin/products/product-form"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { adminCategoryApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail, Category } from "@/types"

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
  const [product, setProduct] = useState<AdminProductDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const categoriesRequest = requestOrThrow(() => adminCategoryApi.getList())
      if (productId) {
        const [categoryData, productData] = await Promise.all([
          categoriesRequest,
          requestOrThrow(() => adminProductApi.getDetail(productId)),
        ])
        setCategories(categoryData)
        setProduct(productData)
      } else {
        setCategories(await categoriesRequest)
        setProduct(null)
      }
    } catch {
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [productId])

  useEffect(() => { void load() }, [load])

  const handleSaved = onSaved || (() => router.push("/admin/products"))
  const handleCancel = onCancel || (() => router.push("/admin/products"))

  if (isWorkspace && loading) {
    return <div className="h-full min-h-64 animate-pulse rounded-card border border-border-subtle bg-surface-muted" role="status" aria-label={t("common.loading")} />
  }

  if (isWorkspace && (error || (productId && !product))) {
    return (
      <div className="flex min-h-64 flex-col items-center justify-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center" role="alert">
        <p className="text-sm text-destructive">{productId ? t("admin.loadProductFailed") : t("admin.loadCategoriesFailed")}</p>
        <Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button>
      </div>
    )
  }

  if (loading) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} description={t("admin.productEditorHint")} />
        <div className="h-64 animate-pulse rounded-card border border-border-subtle bg-surface-muted" role="status" aria-label={t("common.loading")} />
      </AdminPage>
    )
  }

  if (error || (productId && !product)) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} description={t("admin.productEditorHint")} />
        <div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center" role="alert">
          <p className="text-sm text-destructive">{productId ? t("admin.loadProductFailed") : t("admin.loadCategoriesFailed")}</p>
          <Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button>
        </div>
      </AdminPage>
    )
  }

  if (isWorkspace) {
    return (
      <ProductForm
        product={product}
        categories={categories}
        onSaved={handleSaved}
        onCancel={handleCancel}
        onDirtyChange={onDirtyChange}
      />
    )
  }

  return (
    <AdminPage>
      <PageHeader
        title={productId ? t("admin.editProduct") : t("admin.addProduct")}
        description={t("admin.productEditorHint")}
        actions={
          <Button type="button" variant="outline" onClick={() => router.push("/admin/products")}>
            <ArrowLeft className="mr-2 h-4 w-4" aria-hidden="true" />
            {t("admin.backToProducts")}
          </Button>
        }
      />
      <ProductForm product={product} categories={categories} onSaved={handleSaved} onCancel={handleCancel} onDirtyChange={onDirtyChange} />
    </AdminPage>
  )
}
