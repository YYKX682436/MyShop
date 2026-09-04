"use client"

import { useEffect, useRef, useState } from "react"

import { StatusBadge } from "@/components/admin/shared"
import { QuickEditActions, QuickEditContent, QuickEditTrigger } from "@/components/admin/shared/quick-edit"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import type { UserLevel } from "@/types"

export function UserLevelQuickEdit({
  username,
  value,
  label,
  levels,
  saving = false,
  onSave,
}: {
  username: string
  value: string | null
  label: string
  levels: UserLevel[]
  saving?: boolean
  onSave: (userLevelId: string) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState(value || "")
  const [savingLocal, setSavingLocal] = useState(false)
  const selectRef = useRef<HTMLSelectElement>(null)
  const saveInFlightRef = useRef(false)
  const activeLevels = levels.filter((level) => level.enabled)
  const busy = saving || savingLocal

  useEffect(() => {
    if (!open) return
    setSelected(value || "")
    window.requestAnimationFrame(() => selectRef.current?.focus())
  }, [open, value])

  const focusSelect = (event: Event) => {
    event.preventDefault()
    window.requestAnimationFrame(() => selectRef.current?.focus())
  }

  const handleSave = async () => {
    if (busy || saveInFlightRef.current || !selected || selected === value) {
      if (selected === value) setOpen(false)
      return
    }
    if (!activeLevels.some((level) => level.id === selected)) {
      setSelected(value || "")
      return
    }
    saveInFlightRef.current = true
    setSavingLocal(true)
    try {
      if (await onSave(selected)) setOpen(false)
      else setSelected(value || "")
    } finally {
      saveInFlightRef.current = false
      setSavingLocal(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={(nextOpen) => !busy && setOpen(nextOpen)}>
      <PopoverTrigger asChild>
        <QuickEditTrigger disabled={busy} aria-label={`${t("admin.editUserLevel")}: ${username}`} title={label}>
          <StatusBadge label={<span className="max-w-[9rem] truncate">{label}</span>} tone="info" />
        </QuickEditTrigger>
      </PopoverTrigger>
      <QuickEditContent
        title={t("admin.editUserLevel")}
        description={t("admin.userLevelImmediateHint")}
        onOpenAutoFocus={focusSelect}
        footer={<QuickEditActions onCancel={() => setOpen(false)} onSave={() => void handleSave()} saving={busy} />}
      >
        {activeLevels.length > 0 ? (
          <select
            ref={selectRef}
            className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/35 disabled:cursor-not-allowed disabled:opacity-50"
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
            disabled={busy}
            aria-label={t("admin.userLevel")}
          >
            <option value="">{t("admin.choose")}</option>
            {activeLevels.map((level) => <option key={level.id} value={level.id}>{level.name}</option>)}
          </select>
        ) : <p className="text-sm text-muted-foreground">{t("admin.noActiveUserLevels")}</p>}
      </QuickEditContent>
    </Popover>
  )
}
