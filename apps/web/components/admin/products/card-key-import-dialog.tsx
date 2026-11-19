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
import { invalidateAdminData } from "@/lib/admin-data-invalidation"
import { validateCardContentColumns } from "@/lib/card-content"
import { adminCardKeyApi, adminProductApi, requestOrThrow } from "@/services/api"
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
  const [template, setTemplate] = useState<AdminProductDetail | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [validationAttempted, setValidationAttempted] = useState(false)
  const productId = product?.id

  useEffect(() => {
    let cancelled = false
    setTemplate(null)
    setLoadError(false)
    if (!open || !productId) return () => { cancelled = true }

    setSpecId(initialSpecId || "")
    setCheckDuplicate(true)
    setValidationAttempted(false)
    // Do not enable input until the authoritative template arrives. A late
    // response or a parent re-render can therefore never replace typed values.
    void requestOrThrow(() => adminProductApi.getDetail(productId)).then((detail) => {
      if (cancelled) return
      const width = detail.card_content_column_count
      setColumns(Array.from({ length: width && width > 0 ? width : 1 }, () => ""))
      setTemplate(detail)
    }).catch(() => {
      if (!cancelled) setLoadError(true)
    })
    return () => { cancelled = true }
  }, [open, productId, initialSpecId])

  const conflict = template?.card_content_column_count === 0
  const fixedWidth = (template?.card_content_column_count ?? 0) > 0

  const handleImport = async () => {
    const validationError = validateCardContentColumns(columns)
    if (!template || conflict || validationError) {
      if (validationError) setValidationAttempted(true)
      toast.error(validationError || t("admin.cardKeyContentReq"))
      return
    }
    if (template.spec_enabled && !specId) {
      toast.error(t("admin.specRequiredForImport"))
      return
    }

    setImporting(true)
    try {
      const result = await requestOrThrow(() => adminCardKeyApi.import({
        product_id: template.id,
        spec_id: template.spec_enabled ? specId : null,
        content_columns: columns,
        check_duplicate: checkDuplicate,
      }))
      invalidateAdminData("products")
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
          {loadError && <p role="alert" className="text-sm text-destructive">{t("admin.loadProductFailed")}</p>}
          {!template && !loadError && <p role="status" className="text-sm text-muted-foreground">{t("common.loading")}</p>}
          {conflict && <p role="alert" className="text-sm text-destructive">{t("admin.cardFormatConflict").replace("{columns}", template.card_content_column_counts.join("、"))}</p>}
          {template?.spec_enabled && !conflict && (
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              {t("admin.selectSpec")}
              <select
                className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={specId}
                onChange={(event) => setSpecId(event.target.value)}
                disabled={importing}
              >
                <option value="">{t("admin.chooseSpec")}</option>
                {template.specs.map((spec) => <option key={spec.id} value={spec.id}>{spec.name}</option>)}
              </select>
            </label>
          )}

          {template && !conflict && <CardContentImportFields columns={columns} onChange={(next) => { setColumns(next); setValidationAttempted(false) }} disabled={importing} locked={fixedWidth} showValidationError={validationAttempted} />}

          {template && !conflict && <label className="flex items-start gap-2 text-sm text-foreground">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 rounded border-input accent-primary"
              checked={checkDuplicate}
              onChange={(event) => setCheckDuplicate(event.target.checked)}
              disabled={importing}
            />
            <span>{t("admin.checkDuplicate")}</span>
          </label>}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={importing}>{t("admin.cancel")}</Button>
          <Button type="button" onClick={() => void handleImport()} disabled={importing || !template || conflict || (template.spec_enabled && !specId)}>
            {importing ? t("admin.importing") : t("admin.import")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
