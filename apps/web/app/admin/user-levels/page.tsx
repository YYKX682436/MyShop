"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { ArrowRightLeft, Edit3, Plus, Star, Trash2 } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  ConfirmAlert,
  DataTableShell,
  PageHeader,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { adminUserLevelApi, requestOrThrow, type UserLevelUpsertPayload } from "@/services/api"
import type { PricingType, UserLevel } from "@/types"

type LevelForm = {
  name: string
  pricing_type: PricingType
  markup_rate: string
  is_default: boolean
  enabled: boolean
  sort_order: string
  remark: string
}

function emptyForm(): LevelForm {
  return {
    name: "",
    pricing_type: "RETAIL",
    markup_rate: "",
    is_default: false,
    enabled: true,
    sort_order: "0",
    remark: "",
  }
}

function formFromLevel(level: UserLevel): LevelForm {
  return {
    name: level.name,
    pricing_type: level.pricing_type,
    markup_rate: level.markup_rate == null ? "" : String(level.markup_rate),
    is_default: level.is_default,
    enabled: level.enabled,
    sort_order: String(level.sort_order),
    remark: level.remark || "",
  }
}

function toPayload(form: LevelForm): UserLevelUpsertPayload {
  const markup = form.markup_rate.trim()
  return {
    name: form.name.trim(),
    pricing_type: form.pricing_type,
    markup_rate: form.pricing_type === "COST_MARKUP" && markup ? Number(markup) : null,
    is_default: form.is_default,
    enabled: form.is_default ? true : form.enabled,
    sort_order: Number.parseInt(form.sort_order, 10) || 0,
    remark: form.remark.trim() || null,
  }
}

export default function AdminUserLevelsPage() {
  const { t } = useLocale()
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<UserLevel | null>(null)
  const [form, setForm] = useState<LevelForm>(emptyForm)
  const [saving, setSaving] = useState(false)
  const [actionId, setActionId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<UserLevel | null>(null)
  const [migrateSource, setMigrateSource] = useState<UserLevel | null>(null)
  const [migrateTargetId, setMigrateTargetId] = useState("")

  const loadLevels = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      setLevels(await requestOrThrow(() => adminUserLevelApi.getList()))
    } catch {
      setLevels([])
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadLevels() }, [loadLevels])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm())
    setDialogOpen(true)
  }

  const openEdit = (level: UserLevel) => {
    setEditing(level)
    setForm(formFromLevel(level))
    setDialogOpen(true)
  }

  const handleSave = async () => {
    if (!form.name.trim()) {
      toast.error(t("admin.levelNameRequired"))
      return
    }
    if (form.pricing_type === "COST_MARKUP"
      && (!/^\d+(\.\d{1,4})?$/.test(form.markup_rate.trim()) || Number(form.markup_rate) < 0)) {
      toast.error(t("admin.levelMarkupInvalid"))
      return
    }
    if (form.pricing_type === "RETAIL" && form.markup_rate.trim()) {
      toast.error(t("admin.levelMarkupInvalid"))
      return
    }
    setSaving(true)
    try {
      const payload = toPayload(form)
      if (editing) await requestOrThrow(() => adminUserLevelApi.update(editing.id, payload))
      else await requestOrThrow(() => adminUserLevelApi.create(payload))
      setDialogOpen(false)
      toast.success(t("admin.levelSaved"))
      await loadLevels()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.levelSaveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (level: UserLevel) => {
    if (level.is_default && level.enabled) {
      toast.error(t("admin.levelLifecycleHint"))
      return
    }
    setActionId(level.id)
    try {
      await requestOrThrow(() => adminUserLevelApi.setStatus(level.id, !level.enabled))
      await loadLevels()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setActionId(null)
    }
  }

  const setDefault = async (level: UserLevel) => {
    if (level.is_default) return
    setActionId(level.id)
    try {
      await requestOrThrow(() => adminUserLevelApi.setDefault(level.id))
      await loadLevels()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setActionId(null)
    }
  }

  const handleDelete = async () => {
    if (!deleteTarget) return
    setActionId(deleteTarget.id)
    try {
      await requestOrThrow(() => adminUserLevelApi.delete(deleteTarget.id))
      setDeleteTarget(null)
      toast.success(t("admin.levelDeleted"))
      await loadLevels()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setActionId(null)
    }
  }

  const openMigrate = (level: UserLevel) => {
    setMigrateSource(level)
    setMigrateTargetId(levels.find((candidate) => candidate.id !== level.id && candidate.enabled)?.id || "")
  }

  const handleMigrate = async () => {
    if (!migrateSource || !migrateTargetId) {
      toast.error(t("admin.migrateTargetRequired"))
      return
    }
    setActionId(migrateSource.id)
    try {
      const count = await requestOrThrow(() => adminUserLevelApi.migrate(migrateSource.id, migrateTargetId))
      setMigrateSource(null)
      toast.success(t("admin.migrateSuccess").replace("{count}", String(count)))
      await loadLevels()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setActionId(null)
    }
  }

  const migrationTargets = useMemo(
    () => levels.filter((level) => level.id !== migrateSource?.id && level.enabled),
    [levels, migrateSource?.id],
  )

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.userLevels")}
        description={t("admin.userLevelsDesc")}
        actions={<Button type="button" onClick={openCreate}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addUserLevel")}</Button>}
      />

      <p className="rounded-lg border border-amber-500/30 bg-amber-500/5 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
        {t("admin.levelLifecycleHint")}
      </p>

      <DataTableShell>
        <table className="w-full min-w-[1100px] text-sm">
          <thead>
            <tr className="border-b border-border bg-muted/30">
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.levelName")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.pricingType")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.markupRate")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.levelUserCount")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.levelStatus")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.sortOrderLabel")}</th>
              <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.levelRemark")}</th>
              <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {loading && <TableLoadingState colSpan={8} />}
            {!loading && error && <TableErrorState colSpan={8} onRetry={() => void loadLevels()} />}
            {!loading && !error && levels.length === 0 && <TableEmptyState colSpan={8}>{t("common.noData")}</TableEmptyState>}
            {!loading && !error && levels.map((level) => (
              <tr key={level.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2 font-medium">
                    {level.is_default && <Star className="h-4 w-4 fill-amber-400 text-amber-500" aria-label={t("admin.defaultLabel")} />}
                    <span>{level.name}</span>
                    {level.is_default && <StatusBadge label={t("admin.defaultLabel")} tone="warning" />}
                  </div>
                </td>
                <td className="px-4 py-3">{level.pricing_type === "COST_MARKUP" ? t("admin.costMarkup") : t("admin.retailPricing")}</td>
                <td className="px-4 py-3">{level.markup_rate == null ? "—" : `${level.markup_rate}%`}</td>
                <td className="px-4 py-3">{level.user_count}</td>
                <td className="px-4 py-3"><StatusBadge label={level.enabled ? t("admin.levelEnabled") : t("admin.levelDisabled")} tone={level.enabled ? "success" : "neutral"} /></td>
                <td className="px-4 py-3">{level.sort_order}</td>
                <td className="max-w-56 truncate px-4 py-3 text-muted-foreground" title={level.remark || undefined}>{level.remark || "—"}</td>
                <td className="px-4 py-3">
                  <div className="flex justify-end gap-1">
                    <Button type="button" variant="ghost" size="sm" onClick={() => openEdit(level)} disabled={actionId === level.id}><Edit3 className="mr-1 h-3.5 w-3.5" aria-hidden="true" />{t("admin.edit")}</Button>
                    <Button type="button" variant="ghost" size="sm" onClick={() => void toggleStatus(level)} disabled={actionId === level.id || level.is_default}><span className={level.enabled ? "text-destructive" : "text-emerald-600"}>{level.enabled ? t("admin.disableLevel") : t("admin.enableLevel")}</span></Button>
                    {!level.is_default && <Button type="button" variant="ghost" size="sm" onClick={() => void setDefault(level)} disabled={actionId === level.id || !level.enabled}><Star className="mr-1 h-3.5 w-3.5" aria-hidden="true" />{t("admin.setDefault")}</Button>}
                    {level.user_count > 0 && <Button type="button" variant="ghost" size="sm" onClick={() => openMigrate(level)} disabled={actionId === level.id}><ArrowRightLeft className="mr-1 h-3.5 w-3.5" aria-hidden="true" />{t("admin.migrateUsers")}</Button>}
                    {!level.is_default && level.user_count === 0 && <Button type="button" variant="ghost" size="icon" onClick={() => setDeleteTarget(level)} disabled={actionId === level.id} title={t("admin.deleteLevel")} aria-label={t("admin.deleteLevel")}><Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </DataTableShell>

      <Dialog open={dialogOpen} onOpenChange={(open) => { if (!saving) setDialogOpen(open) }}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? t("admin.editUserLevel") : t("admin.addUserLevel")}</DialogTitle>
            <DialogDescription>{t("admin.levelLifecycleHint")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.levelName")}<Input value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} maxLength={100} autoFocus /></label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.pricingType")}
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={form.pricing_type} onChange={(event) => setForm((current) => ({ ...current, pricing_type: event.target.value as PricingType, markup_rate: event.target.value === "RETAIL" ? "" : current.markup_rate }))}>
                <option value="RETAIL">{t("admin.retailPricing")}</option>
                <option value="COST_MARKUP">{t("admin.costMarkup")}</option>
              </select>
            </label>
            {form.pricing_type === "COST_MARKUP" && <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.markupRate")}<Input type="number" min="0" step="0.0001" value={form.markup_rate} onChange={(event) => setForm((current) => ({ ...current, markup_rate: event.target.value }))} placeholder="15" /><span className="text-xs font-normal text-muted-foreground">{t("admin.markupRateHint")}</span></label>}
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.sortOrderLabel")}<Input type="number" value={form.sort_order} onChange={(event) => setForm((current) => ({ ...current, sort_order: event.target.value }))} /></label>
              <label className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2 text-sm font-medium">{t("admin.enabledLevel")}<Switch checked={form.is_default || form.enabled} onCheckedChange={(checked) => setForm((current) => ({ ...current, enabled: checked }))} disabled={form.is_default} /></label>
            </div>
            <label className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2 text-sm font-medium">{t("admin.defaultLevel")}<Switch checked={form.is_default} onCheckedChange={(checked) => setForm((current) => ({ ...current, is_default: checked, enabled: checked || current.enabled }))} /></label>
            <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.levelRemark")}<Textarea value={form.remark} onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))} placeholder={t("admin.levelRemarkPlaceholder")} maxLength={500} rows={3} /></label>
          </div>
          <DialogFooter><Button type="button" variant="outline" onClick={() => setDialogOpen(false)} disabled={saving}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleSave()} disabled={saving}>{saving ? t("admin.saving") : t("admin.save")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={migrateSource !== null} onOpenChange={(open) => { if (!actionId) setMigrateSource(open ? migrateSource : null) }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{t("admin.migrateUsersTitle")}</DialogTitle><DialogDescription>{t("admin.migrateUsersDescription")}</DialogDescription></DialogHeader>
          <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.migrateTarget")}
            <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={migrateTargetId} onChange={(event) => setMigrateTargetId(event.target.value)}>
              <option value="">{t("admin.migrateTargetRequired")}</option>
              {migrationTargets.map((level) => <option key={level.id} value={level.id}>{level.name}</option>)}
            </select>
          </label>
          <DialogFooter><Button type="button" variant="outline" onClick={() => setMigrateSource(null)} disabled={actionId !== null}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void handleMigrate()} disabled={actionId !== null || !migrateTargetId}>{actionId === migrateSource?.id ? t("admin.processing") : t("admin.migrateUsers")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert
        open={deleteTarget !== null}
        onOpenChange={(open) => { if (!open && actionId === null) setDeleteTarget(null) }}
        title={t("admin.deleteLevel")}
        description={deleteTarget ? `${deleteTarget.name} · ${t("admin.deleteLevelMessage")}` : undefined}
        confirmLabel={t("admin.delete")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={actionId === deleteTarget?.id}
        onConfirm={() => void handleDelete()}
      />
    </AdminPage>
  )
}
