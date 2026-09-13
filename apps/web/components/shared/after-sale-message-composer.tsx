"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { getApiErrorMessage } from "@/services/api"
import { AfterSaleAttachmentPicker } from "@/components/shared/after-sale-attachment-picker"

interface AfterSaleMessageComposerProps {
  inputId: string
  resetKey?: string | number
  disabled?: boolean
  submitLabel?: string
  placeholder?: string
  successMessage?: string
  onSubmit: (content: string, attachments: File[]) => Promise<void>
}

/** One reply composer shared by the customer and administrator timelines. */
export function AfterSaleMessageComposer({
  inputId,
  resetKey,
  disabled = false,
  submitLabel,
  placeholder,
  successMessage,
  onSubmit,
}: AfterSaleMessageComposerProps) {
  const { t } = useLocale()
  const [content, setContent] = useState("")
  const [files, setFiles] = useState<File[]>([])
  const [fileError, setFileError] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    setContent("")
    setFiles([])
    setFileError(null)
    setSubmitError(null)
  }, [resetKey])

  const normalizedContent = content.trim()
  const contentLength = [...normalizedContent].length
  const contentValid = contentLength >= 1 && contentLength <= 1000

  const submit = async () => {
    if (disabled || submitting) return
    if (!contentValid) {
      setSubmitError(t("order.afterSaleReplyInvalid"))
      return
    }
    if (fileError) {
      setSubmitError(fileError)
      return
    }
    setSubmitting(true)
    setSubmitError(null)
    try {
      await onSubmit(normalizedContent, files)
      setContent("")
      setFiles([])
      setFileError(null)
      if (successMessage) toast.success(successMessage)
    } catch (error) {
      const message = getApiErrorMessage(error, t)
      setSubmitError(message)
      toast.error(message)
    } finally {
      setSubmitting(false)
    }
  }

  const isDisabled = disabled || submitting

  return (
    <div className="mt-4 space-y-3 rounded-card border border-primary/20 bg-primary/5 p-3 sm:p-4">
      <div className="flex items-center justify-between gap-3">
        <h4 className="text-sm font-semibold text-foreground">{t("order.afterSaleReply")}</h4>
        <span className="text-xs tabular-nums text-muted-foreground">{contentLength}/1000</span>
      </div>
      <Textarea
        id={inputId}
        value={content}
        onChange={event => { setContent(event.target.value); setSubmitError(null) }}
        placeholder={placeholder ?? t("order.afterSaleReplyPlaceholder")}
        rows={4}
        disabled={isDisabled}
        aria-label={t("order.afterSaleReply")}
      />
      {!contentValid && content.length > 0 && <p className="text-xs text-destructive">{t("order.afterSaleReplyInvalid")}</p>}
      <AfterSaleAttachmentPicker
        files={files}
        onChange={setFiles}
        inputId={`${inputId}-attachments`}
        resetKey={resetKey}
        disabled={isDisabled}
        onError={setFileError}
      />
      {submitError && <p className="text-sm text-destructive" role="alert">{submitError}</p>}
      <div className="flex justify-end">
        <Button type="button" onClick={() => void submit()} disabled={isDisabled || !contentValid || Boolean(fileError)}>
          {submitting ? t("common.submitting") : (submitLabel ?? t("order.afterSaleSend"))}
        </Button>
      </div>
    </div>
  )
}
