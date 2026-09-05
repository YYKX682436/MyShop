"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { Edit, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  ConfirmAlert,
  PageHeader,
  SettingsSection,
  StatusBadge,
  TableEmptyState,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { PaymentIcon } from "@/components/shared/payment-icon"
import { useLocale } from "@/lib/context"
import { adminPaymentApi, paymentApi, requestOrThrow } from "@/services/api"
import type { AdminPaymentChannel, PaymentProviderConfigField, PaymentProviderMetadata } from "@/types"

interface ChannelForm {
  channel_name: string
  provider_type: string
  payment_type: string
  payment_hint: string
  badge_text: string
  is_enabled: boolean
  sort_order: string
}

const EMPTY_FORM: ChannelForm = {
  channel_name: "",
  provider_type: "",
  payment_type: "",
  payment_hint: "",
  badge_text: "",
  is_enabled: true,
  sort_order: "0",
}

function defaultChannelName(paymentType: string): string {
  return paymentType.toLowerCase() === "wxpay" ? "微信支付" : "支付宝"
}

function fieldValue(config: Record<string, unknown>, field: PaymentProviderConfigField): unknown {
  return config[field.name] ?? field.default_value ?? (field.type === "switch" ? false : "")
}

function supportedPaymentTypes(provider: PaymentProviderMetadata | undefined): string[] {
  return (provider?.payment_types || []).filter((type) => type === "alipay" || type === "wxpay")
}

export default function AdminPaymentChannelsPage() {
  const { t } = useLocale()
  const [channels, setChannels] = useState<AdminPaymentChannel[]>([])
  const [providers, setProviders] = useState<PaymentProviderMetadata[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState<ChannelForm>(EMPTY_FORM)
  const [config, setConfig] = useState<Record<string, unknown>>({})
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<AdminPaymentChannel | null>(null)
  const [deleting, setDeleting] = useState(false)

  const currentProvider = useMemo(
    () => providers.find((provider) => provider.provider_id === form.provider_type),
    [form.provider_type, providers],
  )

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const [channelData, providerData] = await Promise.all([
        requestOrThrow(() => adminPaymentApi.getList()),
        requestOrThrow(() => paymentApi.getProviders()),
      ])
      setChannels(channelData)
      setProviders(providerData)
    } catch {
      setChannels([])
      setProviders([])
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const resetDialog = () => {
    setEditingId(null)
    setForm(EMPTY_FORM)
    setConfig({})
  }

  const closeDialog = () => {
    if (saving) return
    setDialogOpen(false)
    resetDialog()
  }

  const closeDialogAfterSuccess = () => {
    setDialogOpen(false)
    resetDialog()
  }

  const openCreate = () => {
    const provider = providers[0]
    const paymentType = supportedPaymentTypes(provider)[0] || ""
    setEditingId(null)
    setForm({
      ...EMPTY_FORM,
      provider_type: provider?.provider_id || "",
      payment_type: paymentType,
      channel_name: paymentType ? defaultChannelName(paymentType) : "",
    })
    setConfig({})
    setDialogOpen(true)
  }

  const openEdit = (channel: AdminPaymentChannel) => {
    setEditingId(channel.id)
    setForm({
      channel_name: channel.channel_name,
      provider_type: channel.provider_type,
      payment_type: channel.payment_type,
      payment_hint: channel.payment_hint || "",
      badge_text: channel.badge_text || "",
      is_enabled: channel.is_enabled,
      sort_order: String(channel.sort_order),
    })
    setConfig({ ...(channel.config_data || {}) })
    setDialogOpen(true)
  }

  const selectProvider = (providerId: string) => {
    const provider = providers.find((candidate) => candidate.provider_id === providerId)
    const paymentType = supportedPaymentTypes(provider)[0] || ""
    setForm((current) => ({
      ...current,
      provider_type: providerId,
      payment_type: supportedPaymentTypes(provider).includes(current.payment_type)
        ? current.payment_type : paymentType,
      channel_name: current.channel_name || defaultChannelName(paymentType),
    }))
    setConfig({})
  }

  const selectPaymentType = (paymentType: string) => {
    setForm((current) => ({
      ...current,
      payment_type: paymentType,
      channel_name: !current.channel_name.trim() || current.channel_name.trim() === defaultChannelName(current.payment_type)
        ? defaultChannelName(paymentType) : current.channel_name,
    }))
  }

  const setField = (field: PaymentProviderConfigField, value: string | boolean) => {
    const normalized = field.type === "number" && typeof value === "string" && value !== "" ? Number(value) : value
    setConfig((current) => ({ ...current, [field.name]: normalized }))
  }

  const save = async () => {
    if (!form.payment_type || (providers.length > 1 && !form.provider_type)) {
      toast.error(`${t("admin.paymentTypeRequired")} · ${t("admin.paymentProviderRequired")}`)
      return
    }
    setSaving(true)
    try {
      const data = {
        channel_name: form.channel_name.trim() || undefined,
        provider_type: form.provider_type || undefined,
        payment_type: form.payment_type,
        config_data: config,
        payment_hint: form.payment_hint.trim() || null,
        badge_text: form.badge_text.trim() || null,
        is_enabled: form.is_enabled,
        sort_order: Number(form.sort_order) || 0,
      }
      if (editingId) await requestOrThrow(() => adminPaymentApi.update(editingId, data))
      else await requestOrThrow(() => adminPaymentApi.create(data))
      toast.success(t("admin.paymentSaved"))
      closeDialogAfterSuccess()
      await load()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (channel: AdminPaymentChannel, enabled: boolean) => {
    try {
      await requestOrThrow(() => adminPaymentApi.update(channel.id, { is_enabled: enabled }))
      setChannels((current) => current.map((candidate) => candidate.id === channel.id
        ? { ...candidate, is_enabled: enabled } : candidate))
      toast.success(enabled ? t("admin.channelEnabled") : t("admin.channelDisabled"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    }
  }

  const remove = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await requestOrThrow(() => adminPaymentApi.delete(deleteTarget.id))
      toast.success(t("admin.paymentDeleted"))
      setDeleteTarget(null)
      await load()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setDeleting(false)
    }
  }

  const renderProviderField = (field: PaymentProviderConfigField) => {
    const value = fieldValue(config, field)
    const label = `${field.label}${field.required ? " *" : ""}`
    if (field.type === "switch") {
      return <div key={field.name} className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5 text-sm"><span>{label}</span><Switch checked={Boolean(value)} onCheckedChange={(checked) => setField(field, checked)} aria-label={label} /></div>
    }
    if (field.type === "select") {
      return <label key={field.name} className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{label}</span><select value={String(value)} onChange={(event) => setField(field, event.target.value)} className="h-11 rounded-control border border-input bg-surface px-3 text-foreground">{(field.options || []).map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
    }
    const inputType = field.type === "password" || field.type === "secret" || field.secret ? "password" : field.type === "number" ? "number" : field.type
    return <label key={field.name} className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{label}</span><Input type={inputType} value={String(value ?? "")} placeholder={field.placeholder || undefined} onChange={(event) => setField(field, event.target.value)} /></label>
  }

  return (
    <AdminPage>
      <PageHeader title={t("admin.payment")} description={t("admin.paymentDesc")} actions={<Button type="button" onClick={openCreate} disabled={loading || providers.length === 0}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addChannel")}</Button>} />
      {loading && <div className="h-24 animate-pulse rounded-card border border-border-subtle bg-surface-muted" role="status" aria-label={t("common.loading")} />}
      {!loading && error && <div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadPaymentFailed")}</span><Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button></div>}
      {!loading && !error && <div className="grid gap-4 lg:grid-cols-2">
        {channels.map((channel) => <SettingsSection key={channel.id} title={channel.channel_name} description={`${channel.channel_code} · ${channel.provider_type} · ${channel.payment_type}`} className={!channel.is_enabled ? "opacity-65" : undefined}>
          <div className="flex items-center justify-between gap-3"><div className="flex items-center gap-2"><PaymentIcon method={channel.payment_type} className="h-5 w-5" /><StatusBadge label={channel.is_enabled ? t("admin.channelEnabled") : t("admin.channelDisabled")} tone={channel.is_enabled ? "success" : "neutral"} />{channel.badge_text && <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary">{channel.badge_text}</span>}</div><Switch checked={channel.is_enabled} onCheckedChange={(checked) => void toggle(channel, checked)} aria-label={channel.is_enabled ? t("admin.channelDisabled") : t("admin.channelEnabled")} /></div>
          {channel.payment_hint && <p className="mt-3 text-sm text-muted-foreground">{channel.payment_hint}</p>}
          <div className="mt-4 flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2 text-xs text-muted-foreground"><span>{t("admin.channelOrder").replace("{order}", String(channel.sort_order))}</span><span>{t("admin.channelCodeAuto")}</span></div>
          <div className="mt-4 flex justify-end gap-2"><Button type="button" variant="outline" size="sm" onClick={() => openEdit(channel)}><Edit className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.edit")}</Button><Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={() => setDeleteTarget(channel)}><Trash2 className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.delete")}</Button></div>
        </SettingsSection>)}
        {channels.length === 0 && <div className="col-span-full rounded-card border border-border-subtle bg-surface"><table className="w-full"><tbody><TableEmptyState colSpan={1}>{t("admin.noChannelData")}</TableEmptyState></tbody></table></div>}
      </div>}

      <Dialog open={dialogOpen} onOpenChange={(open) => open ? setDialogOpen(true) : closeDialog()}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
          <DialogHeader><DialogTitle>{editingId ? t("admin.editChannelTitle") : t("admin.addChannelTitle")}</DialogTitle></DialogHeader>
          <div className="grid gap-5 py-2">
            <SettingsSection title={t("admin.paymentCoreSettings")} description={t("admin.paymentCoreSettingsHint")} className="max-w-none">
              <div className="grid gap-4 sm:grid-cols-2">
                {providers.length > 1 ? <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{t("admin.paymentProviderRequired")}</span><select value={form.provider_type} onChange={(event) => selectProvider(event.target.value)} className="h-11 rounded-control border border-input bg-surface px-3 text-foreground"><option value="">{t("admin.choose")}</option>{providers.map((provider) => <option key={provider.provider_id} value={provider.provider_id}>{provider.display_name}</option>)}</select></label> : <div className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{t("admin.paymentProvider")}</span><div className="flex h-11 items-center rounded-control border border-border-subtle bg-surface-muted px-3 text-foreground">{currentProvider?.display_name || "易支付兼容协议"}</div><p className="text-xs text-muted-foreground">{t("admin.paymentProviderAuto")}</p></div>}
                <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{t("admin.paymentTypeRequired")}</span><select value={form.payment_type} onChange={(event) => selectPaymentType(event.target.value)} className="h-11 rounded-control border border-input bg-surface px-3 text-foreground"><option value="">{t("admin.choose")}</option>{supportedPaymentTypes(currentProvider).map((type) => <option key={type} value={type}>{type === "alipay" ? t("payment.alipay") : t("payment.wechat")}</option>)}</select></label>
              </div>
              {currentProvider && <SettingsSection title={currentProvider.display_name} className="mt-4 max-w-none"><div className="grid gap-4 sm:grid-cols-2">{currentProvider.config_schema.map(renderProviderField)}</div></SettingsSection>}
            </SettingsSection>

            <SettingsSection title={t("admin.paymentDisplaySettings")} description={t("admin.paymentDisplaySettingsHint")} className="max-w-none">
              <div className="grid gap-4 sm:grid-cols-2">
                <label className="flex flex-col gap-1.5 text-sm sm:col-span-2"><span className="font-medium">{t("admin.channelNameOptional")}</span><Input value={form.channel_name} onChange={(event) => setForm((current) => ({ ...current, channel_name: event.target.value }))} placeholder={form.payment_type ? defaultChannelName(form.payment_type) : undefined} /><span className="text-xs text-muted-foreground">{t("admin.channelNameAutoHint")}</span></label>
                <label className="flex flex-col gap-1.5 text-sm sm:col-span-2"><span className="font-medium">{t("admin.paymentHint")}</span><Input value={form.payment_hint} maxLength={160} onChange={(event) => setForm((current) => ({ ...current, payment_hint: event.target.value }))} placeholder={t("admin.paymentHintPlaceholder")} /><span className="text-xs text-muted-foreground">{t("admin.paymentHintHint")}</span></label>
                <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{t("admin.badgeText")}</span><Input value={form.badge_text} maxLength={16} onChange={(event) => setForm((current) => ({ ...current, badge_text: event.target.value }))} placeholder={t("admin.badgeTextPlaceholder")} /><span className="text-xs text-muted-foreground">{t("admin.badgeTextHint")}</span></label>
                <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{t("admin.sortOrderLabel")}</span><Input type="number" min={0} value={form.sort_order} onChange={(event) => setForm((current) => ({ ...current, sort_order: event.target.value }))} /></label>
                <div className="flex items-center justify-between gap-3 rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5 text-sm sm:col-span-2"><span>{t("admin.enableChannel")}</span><Switch checked={form.is_enabled} onCheckedChange={(checked) => setForm((current) => ({ ...current, is_enabled: checked }))} aria-label={t("admin.enableChannel")} /></div>
              </div>
            </SettingsSection>
          </div>
          <DialogFooter><Button type="button" variant="outline" onClick={closeDialog} disabled={saving}>{t("admin.cancel")}</Button><Button type="button" onClick={() => void save()} disabled={saving}>{saving ? t("admin.saving") : t("admin.save")}</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert open={deleteTarget !== null} onOpenChange={(open) => { if (!open && !deleting) setDeleteTarget(null) }} title={t("admin.deleteConfirm")} description={deleteTarget ? `${deleteTarget.channel_name} · ${t("admin.deleteChannelMsg")}` : undefined} confirmLabel={t("admin.delete")} cancelLabel={t("admin.cancel")} destructive loading={deleting} onConfirm={() => void remove()} />
    </AdminPage>
  )
}
