"use client"

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { Copy, ExternalLink, Loader2 } from "lucide-react"
import { QRCodeSVG } from "qrcode.react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { resolvePaymentAction, type PaymentActionEnvironment } from "@/lib/payment-action"
import { executePaymentAction, isExecutablePaymentAction, type ExecutablePaymentAction } from "@/lib/payment-action-executor"
import { getPaymentActionEnvironment } from "@/lib/payment-entry"
import type { PaymentPageLoadPhase } from "@/lib/payment-page-state"
import type { PaymentNextAction } from "@/types"

interface PaymentActionRendererProps {
  action: PaymentNextAction | null | undefined
  loadState: PaymentPageLoadPhase
  statusError?: string | null
  retryAction?: ReactNode
  retryStatusAction?: ReactNode
}

const EXECUTION_FALLBACK_DELAY = 1600

function clientLabel(client: "wechat" | "alipay", t: (key: TranslationKey) => string): string {
  return client === "wechat" ? t("payment.wechat") : t("payment.alipay")
}

function openClientLabel(client: "wechat" | "alipay", t: (key: TranslationKey) => string): string {
  return client === "wechat" ? t("payment.openWechat") : t("payment.openAlipay")
}

function scanClientHint(client: "wechat" | "alipay", t: (key: TranslationKey) => string): string {
  return client === "wechat" ? t("payment.scanOpenWechat") : t("payment.scanOpenAlipay")
}

const FALLBACK_ENVIRONMENT: PaymentActionEnvironment = {
  isMobile: false,
  isWechatBrowser: false,
  isAlipayBrowser: false,
}

function QrSurface({ children }: { children: ReactNode }) {
  return <div className="flex min-h-52 w-52 items-center justify-center rounded-xl bg-white p-3">
    {children}
  </div>
}

export function PaymentActionRenderer({
  action,
  loadState,
  statusError,
  retryAction,
  retryStatusAction,
}: PaymentActionRendererProps) {
  const { t } = useLocale()
  const [environment, setEnvironment] = useState<PaymentActionEnvironment | null>(null)
  const [autoExecutionPending, setAutoExecutionPending] = useState(false)
  const executedRef = useRef<string>("")

  useEffect(() => {
    setEnvironment(getPaymentActionEnvironment())
  }, [])

  const needsEnvironment = action?.type === "open_in_client" || action?.type === "deep_link"
  const waitingForEnvironment = Boolean(action && needsEnvironment && !environment)
  const decision = useMemo(() => {
    if (!action || waitingForEnvironment) return null
    try {
      return resolvePaymentAction(action, environment ?? FALLBACK_ENVIRONMENT) ?? null
    } catch {
      return null
    }
  }, [action, environment, waitingForEnvironment])

  const triggerExecution = useCallback((executable: ExecutablePaymentAction) => {
    setAutoExecutionPending(true)
    try {
      executePaymentAction(executable)
    } catch (error) {
      setAutoExecutionPending(false)
      toast.error(error instanceof Error ? error.message : t("common.error"))
      return
    }
    window.setTimeout(() => setAutoExecutionPending(false), EXECUTION_FALLBACK_DELAY)
  }, [t])

  useEffect(() => {
    if (!decision || !isExecutablePaymentAction(decision)) return
    const signature = JSON.stringify(decision)
    if (executedRef.current === signature) return
    executedRef.current = signature
    triggerExecution(decision)
  }, [decision, triggerExecution])

  const copy = async (value: string) => {
    try {
      if (!navigator.clipboard?.writeText) {
        toast.error(t("payment.copyFailed"))
        return
      }
      await navigator.clipboard.writeText(value)
      toast.success(t("payment.linkCopied"))
    } catch {
      toast.error(t("payment.copyFailed"))
    }
  }

  const retryExecution = () => {
    if (decision && isExecutablePaymentAction(decision)) triggerExecution(decision)
  }

  if (!action) {
    if (loadState === "loading") {
      return <div className="flex flex-col items-center gap-2 py-4 text-center" role="status" aria-live="polite">
        <Loader2 className="h-8 w-8 animate-spin text-white" aria-label={t("common.loading")} />
        <span className="text-sm text-white/90">{t("common.loading")}</span>
      </div>
    }
    if (loadState === "error" || statusError) {
      return <div className="flex flex-col items-center gap-3 px-3 text-center" role="alert">
        <p className="text-sm text-destructive">{statusError || t("payment.loadFailed")}</p>
        {retryStatusAction || retryAction}
      </div>
    }
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="alert">
      <p className="text-sm text-muted-foreground">{t("payment.noAction")}</p>
      {retryAction}
    </div>
  }

  if (waitingForEnvironment) {
    return <div className="flex flex-col items-center gap-2 py-4 text-center" role="status" aria-live="polite">
      <Loader2 className="h-8 w-8 animate-spin text-white" aria-label={t("common.loading")} />
      <span className="text-sm text-white/90">{t("common.loading")}</span>
    </div>
  }

  if (!decision) {
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="alert">
      <p className="text-sm text-white">{statusError || t("payment.loadFailed")}</p>
      {retryStatusAction || retryAction}
    </div>
  }

  if (decision.kind === "qr_code") {
    return <QrSurface>
      <QRCodeSVG value={decision.value} size={184} aria-label={t("payment.scanQrCode")} />
    </QrSurface>
  }

  if (decision.kind === "redirect") {
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
      <p className="text-sm text-muted-foreground">
        {autoExecutionPending ? t("payment.redirectingToPay") : t("payment.actionFallback")}
      </p>
      <a href={decision.url} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
        {t("payment.continuePay")} <ExternalLink className="h-3.5 w-3.5" />
      </a>
    </div>
  }

  if (decision.kind === "open_in_client") {
    if (decision.mode === "qr_code") {
      return <QrSurface>
        <div className="flex flex-col items-center gap-2 text-center">
          <QRCodeSVG value={decision.url} size={184} aria-label={scanClientHint(decision.client, t)} />
          <p className="text-xs text-muted-foreground">{scanClientHint(decision.client, t)}</p>
        </div>
      </QrSurface>
    }
    if (decision.mode === "copy") {
      return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
        <p className="text-sm text-muted-foreground">{t("payment.openInClientHint")} · {clientLabel(decision.client, t)}</p>
        <button type="button" onClick={() => void copy(decision.url)} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
          <Copy className="h-3.5 w-3.5" />{t("payment.copyClientLink")}
        </button>
      </div>
    }
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
      <p className="text-sm text-muted-foreground">
        {autoExecutionPending ? t("payment.openingClient") : t("payment.actionFallback")} · {clientLabel(decision.client, t)}
      </p>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <a href={decision.url} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
          {openClientLabel(decision.client, t)} <ExternalLink className="h-3.5 w-3.5" />
        </a>
        <button type="button" onClick={() => void copy(decision.url)} className="inline-flex items-center gap-1.5 rounded-control border border-border-strong px-4 py-2 text-sm font-medium text-foreground">
          <Copy className="h-3.5 w-3.5" />{t("payment.copyClientLink")}
        </button>
      </div>
    </div>
  }

  if (decision.kind === "deep_link") {
    if (decision.mode === "qr_code") {
      return <QrSurface>
        <div className="flex flex-col items-center gap-2 text-center">
          <QRCodeSVG value={decision.url} size={184} aria-label={scanClientHint(decision.client, t)} />
          <p className="text-xs text-muted-foreground">{scanClientHint(decision.client, t)}</p>
        </div>
      </QrSurface>
    }
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
      <p className="text-sm text-muted-foreground">
        {autoExecutionPending ? t("payment.openingClient") : t("payment.actionFallback")} · {clientLabel(decision.client, t)}
      </p>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <a href={decision.url} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
          {openClientLabel(decision.client, t)} <ExternalLink className="h-3.5 w-3.5" />
        </a>
        <button type="button" onClick={() => void copy(decision.url)} className="inline-flex items-center gap-1.5 rounded-control border border-border-strong px-4 py-2 text-sm font-medium text-foreground">
          <Copy className="h-3.5 w-3.5" />{t("payment.copyClientLink")}
        </button>
      </div>
    </div>
  }

  return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
    <p className="text-sm text-muted-foreground">
      {autoExecutionPending ? t("payment.submittingForm") : t("payment.actionFallback")}
    </p>
    <button type="button" onClick={retryExecution} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
      {t("payment.continuePay")} <ExternalLink className="h-3.5 w-3.5" />
    </button>
  </div>
}
