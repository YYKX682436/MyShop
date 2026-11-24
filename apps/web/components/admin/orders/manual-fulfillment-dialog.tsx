"use client"

import { useEffect, useState } from "react"
import { Loader2 } from "lucide-react"

import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { MediaPicker } from "@/components/admin/media-library"
import { useRouteOverlayClose } from "@/components/admin/route-overlay"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import type {
  AdminManualFulfillmentItem,
  AdminOrderItemDetail,
} from "@/types"

interface ManualFulfillmentDialogProps {
  open: boolean
  items: AdminOrderItemDetail[]
  loading?: boolean
  uploadImage: (file: File) => Promise<string>
  onOpenChange: (open: boolean) => void
  onConfirm: (items: AdminManualFulfillmentItem[]) => void
}

export function ManualFulfillmentDialog({
  open,
  items,
  loading = false,
  uploadImage,
  onOpenChange,
  onConfirm,
}: ManualFulfillmentDialogProps) {
  const { t } = useLocale()
  const [contentByItem, setContentByItem] = useState<Record<string, string>>({})
  const [uploadingItems, setUploadingItems] = useState<Set<string>>(() => new Set())
  const overLimit = Object.values(contentByItem).some(content => content.length > 20_000)
  useRouteOverlayClose(false, open, uploadingItems.size > 0)
  const itemIds = items.map((item) => item.id).join(",")

  useEffect(() => {
    if (!open) return
    const ids = itemIds ? itemIds.split(",") : []
    setContentByItem(Object.fromEntries(ids.map((id) => [id, ""])))
  }, [itemIds, open])

  const handleConfirm = () => {
    if (loading || uploadingItems.size || overLimit || items.length === 0) return
    onConfirm(items.map((item) => {
      const content = contentByItem[item.id] || ""
      return {
        order_item_id: item.id,
        customer_delivery_content: content.trim() ? content : null,
      }
    }))
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => { if (!loading && !uploadingItems.size) onOpenChange(nextOpen) }}>
      <DialogContent className="max-h-[90vh] max-w-5xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("admin.manualFulfillmentTitle")}</DialogTitle>
          <DialogDescription>{t("admin.manualFulfillmentDescription")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-5">
          <div className="admin-warning-callout rounded-card px-4 py-3 text-sm">
            <p className="font-medium">{t("admin.manualFulfillmentItems")}</p>
            <p className="mt-1 text-xs opacity-80">{items.length}</p>
          </div>

          {items.map((item) => (
            <section key={item.id} className="content-surface-muted space-y-3 rounded-surface p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold text-foreground">{item.product_title}</h3>
                  <p className="mt-1 text-xs text-muted-foreground">
                    #{item.product_no} · {item.spec_name || t("admin.systemNoData")} · ×{item.quantity}
                  </p>
                </div>
                <span className="admin-warning-badge rounded-full px-2.5 py-0.5 text-xs font-medium">
                  {t("admin.fulfillmentWaitingManual")}
                </span>
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor={"manual-delivery-" + item.id}>
                  {t("admin.customerDeliveryContent")}
                </label>
                <p className="text-xs text-muted-foreground">{t("admin.customerDeliveryContentHint")}</p>
                <MarkdownEditor
                    id={"manual-delivery-" + item.id}
                    value={contentByItem[item.id] || ""}
                    onChange={(value) => setContentByItem((current) => ({ ...current, [item.id]: value }))}
                    uploadImage={uploadImage}
                    renderMediaPicker={props => <MediaPicker disabled={props.disabled} onOpen={props.onOpen}
                      onSelect={item => { if (item.url) props.onSelect({ name: item.name, url: item.url }) }} />}
                    onUploadingChange={uploading => setUploadingItems(current => {
                      const next = new Set(current)
                      if (uploading) next.add(item.id); else next.delete(item.id)
                      return next
                    })}
                    maxLength={20_000}
                />
              </div>
            </section>
          ))}
        </div>

        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={loading || uploadingItems.size > 0}>
            {t("common.cancel")}
          </Button>
          <Button type="button" onClick={handleConfirm} disabled={loading || uploadingItems.size > 0 || overLimit || items.length === 0}>
            {loading && <Loader2 className="animate-spin" aria-hidden="true" />}
            {t("admin.manualFulfillmentConfirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
