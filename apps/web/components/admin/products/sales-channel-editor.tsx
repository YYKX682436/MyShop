"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { AlertTriangle, CheckCircle2, Loader2, MapPin, RefreshCw, ShieldCheck, Sparkles } from "lucide-react"
import { toast } from "sonner"

import { FormSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { isValidStockCapPerSku, isValidUnlimitedStockQuantity } from "@/lib/sales-channel-stock"
import {
  adminSalesChannelApi,
  type SalesChannelAccountSummary,
  type SalesChannelListingSummary,
  type XianyuAddressTip,
  type XianyuCategoryCandidate,
  type XianyuCategoryProperty,
  type XianyuCategoryPropertyOption,
  type XianyuPlatformAttribute,
  type XianyuPriceMode,
  type XianyuProviderConfig,
} from "@/services/api"
import {
  normalizeXianyuAddressTip,
  normalizeXianyuCategoryCandidate,
  normalizeXianyuCategoryProperty,
} from "@/lib/sales-channel-metadata"

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
  stockCapPerSku: null,
}

type BusyAction = "load" | "sync" | "verify" | "save" | "enable" | "disable" | "retry" | "mapping" | "auto" | null

function configFromProviderConfig(config?: XianyuProviderConfig | null): XianyuProviderConfig {
  return {
    ...EMPTY_CONFIG,
    ...(config || {}),
    imageUrls: Array.isArray(config?.imageUrls) ? config.imageUrls : [],
    platformAttributes: Array.isArray(config?.platformAttributes) ? config.platformAttributes : [],
  }
}

function configFromListing(listing?: SalesChannelListingSummary): XianyuProviderConfig {
  return configFromProviderConfig(listing?.providerConfig)
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

function listingStatusLabel(status?: string | null) {
  switch (status) {
    case "REPUBLISHING":
      return "替代商品发布中"
    case "REPLACEMENT_PENDING":
      return "新闲鱼商品已创建，正在完成映射/接管"
    case "PUBLISHING":
      return "发布中"
    case "SYNCING":
      return "同步中"
    case "APPLYING":
      return "处理中"
    case "OFFLINING":
      return "下架中"
    case "ONLINE":
      return "在线"
    case "OFFLINE":
      return "已下架"
    case "DRAFT":
      return "草稿"
    case "ERROR":
      return "错误"
    case "UNKNOWN":
      return "状态未知"
    default:
      return status || "尚未配置"
  }
}

function updateById<T extends { id: string }>(items: T[], updated: T) {
  const index = items.findIndex((item) => item.id === updated.id)
  if (index < 0) return [...items, updated]
  return items.map((item) => item.id === updated.id ? updated : item)
}

function preserveSavedProviderConfigs(
  current: SalesChannelListingSummary[],
  next: SalesChannelListingSummary[],
) {
  const currentById = new Map(current.map((listing) => [listing.id, listing]))
  return next.map((listing) => {
    const previous = currentById.get(listing.id)
    return previous ? { ...listing, providerConfig: previous.providerConfig } : listing
  })
}

function ReadOnlyCategoryValue({ label, value }: { label: string; value?: string | null }) {
  return <div className="min-w-0 rounded-control bg-surface-muted/45 px-3 py-2 text-sm">
    <span className="text-xs text-muted-foreground">{label}</span>
    <div className="mt-1 break-all font-mono text-xs">{value || "—"}</div>
  </div>
}

export function SalesChannelEditor({ productId, productTitle, productDescription, deliveryType = "AUTO", stockMode = "CARD_KEY", specEnabled = false, disabled = false }: {
  productId: string
  productTitle: string
  productDescription: string
  deliveryType?: "AUTO" | "MANUAL"
  stockMode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  specEnabled?: boolean
  disabled?: boolean
}) {
  const [accounts, setAccounts] = useState<SalesChannelAccountSummary[]>([])
  const [listings, setListings] = useState<SalesChannelListingSummary[]>([])
  const [selectedAccountId, setSelectedAccountId] = useState("")
  const [config, setConfig] = useState<XianyuProviderConfig>(EMPTY_CONFIG)
  const [addressKeyword, setAddressKeyword] = useState("")
  const [addressTips, setAddressTips] = useState<XianyuAddressTip[]>([])
  const [selectedAddressTip, setSelectedAddressTip] = useState<XianyuAddressTip | null>(null)
  const [addressBusy, setAddressBusy] = useState(false)
  const [addressError, setAddressError] = useState("")
  const [categoryCandidates, setCategoryCandidates] = useState<XianyuCategoryCandidate[]>([])
  const [categoryCardList, setCategoryCardList] = useState<unknown[]>([])
  const [categoryProperties, setCategoryProperties] = useState<XianyuCategoryProperty[]>([])
  const [selectedCategoryKey, setSelectedCategoryKey] = useState("")
  const [highlightedCategoryKey, setHighlightedCategoryKey] = useState("")
  const [categoryBusy, setCategoryBusy] = useState(false)
  const [categoryError, setCategoryError] = useState("")
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
  const selectedListingConfig = useMemo(
    () => configFromProviderConfig(selectedListing?.providerConfig),
    [selectedListing?.providerConfig],
  )
  const type = accountType(selectedAccount)
  const needsVerification = Boolean(selectedAccount && type === "UNKNOWN")
  const canOperate = !disabled && !busy && accountEnabled(selectedAccount) && !needsVerification
  const takeoverPending = selectedListing?.managementMode === "LINKED_ONLY"
    || selectedListing?.managementMode === "TAKEOVER_PENDING"
  const canProviderOperate = canOperate && !takeoverPending
  const specMappings = selectedListing?.specMappings || []
  const specMappingReady = !specEnabled || (
    Boolean(selectedListing) &&
    specMappings.length > 0 &&
    specMappings.every((mapping) => Boolean(mapping.externalSkuId?.trim() || (mapping.providerSpecName?.trim() && mapping.providerSpecValue?.trim()))) &&
    new Set(specMappings.map((mapping) => mapping.productSpecId)).size === specMappings.length &&
    new Set(specMappings.map((mapping) => `${mapping.providerSpecName?.trim() || ""}\u0000${mapping.providerSpecValue?.trim() || ""}`)).size === specMappings.length
  )
  const currentItemId = selectedListing?.currentExternalItemId || selectedListing?.externalItemId
  const autoGateReady = deliveryType === "AUTO" && stockMode === "CARD_KEY" && type === "FISH_SHOP" && Boolean(currentItemId) && specMappingReady
  const finalTitle = config.titleOverride?.trim() || productTitle.trim()
  const finalDescription = config.descriptionOverride?.trim() || productDescription.trim()

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
    let active = true
    let requestInFlight = false
    const pollStatus = async () => {
      if (!active || requestInFlight || document.visibilityState === "hidden") return
      requestInFlight = true
      try {
        const nextListings = await adminSalesChannelApi.getProductListings(productId)
        if (active) {
          setListings((current) => preserveSavedProviderConfigs(current, nextListings))
        }
      } catch {
        // Keep the last fetched status; explicit loads and user actions surface errors.
      } finally {
        requestInFlight = false
      }
    }
    const timer = window.setInterval(() => { void pollStatus() }, 5000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [productId])

  useEffect(() => {
    setConfig(selectedListingConfig)
    setSelectedAddressTip(null)
    setAddressTips([])
    setAddressError("")
    setCategoryCandidates([])
    setCategoryCardList([])
    setCategoryProperties([])
    setSelectedCategoryKey("")
    setHighlightedCategoryKey("")
    setCategoryError("")
  }, [selectedAccountId, selectedListing?.id, selectedListingConfig])

  const patchConfig = <K extends keyof XianyuProviderConfig>(key: K, value: XianyuProviderConfig[K]) => {
    setConfig((current) => ({ ...current, [key]: value }))
  }

  const persistConfig = async () => {
    if (!selectedAccount) throw new Error("请先选择闲鱼账号")
    const priceMode = config.priceMode || "PRODUCT_RETAIL"
    const fixedPrice = optionalNumber(String(config.fixedPrice ?? ""))
    const markupPercent = optionalNumber(String(config.markupPercent ?? ""))
    const postage = optionalNumber(String(config.postage ?? ""))
    const unlimitedStockQuantity = optionalNumber(String(config.unlimitedStockQuantity ?? ""))
    const stockCapPerSku = optionalNumber(String(config.stockCapPerSku ?? ""))
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
    if (!isValidStockCapPerSku(stockCapPerSku)) {
      throw new Error("闲鱼单规格库存上限必须是 0 到 999999 之间的整数")
    }

    const normalized: XianyuProviderConfig = {
      ...config,
      priceMode,
      fixedPrice,
      markupPercent,
      postage,
      unlimitedStockQuantity: unlimitedStockQuantity ?? 999,
      stockCapPerSku,
      imageUrls: (config.imageUrls || []).map((url) => url.trim()).filter(Boolean),
      platformAttributes: config.platformAttributes || [],
    }
    const listing = await adminSalesChannelApi.updateXianyuListing(productId, {
      accountId: selectedAccount.id,
      config: normalized,
    })
    setConfig(configFromListing(listing))
    setListings((current) => updateById(current, listing))
    return listing
  }

  const handleAddressSearch = async () => {
    const keywords = addressKeyword.trim()
    if (!keywords) {
      setAddressError("请输入城市、区或 POI 名称")
      setAddressTips([])
      return
    }
    setAddressBusy(true)
    setAddressError("")
    try {
      const result = await adminSalesChannelApi.searchXianyuAddress({ keywords, city: "全国" })
      const tips = Array.isArray(result.tips) ? result.tips.map(normalizeXianyuAddressTip) : []
      setAddressTips(tips)
      if (!tips.length) setAddressError("没有找到匹配的所在地，请换个关键词搜索")
    } catch (error) {
      setAddressTips([])
      setAddressError(error instanceof Error ? error.message : "搜索所在地失败")
    } finally {
      setAddressBusy(false)
    }
  }

  const handleRecommendCategory = async () => {
    if (!selectedAccount) return
    if (!finalTitle) {
      setCategoryError("请先填写商品标题或闲鱼标题覆盖")
      return
    }
    setCategoryBusy(true)
    setCategoryError("")
    setCategoryCandidates([])
    setCategoryProperties([])
    setCategoryCardList([])
    setSelectedCategoryKey("")
    setHighlightedCategoryKey("")
    try {
      const result = await adminSalesChannelApi.recommendXianyuCategory({
        accountId: selectedAccount.providerAccountId,
        title: finalTitle,
        description: finalDescription,
        cardList: [],
        selectedCategory: null,
      })
      const candidates = Array.isArray(result.candidates) ? result.candidates : []
      const cardList = Array.isArray(result.cardList) ? result.cardList
        : Array.isArray(result.card_list) ? result.card_list : []
      const normalized = candidates.map((candidate, index) => normalizeXianyuCategoryCandidate(candidate, index))
      setCategoryCandidates(candidates)
      setCategoryCardList(cardList)
      setHighlightedCategoryKey(normalized.find((candidate) => candidate.isSelected)?.key || "")
      if (!candidates.length) setCategoryError("平台没有返回分类候选，请调整标题或描述后重试")
    } catch (error) {
      setCategoryError(error instanceof Error ? error.message : "智能推荐分类失败")
    } finally {
      setCategoryBusy(false)
    }
  }

  const handleSelectCategory = async (rawCandidate: XianyuCategoryCandidate, index: number) => {
    if (!selectedAccount) return
    const selected = normalizeXianyuCategoryCandidate(rawCandidate, index)
    if (!selected.canPublish) return
    const sameCategory = config.platformCategoryId === selected.catId
      && config.platformChannelCategoryId === selected.channelCatId
      && config.platformTbCategoryId === selected.tbCatId
    setCategoryBusy(true)
    setCategoryError("")
    try {
      const result = await adminSalesChannelApi.recommendXianyuCategory({
        accountId: selectedAccount.providerAccountId,
        title: finalTitle,
        description: finalDescription,
        cardList: categoryCardList,
        selectedCategory: rawCandidate,
      })
      const properties = Array.isArray(result.properties) ? result.properties.map(normalizeXianyuCategoryProperty) : []
      setCategoryProperties(properties)
      setSelectedCategoryKey(selected.key)
      setHighlightedCategoryKey(selected.key)
      patchConfig("platformCategoryId", selected.catId)
      patchConfig("platformCategoryName", selected.catName)
      patchConfig("platformChannelCategoryId", selected.channelCatId)
      patchConfig("platformChannelCategoryName", selected.channelCatName)
      patchConfig("platformLeafId", selected.leafId)
      patchConfig("platformTbCategoryId", selected.tbCatId)
      patchConfig("platformAttributes", sameCategory ? config.platformAttributes || [] : [])
      toast.success("分类已选择，请检查动态属性并保存配置")
    } catch (error) {
      setCategoryError(error instanceof Error ? error.message : "获取分类属性失败")
    } finally {
      setCategoryBusy(false)
    }
  }

  const chooseAddress = (tip: XianyuAddressTip) => {
    setSelectedAddressTip(tip)
    patchConfig("address", tip.searchKeyword || "")
    patchConfig("addressExpectedText", tip.expectedText || null)
    setAddressTips([])
    setAddressError("")
  }

  const updatePropertyAttributes = (
    property: XianyuCategoryProperty,
    nextOptions: XianyuCategoryPropertyOption[],
    textValue?: string,
  ) => {
    const propertyId = property.propertyId || ""
    const propertyName = property.propertyName || propertyId
    const currentAttributes = config.platformAttributes || []
    const retained = currentAttributes.filter((attribute) => {
      const record = attribute as XianyuPlatformAttribute & { property_id?: string | null }
      return (record.propertyId || record.property_id) !== propertyId
    })
    const nextAttributes = textValue !== undefined
      ? (textValue.trim() ? [{ propertyId, propertyName, valueId: null, valueName: textValue.trim(), text: textValue.trim(), properties: null }] : [])
      : nextOptions.map((option) => {
        const valueName = option.valueName || ""
        const valueId = option.valueId || null
        return {
          propertyId,
          propertyName,
          valueId,
          valueName,
          text: valueName,
          properties: null,
        }
      })
    patchConfig("platformAttributes", [...retained, ...nextAttributes])
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
    if (!canProviderOperate || !selectedAccount) return
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
    if (!canProviderOperate || !selectedListing) return
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
    if (!canProviderOperate || !selectedListing) return
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

  const handleSyncSpecMappings = async () => {
    if (!canOperate || !selectedListing) return
    setBusy("mapping")
    try {
      const result = await adminSalesChannelApi.syncSpecMappings(selectedListing.id)
      setListings((current) => current.map((listing) => listing.id === selectedListing.id
        ? { ...listing, specMappings: result.mappings }
        : listing))
      toast.success(`已同步 ${result.matched} 个闲鱼规格映射`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "同步闲鱼规格映射失败")
    } finally {
      setBusy(null)
    }
  }

  const handleAutoFulfillment = async () => {
    if (!selectedListing || !canProviderOperate) return
    setBusy("auto")
    try {
      const updated = selectedListing.autoFulfillmentEnabled
        ? await adminSalesChannelApi.disableAutoFulfillment(selectedListing.id)
        : await adminSalesChannelApi.enableAutoFulfillment(selectedListing.id)
      setListings((current) => updateById(current, updated))
      toast.success(updated.autoFulfillmentEnabled ? "闲鱼自动发卡已开启" : "闲鱼自动发卡已关闭")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "更新自动发卡状态失败")
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
        <div><span className="text-muted-foreground">管理模式</span><div className="mt-1 font-medium">{selectedListing?.managementMode === "LINKED_ONLY" ? "仅关联" : selectedListing?.managementMode === "TAKEOVER_PENDING" ? "接管待完成" : selectedListing?.managementMode === "MANAGED" ? "MyShop 管理" : "尚未配置"}</div></div>
        <div><span className="text-muted-foreground">渠道状态</span><div className="mt-1 font-medium">{listingStatusLabel(selectedListing?.status)}{selectedListing ? ` · ${selectedListing.isEnabled ? "已启用" : "已停用"}` : ""}</div></div>
        <div><span className="text-muted-foreground">自动发卡</span><div className="mt-1 font-medium">{selectedListing?.autoFulfillmentEnabled ? "已开启" : "已关闭"}</div></div>
        <div><span className="text-muted-foreground">闲鱼 item_id</span><div className="mt-1 break-all font-medium">{currentItemId || "—"}</div></div>
        <div><span className="text-muted-foreground">当前 generation</span><div className="mt-1 break-all font-medium">{selectedListing?.currentGenerationNo == null ? "—" : `第 ${selectedListing.currentGenerationNo} 代 · 共 ${selectedListing.generationCount ?? 0} 代`}</div></div>
        <div><span className="text-muted-foreground">目标版本 / 已应用版本</span><div className="mt-1 font-medium">{selectedListing?.desiredRevision ?? "—"} / {selectedListing?.appliedRevision ?? "—"}</div></div>
        {selectedListing?.lastErrorCode && <div className="sm:col-span-2 lg:col-span-4"><span className="text-muted-foreground">最近错误</span><div className="mt-1 flex items-start gap-2 text-destructive"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><span><strong>{selectedListing.lastErrorCode}</strong>{selectedListing.lastErrorMessage ? ` · ${selectedListing.lastErrorMessage}` : ""}</span></div></div>}
      </div>}

      {selectedListing?.status === "REPLACEMENT_PENDING" && <p className="mb-4 rounded-card border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-800 dark:text-amber-200">新闲鱼商品已创建，正在完成映射/接管。映射完成前不会把候选商品显示为在线，也不会再次创建替代商品。</p>}

      {needsVerification && <p className="mb-4 flex items-center gap-2 rounded-card border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-800 dark:text-amber-200"><AlertTriangle className="size-4 shrink-0" />账号能力尚未确认。验证账号后才能启用渠道。</p>}
      {!accountEnabled(selectedAccount) && selectedAccount && <p className="mb-4 rounded-card border border-border-subtle bg-surface-muted/45 p-3 text-sm text-muted-foreground">此账号已停用，当前只能查看配置。</p>}
      {selectedListing && takeoverPending && <p className="mb-4 rounded-card border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-800 dark:text-amber-200">当前管理模式为“{selectedListing.managementMode === "TAKEOVER_PENDING" ? "接管待完成" : "仅关联"}”。可以保存本地配置、读取地址/分类 metadata 和只读规格映射；接管完成前禁止启用、停止平台商品、重试和自动发货。</p>}
      {selectedAccount && <div className={`mb-4 rounded-card border p-3 text-sm ${autoGateReady ? "border-emerald-500/30 bg-emerald-500/5" : "border-amber-500/30 bg-amber-500/5"}`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div><strong>自动发卡</strong><p className="mt-1 text-xs text-muted-foreground">Phase 4 仅支持 MyShop 的 AUTO + CARD_KEY + FISH_SHOP 商品；Agent 不分配卡密。</p></div>
          {selectedListing && <Button type="button" variant="outline" size="sm" onClick={() => void handleAutoFulfillment()} disabled={!canProviderOperate || !autoGateReady || !currentItemId}>{busy === "auto" ? <Loader2 className="animate-spin" /> : null}{selectedListing.autoFulfillmentEnabled ? "关闭自动发卡" : "开启自动发卡"}</Button>}
        </div>
        {!autoGateReady && <p className="mt-2 text-xs text-amber-800 dark:text-amber-200">当前不满足开启条件：需要商品交付方式 AUTO、库存模式 CARD_KEY、已验证 FISH_SHOP 账号、已绑定闲鱼 item；规格商品还必须先完成规格映射。</p>}
      </div>}

      {selectedAccount && specEnabled && <div className="mb-5 rounded-card border border-border-subtle bg-surface-muted/45 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">规格映射</h3>
            <p className="mt-1 text-xs text-muted-foreground">按规格名称严格匹配闲鱼真实规格；没有 external SKU ID 时，完整 provider mapping 仍可作为正式 identity。</p>
          </div>
          {selectedListing && <Button type="button" variant="outline" size="sm" onClick={() => void handleSyncSpecMappings()} disabled={!canOperate || !currentItemId}>{busy === "mapping" ? <Loader2 className="animate-spin" /> : <RefreshCw />}{busy === "mapping" ? "同步中…" : "同步闲鱼规格映射"}</Button>}
        </div>
        {specMappings.length > 0 ? <div className="mt-3 space-y-2">
          {specMappings.map((mapping) => {
            const mapped = Boolean(mapping.externalSkuId?.trim() || (mapping.providerSpecName?.trim() && mapping.providerSpecValue?.trim()))
            return <div key={mapping.productSpecId} className="flex flex-wrap items-center justify-between gap-2 rounded-control border border-border-subtle bg-surface px-3 py-2 text-sm">
              <span className="font-medium">{mapping.productSpecName}</span>
              <span className={mapped ? "text-emerald-700 dark:text-emerald-300" : "text-amber-800 dark:text-amber-200"}>{mapped ? "已映射" : "未映射"}</span>
              {mapped && <span className="basis-full text-xs text-muted-foreground">{mapping.providerSpecName && mapping.providerSpecValue ? `${mapping.providerSpecName} = ${mapping.providerSpecValue}` : "provider mapping"}{mapping.externalSkuId ? ` · SKU ${mapping.externalSkuId}` : ""}</span>}
            </div>
          })}
        </div> : <p className="mt-3 text-xs text-amber-800 dark:text-amber-200">尚未读取闲鱼规格映射。完成映射后才能开启自动发卡。</p>}
      </div>}

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
        <div className={`${labelClass} lg:col-span-2`}>
          <span>宝贝所在地</span>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              value={addressKeyword}
              onChange={(event) => {
                setAddressKeyword(event.target.value)
                setAddressTips([])
                setAddressError("")
              }}
              onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); void handleAddressSearch() } }}
              disabled={noSelection || disabled || Boolean(busy) || addressBusy}
              placeholder="搜索城市、区或 POI 名称"
              aria-label="搜索宝贝所在地"
            />
            <Button type="button" variant="outline" onClick={() => void handleAddressSearch()} disabled={noSelection || disabled || Boolean(busy) || addressBusy} className="shrink-0">
              {addressBusy ? <Loader2 className="animate-spin" /> : <MapPin />}
              {selectedAddressTip || config.address ? "重新搜索" : "搜索所在地"}
            </Button>
          </div>
          <div className="rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2 text-sm" aria-live="polite">
            <span className="text-muted-foreground">当前选择：</span>
            <span className="font-medium">{selectedAddressTip
              ? [selectedAddressTip.name, [selectedAddressTip.district, selectedAddressTip.address].filter(Boolean).join("/")].filter(Boolean).join(" · ")
              : config.address || "尚未选择所在地"}</span>
            {config.address && !selectedAddressTip && <span className="ml-2 text-xs font-normal text-muted-foreground">历史地址关键词</span>}
          </div>
          {addressError && <p className="text-xs font-normal text-destructive" role="alert">{addressError}</p>}
          {addressTips.length > 0 && <div className="max-h-64 overflow-y-auto rounded-card border border-border-subtle" role="list" aria-label="所在地搜索结果">
            {addressTips.map((tip, index) => <button
              key={tip.id || `${tip.searchKeyword || addressKeyword}-${index}`}
              type="button"
              role="listitem"
              onClick={() => chooseAddress(tip)}
              disabled={!tip.searchKeyword || Boolean(busy) || disabled}
              className="flex w-full flex-col gap-0.5 border-b border-border-subtle px-3 py-2.5 text-left last:border-b-0 hover:bg-surface-muted/60 disabled:opacity-60"
            >
              <span className="text-sm font-semibold">{tip.name || tip.searchKeyword || "未命名地点"}{tip.district ? <span className="ml-2 font-normal text-muted-foreground">{tip.district}</span> : null}</span>
              <span className="text-xs font-normal text-muted-foreground">{tip.address || "暂无详细地址"}</span>
            </button>)}
          </div>}
        </div>
        <section className="flex min-w-0 flex-col gap-3 lg:col-span-2" aria-labelledby="xianyu-category-heading">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 id="xianyu-category-heading" className="text-sm font-semibold">闲鱼分类</h3>
              <p className="mt-1 text-xs font-normal text-muted-foreground">根据最终发布标题和描述获取真实分类，再由 Agent 加载该分类的动态属性。</p>
            </div>
            <Button type="button" variant="outline" onClick={() => void handleRecommendCategory()} disabled={noSelection || disabled || Boolean(busy) || categoryBusy || !finalTitle}>
              {categoryBusy ? <Loader2 className="animate-spin" /> : <Sparkles />}
              智能推荐分类
            </Button>
          </div>
          <div className="rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2 text-sm" aria-live="polite">
            <span className="text-muted-foreground">当前分类：</span>
            <span className="font-medium">{config.platformCategoryName || config.platformChannelCategoryName || "尚未选择分类"}</span>
            {categoryCandidates.length > 0 && <span className="ml-2 text-xs font-normal text-muted-foreground">选择分类候选以加载属性</span>}
          </div>
          <p className="text-xs font-normal text-muted-foreground">用于推荐的标题：{finalTitle || "未填写"}</p>
          {categoryError && <p className="text-xs font-normal text-destructive" role="alert">{categoryError}</p>}
          {categoryCandidates.length > 0 && <div className="grid gap-2 sm:grid-cols-2" aria-label="闲鱼分类候选">
            {categoryCandidates.map((candidate, index) => {
              const view = normalizeXianyuCategoryCandidate(candidate, index)
              const selected = selectedCategoryKey === view.key
              const highlighted = !selected && highlightedCategoryKey === view.key
              return <button
                key={view.key}
                type="button"
                disabled={!view.canPublish || noSelection || disabled || Boolean(busy) || categoryBusy}
                onClick={() => void handleSelectCategory(candidate, index)}
                className={`flex min-w-0 flex-col gap-1 rounded-card border p-3 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-55 ${selected ? "border-primary bg-primary/5" : highlighted ? "border-primary/60 bg-surface-muted/45" : "border-border-subtle hover:bg-surface-muted/45"}`}
                aria-pressed={selected}
              >
                <span className="font-medium">{view.pathLabel}{view.isSelected ? <span className="ml-2 text-xs text-primary">平台推荐</span> : null}</span>
                <span className="text-xs text-muted-foreground">{view.canPublish ? "可用于发布" : `不可发布：缺少 ${view.incompleteFields.join("、")}`}</span>
              </button>
            })}
          </div>}
          {categoryProperties.length > 0 && <div className="grid gap-4 rounded-card border border-border-subtle p-4 sm:grid-cols-2" aria-label="分类动态属性">
            {categoryProperties.filter((property) => property.propertyId && property.propertyName).map((property) => {
              const propertyId = property.propertyId || ""
              const options = (property.options || []).filter((option) => {
                const channelMatches = !option.channelCatId || !config.platformChannelCategoryId || option.channelCatId === config.platformChannelCategoryId
                const tbMatches = !option.tbCatId || !config.platformTbCategoryId || option.tbCatId === config.platformTbCategoryId
                return channelMatches && tbMatches
              })
              const current = (config.platformAttributes || []).filter((attribute) => {
                const record = attribute as XianyuPlatformAttribute & { property_id?: string | null }
                return (record.propertyId || record.property_id) === propertyId
              })
              const selectedValues = current.map((attribute) => attribute.valueId || attribute.valueName || attribute.text || "")
              const placeholder = property.inputWord || `请输入${property.propertyName}`
              if (!options.length) {
                return <label key={propertyId} className={labelClass}>
                  {property.propertyName}
                  <Input
                    maxLength={200}
                    value={current[0]?.text || current[0]?.valueName || ""}
                    onChange={(event) => updatePropertyAttributes(property, [], event.target.value)}
                    disabled={noSelection || disabled || Boolean(busy) || categoryBusy}
                    placeholder={placeholder}
                  />
                </label>
              }
              if (property.isMultiple) {
                return <fieldset key={propertyId} className="flex min-w-0 flex-col gap-2 text-sm sm:col-span-2">
                  <legend className="mb-1 font-medium">{property.propertyName}</legend>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {options.map((option, optionIndex) => {
                      const optionKey = option.valueId || option.valueName || `${propertyId}-${optionIndex}`
                      const checked = selectedValues.includes(option.valueId || option.valueName || "")
                      return <label key={optionKey} className="flex items-center gap-2 text-sm font-normal">
                        <input
                          type="checkbox"
                          className="size-4 accent-primary"
                          checked={checked}
                          disabled={noSelection || disabled || Boolean(busy) || categoryBusy}
                          onChange={() => {
                            const next = checked
                              ? current.filter((attribute) => (attribute.valueId || attribute.valueName || attribute.text || "") !== (option.valueId || option.valueName || ""))
                              : [...current, { propertyId, propertyName: property.propertyName || propertyId, valueId: option.valueId || null, valueName: option.valueName || "", text: option.valueName || "", properties: null }]
                            updatePropertyAttributes(property, next.map((attribute) => ({ valueId: attribute.valueId, valueName: attribute.valueName })))
                          }}
                        />
                        {option.valueName}
                      </label>
                    })}
                  </div>
                </fieldset>
              }
              return <label key={propertyId} className={labelClass}>
                {property.propertyName}
                <select
                  className={selectClass}
                  value={selectedValues[0] || ""}
                  onChange={(event) => {
                    const option = options.find((entry) => (entry.valueId || entry.valueName || "") === event.target.value)
                    updatePropertyAttributes(property, option ? [option] : [])
                  }}
                  disabled={noSelection || disabled || Boolean(busy) || categoryBusy}
                >
                  <option value="">请选择{property.propertyName}</option>
                  {options.map((option, optionIndex) => <option key={option.valueId || `${propertyId}-${optionIndex}`} value={option.valueId || option.valueName || ""}>{option.valueName}</option>)}
                </select>
              </label>
            })}
          </div>}
          {selectedCategoryKey && categoryProperties.length === 0 && <p className="text-xs text-muted-foreground">此分类没有返回动态属性。</p>}
        </section>
        <label className={labelClass}>运输方式<Input value={config.shippingMethod || ""} onChange={(event) => setText("shippingMethod", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>交付方式<Input value={config.deliveryMethod || ""} onChange={(event) => setText("deliveryMethod", event.target.value)} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>邮费<Input type="number" min="0" step="0.01" value={config.postage ?? ""} onChange={(event) => patchConfig("postage", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>
        <label className={labelClass}>无限库存同步数量<Input type="number" min="0" step="1" value={config.unlimitedStockQuantity ?? 999} onChange={(event) => patchConfig("unlimitedStockQuantity", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>
        <div className="grid gap-1">
          <label className={labelClass}>闲鱼单规格库存上限<Input type="number" min="0" max="999999" step="1" value={config.stockCapPerSku ?? ""} onChange={(event) => patchConfig("stockCapPerSku", optionalNumber(event.target.value))} disabled={noSelection || Boolean(busy)} /></label>
          <p className="text-xs text-muted-foreground">仅限制同步到闲鱼的单个规格库存，不影响商城真实库存；留空表示完全跟随商城库存，设为 0 表示闲鱼库存同步为 0。</p>
        </div>
        <label className="flex items-center gap-2 text-sm font-medium lg:col-span-2"><input type="checkbox" checked={Boolean(config.supportPickup)} onChange={(event) => patchConfig("supportPickup", event.target.checked)} disabled={noSelection || Boolean(busy)} className="size-4 accent-primary" />支持自提</label>
      </div>

      <details className="mt-5 rounded-card border border-border-subtle p-4">
        <summary className="cursor-pointer text-sm font-semibold">分类标识（只读）</summary>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <ReadOnlyCategoryValue label="catId" value={config.platformCategoryId} />
          <ReadOnlyCategoryValue label="channelCatId" value={config.platformChannelCategoryId} />
          <ReadOnlyCategoryValue label="tbCatId" value={config.platformTbCategoryId} />
          <ReadOnlyCategoryValue label="leafId" value={config.platformLeafId} />
        </div>
      </details>

      <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border-subtle pt-4">
        <Button type="button" variant="outline" onClick={() => void handleSave()} disabled={!canOperate || noSelection}><CheckCircle2 />保存配置</Button>
        <Button type="button" onClick={() => void handleEnable()} disabled={!canProviderOperate || noSelection}>{busy === "enable" ? <Loader2 className="animate-spin" /> : null}{selectedListing?.isEnabled ? "启用并同步" : "发布到闲鱼 / 启用"}</Button>
        {selectedListing?.isEnabled && <Button type="button" variant="outline" onClick={() => void handleDisable()} disabled={!canProviderOperate}>{busy === "disable" ? <Loader2 className="animate-spin" /> : null}停止闲鱼销售</Button>}
        {selectedListing && <Button type="button" variant="outline" onClick={() => void handleRetry()} disabled={!canProviderOperate}>{busy === "retry" ? <Loader2 className="animate-spin" /> : null}重试</Button>}
        {disabled && <span className="text-xs text-muted-foreground">请先保存当前商品编辑，再执行渠道操作。</span>}
        {busy && busy !== "load" && <span className="text-xs text-muted-foreground" role="status">{busyLabel}</span>}
      </div>
    </FormSection>
  )
}
