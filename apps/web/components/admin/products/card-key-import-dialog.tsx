"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"

import { CardContentImportFields } from "@/components/admin/card-content-import-fields"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { useLocale } from "@/lib/context"
import { validateCardContentColumns } from "@/lib/card-content"
import { adminCardKeyApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail } from "@/types"

interface CardKeyImportDialogProps {
  product: AdminProductDetail | null
  initialSpecId?: string | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onImported: () => void | Promise<void>
}

export function CardKeyImportDialog({ product, initialSpecId = null, open, onOpenChange, onImported }: CardKeyImportDialogProps) {
  const { t } = useLocale()
  const [specId, setSpecId] = useState("")
  const [columns, setColumns] = useState([""])
  const [checkDuplicate, setCheckDuplicate] = useState(true)
  const [importing, setImporting] = useState(false)

  useEffect(() => {
    if (!open) {
      setSpecId("")
      setColumns([""])
      setCheckDuplicate(true)
    }
  }, [open])

  useEffect(() => {
    if (open) setSpecId(initialSpecId || "")
  }, [open, product?.id, initialSpecId])

  const handleImport = async () => {
    const validationError = validateCardContentColumns(columns)
    if (!product || validationError) {
      toast.error(validationError || t("admin.cardKeyContentReq"))
      return
    }
    if (product.spec_enabled && !specId) {
      toast.error(t("admin.specRequiredForImport"))
      return
    }

    setImporting(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.import({
        product_id: product.id,
        spec_id: product.spec_enabled ? specId : null,
        content_columns: columns,
        check_duplicate: checkDuplicate,
      }))
      toast.success(
        t("admin.importSummary")
          .replace("{total}", String(result.total_count))
          .replace("{success}", String(result.success_count))
          .replace("{duplicate}", String(result.duplicate_count))
          .replace("{failed}", String(result.fail_count)),
      )
      onOpenChange(false)
      await onImported()
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : t("admin.importFailed"))
    } finally {
      setImporting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => !importing && onOpenChange(nextOpen)}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("admin.importKeys")} · {product?.title || t("admin.product")}</DialogTitle>
          <DialogDescription>{t("admin.cardImportHint")}</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-5 py-2">
          {product?.spec_enabled && (
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectSpec")}
              <select
                className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={specId}
                onChange={(event) => setSpecId(event.target.value)}
                disabled={importing}
              >
                <option value="">{t("admin.chooseSpec")}</option>
                {product.specs.map((spec) => <option key={spec.id} value={spec.id}>{spec.name}</option>)}
              </select>
            </label>
          )}

          <CardContentImportFields columns={columns} onChange={setColumns} disabled={importing} />

          <label className="flex items-start gap-2 text-sm text-foreground">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 rounded border-input accent-primary"
              checked={checkDuplicate}
              onChange={(event) => setCheckDuplicate(event.target.checked)}
              disabled={importing}
            />
            <span>{t("admin.checkDuplicate")}</span>
          </label>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={importing}>{t("admin.cancel")}</Button>
          <Button type="button" onClick={() => void handleImport()} disabled={importing || !product || (product.spec_enabled && !specId)}>
            {importing ? t("admin.importing") : t("admin.import")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
