"use client"

import { ProductEditorPage } from "@/components/admin/products/product-editor-page"
import { WorkspaceSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { useLocale } from "@/lib/context"
import { useState } from "react"

export function ProductWorkspaceRoute({ productId }: { productId?: string }) {
  const { t } = useLocale()
  const [dirty, setDirty] = useState(false)
  const close = useRouteOverlayClose(dirty)
  const title = productId ? t("admin.editProduct") : t("admin.addProduct")

  return (
    <WorkspaceSheet
      title={title}
      description={t("admin.productEditorHint")}
      dirty={dirty}
      onClose={close}
      contentClassName="bg-surface"
    >
      <ProductEditorPage
        productId={productId}
        presentation="workspace"
        onDirtyChange={setDirty}
        onSaved={() => {
          setDirty(false)
          close()
        }}
        onCancel={close}
      />
    </WorkspaceSheet>
  )
}
