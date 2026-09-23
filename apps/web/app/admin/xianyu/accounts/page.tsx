"use client"

import { useCallback, useEffect, useState } from "react"
import Image from "next/image"
import { RefreshCw, ShieldCheck, X } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  DataTableShell,
  PageHeader,
  PageToolbar,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import {
  adminSalesChannelApi,
  requestOrThrow,
  type SalesChannelAccountSummary,
  type XianyuQrLoginStatusResponse,
} from "@/services/api"

type LoginStatus = "INITIALIZING" | XianyuQrLoginStatusResponse["status"]

interface LoginSession {
  status: LoginStatus
  sessionId?: string
  qrCodeDataUrl?: string
  verificationMessage?: string
}

const terminalStatuses = new Set<LoginStatus>(["SUCCESS", "EXPIRED", "CANCELLED", "FAILED"])
const defaultVerificationMessage = "闲鱼要求进行安全验证，请按手机端提示完成验证。"

function statusTone(status: string): "success" | "warning" | "danger" | "info" | "neutral" {
  if (status === "CONNECTED" || status === "active" || status === "ACTIVE") return "success"
  if (status === "AUTH_REQUIRED" || status === "DISCONNECTED") return "warning"
  if (status === "FAILED" || status === "SUSPENDED" || status === "DISABLED") return "danger"
  return "info"
}

function accountTypeLabel(accountType?: string | null) {
  if (accountType === "FISH_SHOP") return "鱼小铺"
  if (accountType === "PERSONAL") return "个人账号"
  return "待验证"
}

function capabilityLabels(capabilities?: SalesChannelAccountSummary["capabilities"]) {
  if (!capabilities) return []
  const labels: Record<string, string> = {
    publish: "发布",
    edit: "编辑",
    stock_sync: "库存同步",
    sku: "规格",
    video: "视频",
  }
  return Object.entries(labels)
    .filter(([key]) => capabilities[key] === true)
    .map(([, label]) => label)
}

export default function XianyuAccountsPage() {
  const { locale } = useLocale()
  const [accounts, setAccounts] = useState<SalesChannelAccountSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [verifyingId, setVerifyingId] = useState<string | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)
  const [login, setLogin] = useState<LoginSession | null>(null)

  const loadAccounts = useCallback(async () => {
    setLoading(true)
    setLoadError(false)
    try {
      setAccounts(await requestOrThrow(() => adminSalesChannelApi.getXianyuAccounts()))
    } catch {
      setAccounts([])
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadAccounts()
  }, [loadAccounts])

  const startLogin = async () => {
    setLoginOpen(true)
    setLogin({ status: "INITIALIZING" })
    try {
      const started = await requestOrThrow(() => adminSalesChannelApi.startXianyuQrLogin(crypto.randomUUID()))
      setLogin({
        status: "WAITING",
        sessionId: started.loginSessionId,
        qrCodeDataUrl: started.qrCodeDataUrl,
      })
    } catch (error) {
      setLogin({ status: "FAILED" })
      toast.error(error instanceof Error ? error.message : "生成闲鱼登录二维码失败")
    }
  }

  const closeLogin = () => {
    setLoginOpen(false)
    setLogin(null)
  }

  useEffect(() => {
    if (!loginOpen || !login?.sessionId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const poll = async () => {
      try {
        const status = await requestOrThrow(() =>
          adminSalesChannelApi.getXianyuQrLoginStatus(login.sessionId!, crypto.randomUUID()),
        )
        if (stopped) return
        setLogin((current) => current ? {
          ...current,
          status: status.status,
          verificationMessage: status.verificationMessage || undefined,
        } : current)
        if (status.status === "SUCCESS") {
          await loadAccounts()
          return
        }
        if (!terminalStatuses.has(status.status)) timer = setTimeout(() => void poll(), 2000)
      } catch {
        if (stopped) return
        setLogin((current) => current ? { ...current, status: "FAILED" } : current)
      }
    }

    void poll()
    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
    }
  }, [loginOpen, login?.sessionId, loadAccounts])

  const verifyAccount = async (account: SalesChannelAccountSummary) => {
    setVerifyingId(account.id)
    try {
      await requestOrThrow(() => adminSalesChannelApi.verifyAccount(account.id))
      toast.success("账号能力已更新")
      await loadAccounts()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "账号能力验证失败")
    } finally {
      setVerifyingId(null)
    }
  }

  return (
    <AdminPage>
      <PageHeader title="闲鱼账号" description="在 MyShop 管理闲鱼账号登录状态与已验证能力。" />
      <PageToolbar>
        <div className="flex w-full flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-muted-foreground">账号状态由 MyShop 管理；闲鱼 Cookie 和登录凭据仅保存在 Xianyu Agent，不进入 MyShop 或浏览器。</p>
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={() => void loadAccounts()} disabled={loading}>
              <RefreshCw className={loading ? "animate-spin" : ""} />刷新
            </Button>
            <Button type="button" onClick={() => void startLogin()}>添加闲鱼账号</Button>
          </div>
        </div>
      </PageToolbar>

      <DataTableShell>
        <table className="w-full min-w-[1050px] text-sm">
          <thead>
            <tr className="border-b border-border-subtle bg-surface-muted">
              <th className="px-4 py-3 text-left">账号 ID</th>
              <th className="px-4 py-3 text-left">显示名称 / 备注</th>
              <th className="px-4 py-3 text-left">连接状态</th>
              <th className="px-4 py-3 text-left">账号类型</th>
              <th className="px-4 py-3 text-left">最近登录</th>
              <th className="px-4 py-3 text-left">最近验证</th>
              <th className="px-4 py-3 text-left">能力</th>
              <th className="px-4 py-3 text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && <TableLoadingState colSpan={8} />}
            {!loading && loadError && <TableErrorState colSpan={8} onRetry={() => void loadAccounts()} />}
            {!loading && !loadError && accounts.length === 0 && <TableEmptyState colSpan={8}>还没有添加闲鱼账号</TableEmptyState>}
            {!loading && !loadError && accounts.map((account) => {
              const capabilities = capabilityLabels(account.capabilities)
              return (
                <tr key={account.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                  <td className="px-4 py-3 font-mono text-xs">{account.providerAccountId}</td>
                  <td className="px-4 py-3">{account.displayName || "—"}</td>
                  <td className="px-4 py-3"><StatusBadge label={account.status} tone={statusTone(account.status)} /></td>
                  <td className="px-4 py-3">{accountTypeLabel(account.accountType)}</td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                    {account.lastLoginAt ? formatDateTime(account.lastLoginAt, locale) : "—"}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                    {account.lastVerifiedAt ? formatDateTime(account.lastVerifiedAt, locale) : "—"}
                  </td>
                  <td className="px-4 py-3">
                    {capabilities.length > 0 ? capabilities.join(" · ") : <span className="text-muted-foreground">未验证</span>}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Button type="button" variant="outline" size="sm" disabled={verifyingId === account.id} onClick={() => void verifyAccount(account)}>
                      <ShieldCheck className="size-4" />
                      {verifyingId === account.id ? "验证中" : "验证能力"}
                    </Button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </DataTableShell>

      {loginOpen && login && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 p-4" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) closeLogin()
        }}>
          <section className="w-full max-w-md rounded-surface border border-border-subtle bg-surface p-5 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="xianyu-login-title">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 id="xianyu-login-title" className="text-lg font-semibold">添加闲鱼账号</h2>
                <p className="mt-1 text-sm text-muted-foreground">使用闲鱼手机客户端扫码并确认登录。</p>
              </div>
              <Button type="button" variant="ghost" size="icon" aria-label="关闭" onClick={closeLogin}><X className="size-4" /></Button>
            </div>

            <div className="mt-5 flex min-h-64 flex-col items-center justify-center rounded-card border border-border-subtle bg-surface-muted/40 p-5 text-center">
              {login.status === "INITIALIZING" && <p className="text-sm text-muted-foreground">正在生成二维码…</p>}
              {login.status === "WAITING" && login.qrCodeDataUrl && <>
                <Image src={login.qrCodeDataUrl} alt="闲鱼登录二维码" width={208} height={208} unoptimized className="size-52 rounded-card bg-white p-2" />
                <p className="mt-3 text-sm text-muted-foreground">请打开闲鱼 App 扫码</p>
              </>}
              {login.status === "SCANNED" && <p className="text-sm font-medium">已扫码，请在手机上确认</p>}
              {login.status === "VERIFICATION_REQUIRED" && <p className="max-w-xs text-sm font-medium">{login.verificationMessage || defaultVerificationMessage}</p>}
              {login.status === "SUCCESS" && <div><StatusBadge label="登录成功" tone="success" /><p className="mt-3 text-sm text-muted-foreground">账号列表已刷新。</p>{login.verificationMessage && <p className="mt-2 text-xs text-muted-foreground">{login.verificationMessage}</p>}</div>}
              {login.status === "EXPIRED" && <p className="text-sm">二维码已过期，请重新生成。</p>}
              {login.status === "CANCELLED" && <p className="text-sm">本次登录已取消。</p>}
              {login.status === "FAILED" && <p className="text-sm text-destructive">登录未完成，请重试。</p>}
            </div>

            <div className="mt-4 flex justify-end gap-2">
              {(login.status === "EXPIRED" || login.status === "FAILED") && <Button type="button" variant="outline" onClick={() => void startLogin()}>重新生成二维码</Button>}
              <Button type="button" variant="outline" onClick={closeLogin}>{login.status === "SUCCESS" ? "完成" : "关闭"}</Button>
            </div>
          </section>
        </div>
      )}
    </AdminPage>
  )
}
