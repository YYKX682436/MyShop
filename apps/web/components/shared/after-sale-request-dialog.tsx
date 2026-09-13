"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Textarea } from "@/components/ui/textarea"
import { AfterSaleAttachmentPicker } from "@/components/shared/after-sale-attachment-picker"
import { useLocale } from "@/lib/context"
import { getApiErrorMessage, requestOrThrow, userApi } from "@/services/api"
import type { AfterSaleApplyResult } from "@/types"
interface AfterSaleRequestDialogProps {
  open: boolean
  orderId: string
  orderNo: number
  productSummary: string
  onOpenChange: (open: boolean) => void
  onSubmitted?: (result: AfterSaleApplyResult) => void
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

          <AfterSaleAttachmentPicker
            files={files}
            onChange={setFiles}
            inputId="after-sale-attachments"
            resetKey={`${orderId}:${open}`}
            disabled={submitting}
            onError={setFileError}
          />

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
