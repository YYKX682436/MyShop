"use client"

import { useCallback, useEffect, useRef, useState } from "react"
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
  applyQrLoginStatus,
  buildQrLoginPollCommand,
  isQrLoginComplete,
  isQrLoginTerminal,
  type XianyuQrLoginUiState,
} from "@/lib/xianyu-qr-login"
import {
  adminSalesChannelApi,
  requestOrThrow,
  type SalesChannelAccountSummary,
  type XianyuQrLoginStatusResponse,
} from "@/services/api"

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

function authStateLabel(state?: string | null) {
  if (state === "VALID") return "登录正常"
  if (state === "AUTH_REQUIRED") return "需要重新登录"
  if (state === "VERIFICATION_REQUIRED") return "需要安全验证"
  return "认证状态未知"
}

function authStateTone(state?: string | null): "success" | "warning" | "info" {
  if (state === "VALID") return "success"
  if (state === "AUTH_REQUIRED" || state === "VERIFICATION_REQUIRED") return "warning"
  return "info"
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
  const [reauthenticatingId, setReauthenticatingId] = useState<string | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)
  const [login, setLogin] = useState<XianyuQrLoginUiState | null>(null)
  const loginRef = useRef(login)

  useEffect(() => {
    loginRef.current = login
  }, [login])

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
    const loginIntentId = crypto.randomUUID()
    setLogin({ status: "INITIALIZING", mode: "ADD_ACCOUNT", loginIntentId })
    try {
      const started = await requestOrThrow(() =>
        adminSalesChannelApi.startXianyuQrLogin(loginIntentId, crypto.randomUUID()),
      )
      setLogin({
        status: "WAITING",
        mode: "ADD_ACCOUNT",
        loginIntentId,
        sessionId: started.loginSessionId,
        qrCodeDataUrl: started.qrCodeDataUrl,
      })
    } catch (error) {
      setLogin({ status: "FAILED", mode: "ADD_ACCOUNT", loginIntentId })
      toast.error(error instanceof Error ? error.message : "生成闲鱼登录二维码失败")
    }
  }

  const startReauthentication = async (account: SalesChannelAccountSummary) => {
    setReauthenticatingId(account.id)
    setLoginOpen(true)
    const loginIntentId = crypto.randomUUID()
    setLogin({
      status: "INITIALIZING",
      mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
      loginIntentId,
      targetAccountRowId: account.id,
      targetProviderAccountId: account.providerAccountId,
    })
    try {
      const started = await requestOrThrow(() =>
        adminSalesChannelApi.startXianyuQrReauthentication(account.id, loginIntentId, crypto.randomUUID()),
      )
      setLogin({
        status: "WAITING",
        mode: "REAUTHENTICATE_EXISTING_ACCOUNT",
        loginIntentId,
        targetAccountRowId: account.id,
        targetProviderAccountId: account.providerAccountId,
        sessionId: started.loginSessionId,
        qrCodeDataUrl: started.qrCodeDataUrl,
      })
    } catch (error) {
      setLogin((current) => current ? { ...current, status: "FAILED" } : current)
      toast.error(error instanceof Error ? error.message : "生成账号重新登录二维码失败")
    } finally {
      setReauthenticatingId(null)
    }
  }

  const closeLogin = () => {
    setLoginOpen(false)
    setLogin(null)
  }

  useEffect(() => {
    const initialLogin = loginRef.current
    if (!loginOpen || !initialLogin?.sessionId || !initialLogin.loginIntentId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const poll = async () => {
      try {
        const currentLogin = loginRef.current
        if (!currentLogin || currentLogin.loginIntentId !== initialLogin.loginIntentId) return
        const command = buildQrLoginPollCommand(currentLogin, crypto.randomUUID())
        const status = await requestOrThrow(() => command.mode === "REAUTHENTICATE_EXISTING_ACCOUNT"
          ? adminSalesChannelApi.getXianyuQrReauthenticationStatus(
              command.targetAccountRowId!, command.loginSessionId, command.loginIntentId, command.requestId,
            )
          : adminSalesChannelApi.getXianyuQrLoginStatus(
              command.loginSessionId, command.loginIntentId, command.requestId,
            ))
        if (stopped) return
        const next = applyQrLoginStatus(currentLogin, status)
        setLogin((current) => current ? applyQrLoginStatus(current, status) : current)
        if (isQrLoginComplete(next.status as XianyuQrLoginStatusResponse["status"])) {
          await loadAccounts()
          return
        }
        if (!isQrLoginTerminal(next.status as XianyuQrLoginStatusResponse["status"])) {
          timer = setTimeout(() => void poll(), 2000)
        }
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
  }, [loginOpen, login?.sessionId, login?.loginIntentId, login?.mode, login?.targetAccountRowId, loadAccounts])

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
              <th className="px-4 py-3 text-left">登录状态</th>
              <th className="px-4 py-3 text-left">账号类型</th>
              <th className="px-4 py-3 text-left">最近登录</th>
              <th className="px-4 py-3 text-left">最近验证</th>
              <th className="px-4 py-3 text-left">能力</th>
              <th className="px-4 py-3 text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && <TableLoadingState colSpan={9} />}
            {!loading && loadError && <TableErrorState colSpan={9} onRetry={() => void loadAccounts()} />}
            {!loading && !loadError && accounts.length === 0 && <TableEmptyState colSpan={9}>还没有添加闲鱼账号</TableEmptyState>}
            {!loading && !loadError && accounts.map((account) => {
              const capabilities = capabilityLabels(account.capabilities)
              return (
                <tr key={account.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                  <td className="px-4 py-3 font-mono text-xs">{account.providerAccountId}</td>
                  <td className="px-4 py-3">{account.displayName || "—"}</td>
                  <td className="px-4 py-3">
                    <StatusBadge label={account.status} tone={statusTone(account.status)} />
                    {account.agentOperationalStatus && <div className="mt-1 text-xs text-muted-foreground">
                      Agent：{account.agentOperationalStatus}{account.statusSource ? ` · ${account.statusSource}` : ""}
                    </div>}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge label={authStateLabel(account.authState)} tone={authStateTone(account.authState)} />
                    <div className="mt-1 text-xs text-muted-foreground">
                      {account.authStateSource || "来源未知"}
                      {account.authStateObservedAt ? ` · ${formatDateTime(account.authStateObservedAt, locale)}` : ""}
                    </div>
                  </td>
                  <td className="px-4 py-3">{accountTypeLabel(account.accountType)}</td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                    <div>{account.lastLoginAt ? formatDateTime(account.lastLoginAt, locale) : "—"}</div>
                    <div className="mt-1 text-xs">最近刷新：{account.lastRefreshAt ? formatDateTime(account.lastRefreshAt, locale) : "—"}</div>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted-foreground">
                    {account.lastVerifiedAt ? formatDateTime(account.lastVerifiedAt, locale) : "—"}
                  </td>
                  <td className="px-4 py-3">
                    {capabilities.length > 0 ? capabilities.join(" · ") : <span className="text-muted-foreground">未验证</span>}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex justify-end gap-2">
                      <Button type="button" variant="outline" size="sm"
                        disabled={reauthenticatingId === account.id}
                        onClick={() => void startReauthentication(account)}>
                        {reauthenticatingId === account.id ? "生成中" : "重新登录"}
                      </Button>
                      <Button type="button" variant="outline" size="sm" disabled={verifyingId === account.id} onClick={() => void verifyAccount(account)}>
                        <ShieldCheck className="size-4" />
                        {verifyingId === account.id ? "验证中" : "验证能力"}
                      </Button>
                    </div>
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
                <h2 id="xianyu-login-title" className="text-lg font-semibold">
                  {login.mode === "ADD_ACCOUNT" ? "添加闲鱼账号" : "重新登录闲鱼账号"}
                </h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  {login.mode === "ADD_ACCOUNT"
                    ? "使用闲鱼手机客户端扫码并确认登录。"
                    : `目标账号 ${login.targetProviderAccountId} 固定；请使用该账号的闲鱼 App 扫码确认。`}
                </p>
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
              {login.status === "VERIFICATION_REQUIRED" && <>
                {login.verificationQrCodeDataUrl ? <>
                  <Image src={login.verificationQrCodeDataUrl} alt="闲鱼人脸验证二维码" width={208} height={208} unoptimized className="size-52 rounded-card bg-white p-2" />
                  <p className="max-w-xs text-sm font-medium">请用闲鱼 App 再次扫码，并按手机提示完成人脸或安全验证。</p>
                </> : <>
                  <p className="max-w-xs text-sm font-medium">{login.verificationMessage || defaultVerificationMessage}</p>
                  <p className="text-xs text-muted-foreground">正在获取人脸验证二维码…</p>
                </>}
              </>}
              {login.status === "QR_SCAN_CONFIRMED" && <p className="text-sm font-medium">扫码已确认，正在核对目标账号身份…</p>}
              {login.status === "COOKIE_PERSISTED" && <div><StatusBadge label="Cookie 已保存" tone="warning" /><p className="mt-3 text-sm text-muted-foreground">正在通过只读账号能力接口验证登录状态…</p></div>}
              {login.status === "PROVIDER_AUTH_VERIFIED" && <div><StatusBadge label="登录与能力验证通过" tone="success" /><p className="mt-3 text-sm text-muted-foreground">账号列表已刷新。</p></div>}
              {login.status === "PROVIDER_AUTH_VERIFICATION_FAILED" && <div><StatusBadge label="能力验证失败" tone="danger" /><p className="mt-3 text-sm text-destructive">扫码确认且 Cookie 已保存，但账号认证尚未验证通过。</p>{login.errorCode && <p className="mt-2 text-xs font-mono text-destructive">{login.errorCode}</p>}</div>}
              {login.status === "EXPIRED" && <p className="text-sm">二维码已过期，请重新生成。</p>}
              {login.status === "CANCELLED" && <p className="text-sm">本次登录已取消。</p>}
              {login.status === "FAILED" && <div><p className="text-sm text-destructive">登录未完成，账号 Cookie 未被确认更新。</p>{login.errorCode && <p className="mt-2 text-xs font-mono text-destructive">{login.errorCode}</p>}</div>}
            </div>

            <div className="mt-4 flex justify-end gap-2">
              {(login.status === "EXPIRED" || login.status === "FAILED") && <Button type="button" variant="outline" onClick={() => {
                if (login.mode === "ADD_ACCOUNT") void startLogin()
                else {
                  const account = accounts.find((item) => item.id === login.targetAccountRowId)
                  if (account) void startReauthentication(account)
                }
              }}>重新生成二维码</Button>}
              <Button type="button" variant="outline" onClick={closeLogin}>{login.status === "PROVIDER_AUTH_VERIFIED" ? "完成" : "关闭"}</Button>
            </div>
          </section>
        </div>
      )}
    </AdminPage>
  )
}
