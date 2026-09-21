"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { AlertTriangle, CheckCircle2, Loader2, RefreshCw, ShieldCheck } from "lucide-react"
import { toast } from "sonner"

import { FormSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { isValidUnlimitedStockQuantity } from "@/lib/sales-channel-stock"
import {
  adminSalesChannelApi,
  type SalesChannelAccountSummary,
  type SalesChannelListingSummary,
  type XianyuPlatformAttribute,
  type XianyuPriceMode,
  type XianyuProviderConfig,
} from "@/services/api"

const EMPTY_CONFIG: XianyuProviderConfig = {
  titleOverride: "",
  descriptionOverride: "",
  imageUrls: [],
  priceMode: "PRODUCT_RETAIL",
  fixedPrice: null,
  markupPercent: null,
  address: "",
  addressExpectedText: "",
  platformCategoryId: "",
  platformCategoryName: "",
  platformChannelCategoryId: "",
  platformChannelCategoryName: "",
  platformLeafId: "",
  platformTbCategoryId: "",
  platformAttributes: [],
  deliveryMethod: "",
  shippingMethod: "",
  supportPickup: false,
  postage: null,
  unlimitedStockQuantity: 999,
}

type BusyAction = "load" | "sync" | "verify" | "save" | "enable" | "disable" | "retry" | null

function configFromListing(listing?: SalesChannelListingSummary): XianyuProviderConfig {
  const config = listing?.providerConfig
  return {
    ...EMPTY_CONFIG,
    ...(config || {}),
    imageUrls: Array.isArray(config?.imageUrls) ? config.imageUrls : [],
    platformAttributes: Array.isArray(config?.platformAttributes) ? config.platformAttributes : [],
  }
}

function optionalNumber(value: string): number | null {
  if (!value.trim()) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function accountEnabled(account: SalesChannelAccountSummary | undefined) {
  return Boolean(account) && account?.enabled !== false && account?.isEnabled !== false
}

function accountType(account: SalesChannelAccountSummary | undefined) {
  return account?.accountType || "UNKNOWN"
}

function accountStatus(account: SalesChannelAccountSummary | undefined) {
  if (!account) return "未选择账号"
  return account.status || "UNKNOWN"
}

function updateById<T extends { id: string }>(items: T[], updated: T) {
  const index = items.findIndex((item) => item.id === updated.id)
  if (index < 0) return [...items, updated]
  return items.map((item) => item.id === updated.id ? updated : item)
}

export function SalesChannelEditor({ productId, disabled = false }: { productId: string; disabled?: boolean }) {
  const [accounts, setAccounts] = useState<SalesChannelAccountSummary[]>([])
  const [listings, setListings] = useState<SalesChannelListingSummary[]>([])
  const [selectedAccountId, setSelectedAccountId] = useState("")
  const [config, setConfig] = useState<XianyuProviderConfig>(EMPTY_CONFIG)
  const [attributesDraft, setAttributesDraft] = useState("[]")
  const [loadError, setLoadError] = useState("")
  const [busy, setBusy] = useState<BusyAction>("load")

  const selectedAccount = useMemo(
    () => accounts.find((account) => account.id === selectedAccountId),
    [accounts, selectedAccountId],
  )
  const selectedListing = useMemo(
    () => listings.find((listing) => listing.providerAccountId === selectedAccount?.providerAccountId),
    [listings, selectedAccount?.providerAccountId],
  )
  const type = accountType(selectedAccount)
  const needsVerification = Boolean(selectedAccount && type === "UNKNOWN")
  const canOperate = !disabled && !busy && accountEnabled(selectedAccount) && !needsVerification

  const refresh = useCallback(async () => {
    setLoadError("")
    const [nextAccounts, nextListings] = await Promise.all([
      adminSalesChannelApi.getAccounts(),
      adminSalesChannelApi.getProductListings(productId),
    ])
    setAccounts(nextAccounts)
    setListings(nextListings)
    setSelectedAccountId((current) => current && nextAccounts.some((account) => account.id === current)
      ? current
      : nextAccounts[0]?.id || "")
  }, [productId])

  useEffect(() => {
    let active = true
    setBusy("load")
    void refresh().catch((error: unknown) => {
      if (!active) return
      setLoadError(error instanceof Error ? error.message : "加载销售渠道失败")
    }).finally(() => {
      if (active) setBusy(null)
    })
    return () => { active = false }
  }, [refresh])

  useEffect(() => {
    const nextConfig = configFromListing(selectedListing)
    setConfig(nextConfig)
    setAttributesDraft(JSON.stringify(nextConfig.platformAttributes || [], null, 2))
  }, [selectedAccountId, selectedListing])

  const patchConfig = <K extends keyof XianyuProviderConfig>(key: K, value: XianyuProviderConfig[K]) => {
    setConfig((current) => ({ ...current, [key]: value }))
  }

  const persistConfig = async () => {
    if (!selectedAccount) throw new Error("请先选择闲鱼账号")
    let platformAttributes: XianyuPlatformAttribute[]
    try {
      const parsed: unknown = JSON.parse(attributesDraft || "[]")
      if (!Array.isArray(parsed) || parsed.some((entry) => !entry || typeof entry !== "object" || Array.isArray(entry))) {
        throw new Error("平台属性必须是 JSON 对象数组")
      }
      platformAttributes = parsed as XianyuPlatformAttribute[]
    } catch (error) {
      if (error instanceof SyntaxError) throw new Error("平台属性 JSON 格式无效")
      throw error
    }

    const priceMode = config.priceMode || "PRODUCT_RETAIL"
    const fixedPrice = optionalNumber(String(config.fixedPrice ?? ""))
    const markupPercent = optionalNumber(String(config.markupPercent ?? ""))
    const postage = optionalNumber(String(config.postage ?? ""))
    const unlimitedStockQuantity = optionalNumber(String(config.unlimitedStockQuantity ?? ""))
    if (priceMode === "FIXED" && (fixedPrice === null || fixedPrice <= 0)) {
      throw new Error("固定售价必须大于 0")
    }
    if (priceMode === "MARKUP_PERCENT" && (markupPercent === null || markupPercent < 0)) {
      throw new Error("加价比例必须是大于或等于 0 的数字")
    }
    if (postage !== null && postage < 0) throw new Error("邮费不能小于 0")
    if (!isValidUnlimitedStockQuantity(unlimitedStockQuantity)) {
      throw new Error("无限库存同步数量必须是大于或等于 0 的整数")
    }

    const normalized: XianyuProviderConfig = {
      ...config,
      priceMode,
      fixedPrice,
      markupPercent,
      postage,
      unlimitedStockQuantity: unlimitedStockQuantity ?? 999,
      imageUrls: (config.imageUrls || []).map((url) => url.trim()).filter(Boolean),
      platformAttributes,
    }
    const listing = await adminSalesChannelApi.updateXianyuListing(productId, {
      accountId: selectedAccount.id,
      config: normalized,
    })
    setConfig(configFromListing(listing))
    setAttributesDraft(JSON.stringify(listing.providerConfig?.platformAttributes || platformAttributes, null, 2))
    setListings((current) => updateById(current, listing))
    return listing
  }

  const handleSyncAccounts = async () => {
    setBusy("sync")
    try {
      await adminSalesChannelApi.syncXianyuAccounts()
      await refresh()
      toast.success("闲鱼账号已同步")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "同步闲鱼账号失败")
    } finally {
      setBusy(null)
    }
  }

  const handleVerify = async () => {
    if (!selectedAccount) return
    setBusy("verify")
    try {
      const verified = await adminSalesChannelApi.verifyAccount(selectedAccount.id)
      setAccounts((current) => updateById(current, verified))
      toast.success("闲鱼账号能力已更新")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "验证闲鱼账号失败")
    } finally {
      setBusy(null)
    }
  }

  const handleSave = async () => {
    setBusy("save")
    try {
      await persistConfig()
      toast.success("闲鱼渠道配置已保存")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存闲鱼配置失败")
    } finally {
      setBusy(null)
    }
  }

  const handleEnable = async () => {
    if (!canOperate || !selectedAccount) return
    setBusy("enable")
    try {
      const saved = await persistConfig()
      const enabled = await adminSalesChannelApi.enableListing(saved.id)
      setListings((current) => updateById(current, enabled))
      toast.success("已提交闲鱼发布或同步操作")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "启用闲鱼销售失败")
    } finally {
      setBusy(null)
    }
  }

  const handleDisable = async () => {
    if (!canOperate || !selectedListing) return
    setBusy("disable")
    try {
      const updated = await adminSalesChannelApi.disableListing(selectedListing.id)
      setListings((current) => updateById(current, updated))
      toast.success("已提交闲鱼下架操作")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "停止闲鱼销售失败")
    } finally {
      setBusy(null)
    }
  }

  const handleRetry = async () => {
    if (!canOperate || !selectedListing) return
    setBusy("retry")
    try {
      const updated = await adminSalesChannelApi.retryListing(selectedListing.id)
      setListings((current) => updateById(current, updated))
      toast.success("已提交重试操作")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "重试闲鱼操作失败")
    } finally {
      setBusy(null)
    }
  }

  const setText = (key: keyof XianyuProviderConfig, value: string) => patchConfig(key, value)
  const selectClass = "h-10 w-full rounded-control border border-input bg-surface px-3 text-sm font-normal disabled:cursor-not-allowed disabled:opacity-60"
  const labelClass = "flex min-w-0 flex-col gap-1.5 text-sm font-medium"
  const noSelection = !selectedAccount
  const busyLabel = busy ? "处理中…" : ""

  return (
    <FormSection title="销售渠道 · 闲鱼" description="配置由 MyShop 管理接口保存，发布、同步和下架由后台队列处理。">
      {busy === "load" && <div className="flex items-center gap-2 py-3 text-sm text-muted-foreground" role="status"><Loader2 className="size-4 animate-spin" />正在加载闲鱼账号和渠道状态…</div>}
      {loadError && <div className="mb-4 flex items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive" role="alert"><span>{loadError}</span><Button type="button" variant="outline" size="sm" disabled={Boolean(busy)} onClick={() => { setBusy("load"); void refresh().catch((error: unknown) => setLoadError(error instanceof Error ? error.message : "加载销售渠道失败")).finally(() => setBusy(null)) }}>重试加载</Button></div>}

      <div className="mb-5 flex flex-wrap items-end gap-3">
        <label className={`${labelClass} min-w-[240px] flex-1`}>
          闲鱼账号
          <select className={selectClass} value={selectedAccountId} onChange={(event) => setSelectedAccountId(event.target.value)} disabled={Boolean(busy) || accounts.length === 0}>
            <option value="">{accounts.length ? "选择已同步账号" : "暂无已同步账号"}</option>
            {accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName ? `${account.displayName} (${account.providerAccountId})` : account.providerAccountId} · {account.accountType || "UNKNOWN"}</option>)}
          </select>
        </label>
        <Button type="button" variant="outline" onClick={() => void handleSyncAccounts()} disabled={Boolean(busy)}><RefreshCw className={busy === "sync" ? "animate-spin" : ""} />同步账号</Button>
        <Button type="button" variant="outline" onClick={() => void handleVerify()} disabled={Boolean(busy) || disabled || !selectedAccount}><ShieldCheck />验证账号</Button>
      </div>

      {selectedAccount && <div className="mb-5 grid gap-3 rounded-card border border-border-subtle bg-surface-muted/45 p-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
        <div><span className="text-muted-foreground">账号类型</span><div className="mt-1 font-medium">{type}</div></div>
        <div><span className="text-muted-foreground">账号状态</span><div className="mt-1 font-medium">{accountStatus(selectedAccount)}</div></div>
        <div><span className="text-muted-foreground">渠道状态</span><div className="mt-1 font-medium">{selectedListing?.status || "尚未配置"}{selectedListing ? ` · ${selectedListing.isEnabled ? "已启用" : "已停用"}` : ""}</div></div>
        <div><span className="text-muted-foreground">闲鱼 item_id</span><div className="mt-1 break-all font-medium">{selectedListing?.externalItemId || "—"}</div></div>
        <div><span className="text-muted-foreground">目标版本 / 最近同步</span><div className="mt-1 font-medium">{selectedListing?.desiredRevision ?? "—"} / {selectedListing?.lastSyncedAt ? new Date(selectedListing.lastSyncedAt).toLocaleString() : "—"}</div></div>
        {selectedListing?.lastErrorCode && <div className="sm:col-span-2 lg:col-span-4"><span className="text-muted-foreground">最近错误</span><div className="mt-1 flex items-start gap-2 text-destructive"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><span><strong>{selectedListing.lastErrorCode}</strong>{selectedListing.lastErrorMessage ? ` · ${selectedListing.lastErrorMessage}` : ""}</span></div></div>}
      </div>}

      {needsVerification && <p className="mb-4 flex items-center gap-2 rounded-card border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-800 dark:text-amber-200"><AlertTriangle className="size-4 shrink-0" />账号能力尚未确认。验证账号后才能启用渠道。</p>}
      {!accountEnabled(selectedAccount) && selectedAccount && <p className="mb-4 rounded-card border border-border-subtle bg-surface-muted/45 p-3 text-sm text-muted-foreground">此账号已停用，当前只能查看配置。</p>}

      <div className="grid gap-4 lg:grid-cols-2">
        <label className={labelClass}>
          闲鱼标题覆盖
          <Input value={config.titleOverride || ""} onChange={(event) => setText("titleOverride", event.target.value)} disabled={noSelection || Boolean(busy)} placeholder="留空时使用商品标题" />
        </label>
        <label className={labelClass}>
          售价策略
          <select className={selectClass} value={config.priceMode || "PRODUCT_RETAIL"} onChange={(event) => patchConfig("priceMode", event.target.value as XianyuPriceMode)} disabled={noSelection || Boolean(busy)}>
            <option value="PRODUCT_RETAIL">跟随商品售价</option>
            <option value="FIXED">固定售价</option>
            <option value="MARKUP_PERCENT">按百分比加价</option>
          </select>
        </label>
        {config.priceMode === "FIXED" && <label className={labelClass}>固定售价<Input type="number" min="0.01" step="0.01" value={config.fixedPrice ?? ""} onChange={(event) => patchConfig("fixedPrice", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>}
        {config.priceMode === "MARKUP_PERCENT" && <label className={labelClass}>加价比例 (%)<Input type="number" min="0" step="0.01" value={config.markupPercent ?? ""} onChange={(event) => patchConfig("markupPercent", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>}
        <label className={`${labelClass} lg:col-span-2`}>
          闲鱼描述覆盖
          <Textarea className="min-h-24" value={config.descriptionOverride || ""} onChange={(event) => setText("descriptionOverride", event.target.value)} disabled={noSelection || Boolean(busy)} placeholder="留空时使用商品描述或详情内容" />
        </label>
        <label className={`${labelClass} lg:col-span-2`}>
          图片 URL（每行一个；留空时使用商品封面）
          <Textarea className="min-h-20" value={(config.imageUrls || []).join("\n")} onChange={(event) => patchConfig("imageUrls", event.target.value.split(/\r?\n/).map((url) => url.trim()).filter(Boolean))} disabled={noSelection || Boolean(busy)} placeholder="https://…" />
        </label>
        <label className={labelClass}>发布地址<Input value={config.address || ""} onChange={(event) => setText("address", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>地址校验文字<Input value={config.addressExpectedText || ""} onChange={(event) => setText("addressExpectedText", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>运输方式<Input value={config.shippingMethod || ""} onChange={(event) => setText("shippingMethod", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>交付方式<Input value={config.deliveryMethod || ""} onChange={(event) => setText("deliveryMethod", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>邮费<Input type="number" min="0" step="0.01" value={config.postage ?? ""} onChange={(event) => patchConfig("postage", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>无限库存同步数量<Input type="number" min="0" step="1" value={config.unlimitedStockQuantity ?? 999} onChange={(event) => patchConfig("unlimitedStockQuantity", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>
        <label className="flex items-center gap-2 text-sm font-medium lg:col-span-2"><input type="checkbox" checked={Boolean(config.supportPickup)} onChange={(event) => patchConfig("supportPickup", event.target.checked)} disabled={noSelection || Boolean(busy)} className="size-4 accent-primary" />支持自提</label>
      </div>

      <details className="mt-5 rounded-card border border-border-subtle p-4">
        <summary className="cursor-pointer text-sm font-semibold">分类和平台属性（高级）</summary>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <label className={labelClass}>平台分类 ID<Input value={config.platformCategoryId || ""} onChange={(event) => setText("platformCategoryId", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className={labelClass}>平台分类名称<Input value={config.platformCategoryName || ""} onChange={(event) => setText("platformCategoryName", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className={labelClass}>渠道分类 ID<Input value={config.platformChannelCategoryId || ""} onChange={(event) => setText("platformChannelCategoryId", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className={labelClass}>渠道分类名称<Input value={config.platformChannelCategoryName || ""} onChange={(event) => setText("platformChannelCategoryName", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className={labelClass}>叶子分类 ID<Input value={config.platformLeafId || ""} onChange={(event) => setText("platformLeafId", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className={labelClass}>淘宝分类 ID<Input value={config.platformTbCategoryId || ""} onChange={(event) => setText("platformTbCategoryId", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium sm:col-span-2">平台属性 JSON<textarea className="min-h-28 w-full rounded-control border border-input bg-surface px-3 py-2 font-mono text-xs" value={attributesDraft} onChange={(event) => setAttributesDraft(event.target.value)} disabled={noSelection || Boolean(busy)} spellCheck={false} /><span className="text-xs font-normal text-muted-foreground">JSON 数组，每项包含 propertyId、propertyName、valueId、valueName、text 或 properties。</span></label>
        </div>
      </details>

      <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border-subtle pt-4">
        <Button type="button" variant="outline" onClick={() => void handleSave()} disabled={!canOperate || noSelection}><CheckCircle2 />保存配置</Button>
        <Button type="button" onClick={() => void handleEnable()} disabled={!canOperate || noSelection}>{busy === "enable" ? <Loader2 className="animate-spin" /> : null}{selectedListing?.isEnabled ? "启用并同步" : "发布到闲鱼 / 启用"}</Button>
        {selectedListing?.isEnabled && <Button type="button" variant="outline" onClick={() => void handleDisable()} disabled={!canOperate}>{busy === "disable" ? <Loader2 className="animate-spin" /> : null}停止闲鱼销售</Button>}
        {selectedListing && <Button type="button" variant="outline" onClick={() => void handleRetry()} disabled={!canOperate}>{busy === "retry" ? <Loader2 className="animate-spin" /> : null}重试</Button>}
        {disabled && <span className="text-xs text-muted-foreground">请先保存当前商品编辑，再执行渠道操作。</span>}
        {busy && busy !== "load" && <span className="text-xs text-muted-foreground" role="status">{busyLabel}</span>}
      </div>
    </FormSection>
  )
}
