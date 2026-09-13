"use client"

import { useEffect, useMemo, useState } from "react"
import Image from "next/image"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { getApiErrorMessage, requestOrThrow, userApi } from "@/services/api"
import type { AfterSaleApplyResult } from "@/types"
import { AFTER_SALE_MAX_FILES, validateAfterSaleFile } from "@/lib/after-sale-upload"

interface AfterSaleRequestDialogProps {
  open: boolean
  orderId: string
  orderNo: number
  productSummary: string
  onOpenChange: (open: boolean) => void
  onSubmitted?: (result: AfterSaleApplyResult) => void
}

interface FilePreview {
  file: File
  url: string
}

export function AfterSaleRequestDialog({
  open,
  orderId,
  orderNo,
  productSummary,
  onOpenChange,
  onSubmitted,
}: AfterSaleRequestDialogProps) {
  const { t } = useLocale()
  const [reason, setReason] = useState("")
  const [files, setFiles] = useState<File[]>([])
  const [fileError, setFileError] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const previews = useMemo<FilePreview[]>(
    () => files.map(file => ({ file, url: URL.createObjectURL(file) })),
    [files],
  )

  useEffect(() => () => previews.forEach(preview => URL.revokeObjectURL(preview.url)), [previews])

  useEffect(() => {
    if (!open) return
    setReason("")
    setFiles([])
    setFileError(null)
    setSubmitError(null)
    setSubmitting(false)
  }, [open, orderId])

  const normalizedReason = reason.trim()
  const reasonLength = [...normalizedReason].length
  const reasonValid = reasonLength >= 5 && reasonLength <= 1000

  const handleFiles = async (selected: FileList | null) => {
    if (!selected || selected.length === 0) return
    setFileError(null)
    const next = [...files]
    for (const file of Array.from(selected)) {
      if (next.length >= AFTER_SALE_MAX_FILES) {
        setFileError(t("order.afterSaleTooManyFiles"))
        break
      }
      const validationError = await validateAfterSaleFile(file)
      if (validationError) {
        setFileError(validationError === "SIZE"
          ? t("order.afterSaleFileTooLarge")
          : t("order.afterSaleFileInvalid"))
        continue
      }
      if (!next.some(existing => fileKey(existing) === fileKey(file))) next.push(file)
    }
    setFiles(next)
  }

  const removeFile = (file: File) => {
    setFiles(current => current.filter(candidate => fileKey(candidate) !== fileKey(file)))
    setFileError(null)
  }

  const submit = async () => {
    if (submitting) return
    const normalizedReason = reason.trim()
    const normalizedLength = [...normalizedReason].length
    if (normalizedLength < 5 || normalizedLength > 1000) {
      setSubmitError(t("order.afterSaleReasonInvalid"))
      return
    }
    if (fileError) {
      setSubmitError(fileError)
      return
    }
    setSubmitting(true)
    setSubmitError(null)
    try {
      const result = await requestOrThrow(() => userApi.submitAfterSale(orderId, normalizedReason, files))
      toast.success(t("order.afterSaleSubmitSuccess"))
      onSubmitted?.(result)
      onOpenChange(false)
    } catch (error) {
      const message = getApiErrorMessage(error, t)
      setSubmitError(message)
      toast.error(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={nextOpen => !submitting && onOpenChange(nextOpen)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t("order.applyAfterSales")}</DialogTitle>
          <DialogDescription>{t("order.afterSaleDescription")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="rounded-control border border-border-subtle bg-surface-muted/60 px-3 py-2 text-sm">
            <p className="text-xs text-muted-foreground">{t("order.orderNo")}</p>
            <p className="mt-1 font-mono text-foreground">{orderNo}</p>
            <p className="mt-2 text-xs text-muted-foreground">{t("order.product")}</p>
            <p className="mt-1 break-words font-medium text-foreground">{productSummary}</p>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between gap-3">
              <label htmlFor="after-sale-reason" className="text-sm font-medium text-foreground">{t("order.afterSaleReason")}</label>
              <span className="text-xs tabular-nums text-muted-foreground">{reasonLength}/1000</span>
            </div>
            <Textarea
              id="after-sale-reason"
              value={reason}
              onChange={event => { setReason(event.target.value); setSubmitError(null) }}
              placeholder={t("order.afterSaleReasonPlaceholder")}
              rows={5}
              aria-required="true"
            />
            {!reasonValid && reason.length > 0 && <p className="text-xs text-destructive">{t("order.afterSaleReasonInvalid")}</p>}
          </div>

          <div className="space-y-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <label htmlFor="after-sale-attachments" className="text-sm font-medium text-foreground">{t("order.afterSaleUpload")}</label>
              <span className="text-xs text-muted-foreground">{files.length}/{AFTER_SALE_MAX_FILES}</span>
            </div>
            <input
              id="after-sale-attachments"
              type="file"
              multiple
              accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
              disabled={submitting || files.length >= AFTER_SALE_MAX_FILES}
              onChange={event => { void handleFiles(event.target.files); event.currentTarget.value = "" }}
              className="block w-full rounded-control border border-input bg-surface px-3 py-2 text-sm text-foreground file:mr-3 file:rounded-control file:border-0 file:bg-primary file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-primary-foreground"
            />
            <p className="text-xs text-muted-foreground">{t("order.afterSaleUploadHint")}</p>
            {fileError && <p className="text-xs text-destructive" role="alert">{fileError}</p>}
            {previews.length > 0 && (
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-5" aria-label={t("order.afterSaleUpload")}>
                {previews.map(preview => (
                  <div key={fileKey(preview.file)} className="group relative aspect-square overflow-hidden rounded-control border border-border-subtle bg-surface-muted">
                    <Image src={preview.url} alt={preview.file.name} fill unoptimized sizes="120px" className="object-cover" />
                    <button
                      type="button"
                      onClick={() => removeFile(preview.file)}
                      disabled={submitting}
                      className="absolute inset-x-1 bottom-1 rounded bg-black/70 px-1 py-1 text-[10px] font-medium text-white opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100 disabled:cursor-not-allowed"
                    >
                      {t("common.remove")}
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <p className="rounded-control border border-primary/20 bg-primary/5 px-3 py-2 text-sm text-primary">{t("order.afterSaleProcessingNotice")}</p>
          {submitError && <p className="text-sm text-destructive" role="alert">{submitError}</p>}
        </div>

        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>{t("common.cancel")}</Button>
          <Button type="button" onClick={() => void submit()} disabled={submitting || !reasonValid || Boolean(fileError)}>
            {submitting ? t("common.submitting") : t("order.afterSaleSubmit")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function fileKey(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}
