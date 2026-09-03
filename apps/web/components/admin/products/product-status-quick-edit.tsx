"use client"

import { useRef, useState } from "react"

import { ConfirmAlert, StatusBadge } from "@/components/admin/shared"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import type { AdminProductDetail } from "@/types"
import { QuickEditContent, QuickEditTrigger } from "./quick-edit"

export function ProductStatusQuickEdit({
  product,
  saving,
  onSave,
}: {
  product: AdminProductDetail
  saving: boolean
  onSave: (enabled: boolean) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [confirmUnlist, setConfirmUnlist] = useState(false)
  const saveInFlightRef = useRef(false)
  const enabled = product.is_enabled !== false

  const statusLabel = (isEnabled: boolean) => isEnabled ? t("admin.listed") : t("admin.unlisted")

  const chooseStatus = async (nextEnabled: boolean) => {
    if (saving || saveInFlightRef.current) return
    if (nextEnabled === enabled) {
      setOpen(false)
      return
    }
    if (!nextEnabled) {
      setOpen(false)
      setConfirmUnlist(true)
      return
    }
    saveInFlightRef.current = true
    try {
      if (await onSave(true)) setOpen(false)
    } finally {
      saveInFlightRef.current = false
    }
  }

  const confirmStatus = async () => {
    if (saving || saveInFlightRef.current) return
    saveInFlightRef.current = true
    try {
      if (await onSave(false)) setConfirmUnlist(false)
    } finally {
      saveInFlightRef.current = false
    }
  }

  return (
    <>
      <Popover open={open} onOpenChange={(nextOpen) => !saving && setOpen(nextOpen)}>
        <PopoverTrigger asChild>
          <QuickEditTrigger
            disabled={saving}
            aria-label={`${t("admin.editProductStatus")}: ${product.title}`}
            title={t("admin.editProductStatus")}
          >
            <StatusBadge label={statusLabel(enabled)} tone={enabled ? "success" : "neutral"} />
          </QuickEditTrigger>
        </PopoverTrigger>
        <QuickEditContent title={t("admin.editProductStatus")} description={t("admin.chooseProductStatus")}>
          <div className="grid gap-1.5">
            {([true, false] as const).map((nextEnabled) => (
              <button
                key={String(nextEnabled)}
                type="button"
                className="flex items-center justify-between rounded-control px-3 py-2 text-left text-sm transition-colors hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={() => void chooseStatus(nextEnabled)}
                disabled={saving}
                aria-current={nextEnabled === enabled ? "true" : undefined}
              >
                <span>{statusLabel(nextEnabled)}</span>
                {nextEnabled === enabled && <span className="text-xs text-muted-foreground">{t("admin.currentStatus")}</span>}
              </button>
            ))}
          </div>
        </QuickEditContent>
      </Popover>

      <ConfirmAlert
        open={confirmUnlist}
        onOpenChange={(nextOpen) => { if (!saving) setConfirmUnlist(nextOpen) }}
        title={t("admin.unlistProductTitle").replace("{title}", product.title)}
        description={t("admin.unlistProductDescription")}
        confirmLabel={t("admin.unlisted")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={saving}
        onConfirm={() => void confirmStatus()}
      />
    </>
  )
}
