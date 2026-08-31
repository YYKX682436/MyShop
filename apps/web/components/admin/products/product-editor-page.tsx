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

export function ProductEditorPage({ productId }: { productId?: string }) {
  const { t } = useLocale()
  const router = useRouter()
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

  if (loading) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} description={t("admin.productEditorHint")} />
        <div className="h-64 animate-pulse rounded-xl border border-border bg-muted" role="status" aria-label={t("common.loading")} />
      </AdminPage>
    )
  }

  if (error || (productId && !product)) {
    return (
      <AdminPage>
        <PageHeader title={productId ? t("admin.editProduct") : t("admin.addProduct")} description={t("admin.productEditorHint")} />
        <div className="flex flex-col items-center gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-12 text-center" role="alert">
          <p className="text-sm text-destructive">{productId ? t("admin.loadProductFailed") : t("admin.loadCategoriesFailed")}</p>
          <Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button>
        </div>
      </AdminPage>
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
      <ProductForm product={product} categories={categories} onSaved={() => router.push("/admin/products")} onCancel={() => router.push("/admin/products")} />
    </AdminPage>
  )
}
