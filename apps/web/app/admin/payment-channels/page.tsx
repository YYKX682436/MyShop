"use client"

import { useEffect, useMemo, useState } from "react"
import { Edit, Plus, ToggleLeft, ToggleRight, Trash2, X } from "lucide-react"
import { toast } from "sonner"
import { Modal } from "@/components/ui/modal"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"
import { adminPaymentApi, paymentApi, requestOrThrow } from "@/services/api"
import type {
  PaymentChannelItem,
  PaymentProviderConfigField,
  PaymentProviderMetadata,
} from "@/types"

interface ChannelForm {
  channel_code: string
  channel_name: string
  provider_type: string
  payment_type: string
  is_enabled: boolean
  sort_order: string
}

const emptyForm: ChannelForm = {
  channel_code: "",
  channel_name: "",
  provider_type: "",
  payment_type: "",
  is_enabled: true,
  sort_order: "0",
}

function providerLabel(providers: PaymentProviderMetadata[], providerId: string): string {
  return providers.find(provider => provider.provider_id === providerId)?.display_name || providerId
}

function fieldValue(config: Record<string, unknown>, field: PaymentProviderConfigField): unknown {
  return config[field.name] ?? field.default_value ?? (field.type === "switch" ? false : "")
}

export default function AdminPaymentChannelsPage() {
  const { t } = useLocale()
  const [channels, setChannels] = useState<PaymentChannelItem[]>([])
  const [providers, setProviders] = useState<PaymentProviderMetadata[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showModal, setShowModal] = useState(false)
  const [editId, setEditId] = useState<string | null>(null)
  const [form, setForm] = useState<ChannelForm>(emptyForm)
  const [config, setConfig] = useState<Record<string, unknown>>({})

  const currentProvider = useMemo(
    () => providers.find(provider => provider.provider_id === form.provider_type),
    [form.provider_type, providers],
  )

  const load = async () => {
    try {
      const [channelData, providerData] = await Promise.all([
        requestOrThrow(() => adminPaymentApi.getList()),
        requestOrThrow(() => paymentApi.getProviders()),
      ])
      setChannels(channelData)
      setProviders(providerData)
    } catch (error) {
      setChannels([])
      setProviders([])
      toast.error(error instanceof Error ? error.message : "无法读取支付配置")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  const closeModal = () => {
    setShowModal(false)
    setEditId(null)
    setForm(emptyForm)
    setConfig({})
  }

  const openCreate = () => {
    const provider = providers[0]
    setEditId(null)
    setForm({
      ...emptyForm,
      provider_type: provider?.provider_id || "",
      payment_type: provider?.payment_types[0] || "",
    })
    setConfig({})
    setShowModal(true)
  }

  const openEdit = (channel: PaymentChannelItem) => {
    const values: Record<string, unknown> = {}
    Object.entries(channel.config_data || {}).forEach(([key, value]) => { values[key] = value })
    setEditId(channel.id)
    setForm({
      channel_code: channel.channel_code,
      channel_name: channel.channel_name,
      provider_type: channel.provider_type,
      payment_type: channel.payment_type,
      is_enabled: channel.is_enabled,
      sort_order: String(channel.sort_order),
    })
    setConfig(values)
    setShowModal(true)
  }

  const selectProvider = (providerId: string) => {
    const provider = providers.find(value => value.provider_id === providerId)
    setForm(previous => ({
      ...previous,
      provider_type: providerId,
      payment_type: provider?.payment_types.includes(previous.payment_type)
        ? previous.payment_type
        : provider?.payment_types[0] || "",
    }))
    setConfig({})
  }

  const setField = (field: PaymentProviderConfigField, rawValue: string | boolean) => {
    const value = field.type === "number" && typeof rawValue === "string" && rawValue !== ""
      ? Number(rawValue)
      : rawValue
    setConfig(previous => ({ ...previous, [field.name]: value }))
  }

  const save = async () => {
    if (!form.provider_type || !form.payment_type || !form.channel_code.trim() || !form.channel_name.trim()) {
      toast.error("请完整填写通道代码、名称、提供商和支付类型")
      return
    }
    setSaving(true)
    try {
      const data = {
        channel_code: form.channel_code.trim(),
        channel_name: form.channel_name.trim(),
        provider_type: form.provider_type,
        payment_type: form.payment_type,
        config_data: config,
        is_enabled: form.is_enabled,
        sort_order: Number(form.sort_order) || 0,
      }
      if (editId) await requestOrThrow(() => adminPaymentApi.update(editId, data))
      else await requestOrThrow(() => adminPaymentApi.create(data))
      toast.success("保存成功")
      closeModal()
      await load()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const toggle = async (channel: PaymentChannelItem) => {
    try {
      await requestOrThrow(() => adminPaymentApi.update(channel.id, { is_enabled: !channel.is_enabled }))
      await load()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "操作失败")
    }
  }

  const remove = async (channel: PaymentChannelItem) => {
    if (!window.confirm(`确定删除通道「${channel.channel_name}」吗？`)) return
    try {
      await requestOrThrow(() => adminPaymentApi.delete(channel.id))
      await load()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败")
    }
  }

  const renderField = (field: PaymentProviderConfigField) => {
    const value = fieldValue(config, field)
    const label = `${field.label}${field.required ? " *" : ""}`
    if (field.type === "switch") {
      return (
        <label key={field.name} className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5 text-sm">
          <span>{label}</span>
          <input type="checkbox" checked={Boolean(value)} onChange={event => setField(field, event.target.checked)} className="h-4 w-4 accent-primary" />
        </label>
      )
    }
    if (field.type === "select") {
      return (
        <label key={field.name} className="flex flex-col gap-1.5 text-sm">
          <span className="font-medium">{label}</span>
          <select value={String(value)} onChange={event => setField(field, event.target.value)} className="h-10 rounded-lg border border-input bg-background px-3">
            {(field.options || []).map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
      )
    }
    const inputType = field.type === "password" || field.type === "secret" || field.secret
      ? "password"
      : field.type === "number" ? "number" : field.type
    return (
      <label key={field.name} className="flex flex-col gap-1.5 text-sm">
        <span className="font-medium">{label}</span>
        <input
          type={inputType}
          value={String(value ?? "")}
          placeholder={field.placeholder || undefined}
          onChange={event => setField(field, event.target.value)}
          className="h-10 rounded-lg border border-input bg-background px-3"
        />
      </label>
    )
  }

  if (loading) return <div className="flex items-center justify-center py-24"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div>

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.payment")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.paymentDesc")}</p>
        </div>
        <button type="button" onClick={openCreate} disabled={providers.length === 0} className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-50">
          <Plus className="h-4 w-4" />新增支付通道
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {channels.map(channel => (
          <div key={channel.id} className={cn("rounded-xl border bg-card p-5 shadow-sm", !channel.is_enabled && "opacity-60")}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="font-medium text-foreground">{channel.channel_name}</h3>
                <p className="mt-1 text-xs text-muted-foreground">{channel.channel_code} · {providerLabel(providers, channel.provider_type)} · {channel.payment_type}</p>
              </div>
              <button type="button" title={channel.is_enabled ? "禁用" : "启用"} onClick={() => void toggle(channel)}>
                {channel.is_enabled ? <ToggleRight className="h-6 w-6 text-primary" /> : <ToggleLeft className="h-6 w-6 text-muted-foreground" />}
              </button>
            </div>
            <div className="mt-4 flex items-center justify-between rounded-lg bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
              <span>排序 {channel.sort_order}</span><span>实例代码唯一</span>
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button type="button" onClick={() => openEdit(channel)} className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs hover:bg-accent"><Edit className="h-3.5 w-3.5" />编辑</button>
              <button type="button" onClick={() => void remove(channel)} className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs text-destructive hover:bg-destructive/10"><Trash2 className="h-3.5 w-3.5" />删除</button>
            </div>
          </div>
        ))}
        {channels.length === 0 && <div className="col-span-full py-12 text-center text-sm text-muted-foreground">暂无支付通道</div>}
      </div>

      <Modal open={showModal} onClose={closeModal} className="max-w-2xl">
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 className="text-lg font-semibold">{editId ? "编辑支付通道" : "新增支付通道"}</h2>
          <button type="button" onClick={closeModal} className="rounded-md p-1 text-muted-foreground hover:bg-accent"><X className="h-5 w-5" /></button>
        </div>
        <div className="grid gap-4 p-6">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">支付提供商 *</span><select value={form.provider_type} onChange={event => selectProvider(event.target.value)} className="h-10 rounded-lg border border-input bg-background px-3"><option value="">请选择</option>{providers.map(provider => <option key={provider.provider_id} value={provider.provider_id}>{provider.display_name}</option>)}</select></label>
            <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">支付类型 *</span><select value={form.payment_type} onChange={event => setForm(previous => ({ ...previous, payment_type: event.target.value }))} className="h-10 rounded-lg border border-input bg-background px-3"><option value="">请选择</option>{(currentProvider?.payment_types || []).map(type => <option key={type} value={type}>{type}</option>)}</select></label>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">通道实例代码 *</span><input value={form.channel_code} onChange={event => setForm(previous => ({ ...previous, channel_code: event.target.value }))} placeholder="例如 epay-alipay-main" className="h-10 rounded-lg border border-input bg-background px-3" /><span className="text-xs text-muted-foreground">同一 provider/payment_type 可创建多个实例。</span></label>
            <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">通道名称 *</span><input value={form.channel_name} onChange={event => setForm(previous => ({ ...previous, channel_name: event.target.value }))} placeholder="例如 支付宝主通道" className="h-10 rounded-lg border border-input bg-background px-3" /></label>
          </div>
          {currentProvider && currentProvider.config_schema.length > 0 && <div className="grid gap-4 rounded-lg border border-border p-4 sm:grid-cols-2"><div className="sm:col-span-2 text-sm font-semibold">{currentProvider.display_name} 配置</div>{currentProvider.config_schema.map(renderField)}</div>}
          <div className="grid gap-4 sm:grid-cols-2"><label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">排序</span><input type="number" value={form.sort_order} onChange={event => setForm(previous => ({ ...previous, sort_order: event.target.value }))} className="h-10 rounded-lg border border-input bg-background px-3" /></label><label className="flex items-center gap-2 self-end text-sm"><input type="checkbox" checked={form.is_enabled} onChange={event => setForm(previous => ({ ...previous, is_enabled: event.target.checked }))} className="h-4 w-4 accent-primary" />启用通道</label></div>
          <div className="flex justify-end gap-3 border-t border-border pt-4"><button type="button" onClick={closeModal} className="rounded-lg border border-border px-4 py-2 text-sm">取消</button><button type="button" onClick={() => void save()} disabled={saving} className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50">{saving ? "保存中…" : "保存"}</button></div>
        </div>
      </Modal>
    </div>
  )
}
