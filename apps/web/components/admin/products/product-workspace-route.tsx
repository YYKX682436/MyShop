"use client"

import { ProductEditorPage } from "@/components/admin/products/product-editor-page"
import { WorkspaceSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { useLocale } from "@/lib/context"
import { useState } from "react"
import { useRouter } from "next/navigation"
import type { ProductEditorTab } from "@/lib/product-editor"

export function ProductWorkspaceRoute({ productId, initialTab }: { productId?: string; initialTab?: ProductEditorTab }) {
  const { t } = useLocale()
  const router = useRouter()
  const [dirty, setDirty] = useState(false)
  const close = useRouteOverlayClose(dirty)
  const title = productId ? t("admin.editProduct") : t("admin.addProduct")

  return (
    <WorkspaceSheet
      title={title}
      dirty={dirty}
      onClose={close}
      contentClassName="bg-surface"
    >
      <ProductEditorPage
        productId={productId}
        presentation="workspace"
        onDirtyChange={setDirty}
        initialTab={initialTab}
        onSaved={(saved, continueRestock) => {
          setDirty(false)
          if (continueRestock) router.replace(`/admin/products/${saved.id}/edit?tab=inventory`)
          else close({ skipDirtyCheck: true })
        }}
        onCancel={close}
      />
    </WorkspaceSheet>
  )
}
