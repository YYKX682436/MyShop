"use client"

import { useRef, useState } from "react"

import { ConfirmAlert, StatusBadge } from "@/components/admin/shared"
import { QuickEditContent, QuickEditTrigger } from "@/components/admin/shared/quick-edit"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"

export function UserStatusQuickEdit({
  username,
  isDeleted,
  saving = false,
  onSave,
}: {
  username: string
  isDeleted: 0 | 1
  saving?: boolean
  onSave: (nextIsDeleted: 0 | 1) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [confirmDisableOpen, setConfirmDisableOpen] = useState(false)
  const [savingLocal, setSavingLocal] = useState(false)
  const inFlightRef = useRef(false)
  const busy = saving || savingLocal
  const enabled = isDeleted === 0

  const save = async (nextIsDeleted: 0 | 1) => {
    if (busy || inFlightRef.current) return false
    inFlightRef.current = true
    setSavingLocal(true)
    try {
      return await onSave(nextIsDeleted)
    } finally {
      inFlightRef.current = false
      setSavingLocal(false)
    }
  }

  const confirmDisable = async () => {
    if (await save(1)) setConfirmDisableOpen(false)
  }

  const restore = async () => {
    if (await save(0)) setOpen(false)
  }

  return (
    <>
      <Popover open={open} onOpenChange={(nextOpen) => !busy && setOpen(nextOpen)}>
        <PopoverTrigger asChild>
          <QuickEditTrigger disabled={busy} aria-label={`${t("admin.editUserStatus")}: ${username}`}>
            <StatusBadge label={enabled ? t("admin.normal") : t("admin.banned")} tone={enabled ? "success" : "danger"} />
          </QuickEditTrigger>
        </PopoverTrigger>
        <QuickEditContent title={t("admin.editUserStatus")} description={enabled ? t("admin.userStatusDisableHint") : t("admin.userStatusRestoreHint")}>
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">{t("admin.currentStatus")}: <strong className="text-foreground">{enabled ? t("admin.normal") : t("admin.banned")}</strong></p>
            {enabled ? (
              <button type="button" className="flex min-h-10 w-full items-center rounded-control border border-destructive/30 px-3 text-left text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35" onClick={() => { setOpen(false); setConfirmDisableOpen(true) }} disabled={busy}>
                {t("admin.ban")}
              </button>
            ) : (
              <button type="button" className="flex min-h-10 w-full items-center rounded-control border border-input px-3 text-left text-sm font-medium transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35" onClick={() => void restore()} disabled={busy}>
                {t("admin.unban")}
              </button>
            )}
          </div>
        </QuickEditContent>
      </Popover>
      <ConfirmAlert
        open={confirmDisableOpen}
        onOpenChange={(nextOpen) => !busy && setConfirmDisableOpen(nextOpen)}
        title={t("admin.confirmDisable")}
        description={t("admin.userStatusDescription").replace("{action}", t("admin.ban")).replace("{username}", username)}
        confirmLabel={t("admin.ban")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={busy}
        onConfirm={() => void confirmDisable()}
      />
    </>
  )
}
