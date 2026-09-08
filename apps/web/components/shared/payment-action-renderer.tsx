"use client"

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { Copy, ExternalLink, Loader2 } from "lucide-react"
import { QRCodeSVG } from "qrcode.react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { isMobileDevice } from "@/lib/utils"
import { isAlipayBrowser, isWechatBrowser, resolvePaymentAction, type PaymentActionEnvironment } from "@/lib/payment-action"
import type { PaymentNextAction } from "@/types"

interface PaymentActionRendererProps {
  action: PaymentNextAction | null | undefined
  statusLoaded: boolean
  retryAction?: ReactNode
}

function clientLabel(client: "wechat" | "alipay", t: (key: TranslationKey) => string): string {
  return client === "wechat" ? t("payment.wechat") : t("payment.alipay")
}

export function PaymentActionRenderer({ action, statusLoaded, retryAction }: PaymentActionRendererProps) {
  const { t } = useLocale()
  const [environment, setEnvironment] = useState<PaymentActionEnvironment | null>(null)
  useEffect(() => {
    setEnvironment({
      isMobile: isMobileDevice(),
      isWechatBrowser: isWechatBrowser(),
      isAlipayBrowser: isAlipayBrowser(),
    })
  }, [])
  const decision = useMemo(
    () => action && environment ? resolvePaymentAction(action, environment) : null,
    [action, environment],
  )
  const executedRef = useRef<string>("")
  const [copying, setCopying] = useState(false)

  useEffect(() => {
    if (!action || !decision) return
    const signature = JSON.stringify(decision)
    if (executedRef.current === signature) return
    executedRef.current = signature

    if (decision.kind === "redirect" || decision.kind === "form_post"
      || (decision.kind === "open_in_client" && decision.mode === "direct")
      || (decision.kind === "deep_link" && decision.mode === "open")) {
      if (decision.kind === "form_post") {
        const form = document.createElement("form")
        form.method = "POST"
        form.action = decision.url
        Object.entries(decision.fields).forEach(([name, value]) => {
          const input = document.createElement("input")
          input.type = "hidden"
          input.name = name
          input.value = value
          form.appendChild(input)
        })
        document.body.appendChild(form)
        form.submit()
        form.remove()
      } else {
        window.location.assign(decision.url)
      }
    }
  }, [action, decision])

  const copy = async (value: string) => {
    if (copying) return
    setCopying(true)
    try {
      if (!navigator.clipboard?.writeText) {
        toast.error(t("payment.copyFailed"))
        return
      }
      await navigator.clipboard.writeText(value)
      toast.success(t("payment.linkCopied"))
    } catch {
      toast.error(t("payment.copyFailed"))
    } finally {
      setCopying(false)
    }
  }

  const needsEnvironment = action?.type === "open_in_client" || action?.type === "deep_link"
  if (!action) {
    return statusLoaded
      ? <div className="flex flex-col items-center gap-3 px-3 text-center" role="alert"><p className="text-sm text-muted-foreground">{t("payment.noAction")}</p>{retryAction}</div>
      : <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
  }
  if (!decision || (needsEnvironment && !environment)) {
    return <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
  }

  if (decision.kind === "qr_code") {
    return <QRCodeSVG value={decision.value} size={184} aria-label={t("payment.scanQrCode")} />
  }

  if (decision.kind === "redirect") {
    return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
      <p className="text-sm text-muted-foreground">{t("payment.redirectingToPay")}</p>
      <a href={decision.url} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">
        {t("payment.continuePay")} <ExternalLink className="h-3.5 w-3.5" />
      </a>
    </div>
  }

  if (decision.kind === "open_in_client") {
    if (decision.mode === "qr_code") {
      return <div className="flex flex-col items-center gap-2 text-center"><QRCodeSVG value={decision.url} size={184} /><p className="text-xs text-muted-foreground">{t("payment.scanWithClient")} · {clientLabel(decision.client, t)}</p></div>
    }
    if (decision.mode === "copy") {
      return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
        <p className="text-sm text-muted-foreground">{t("payment.openInClientHint")} · {clientLabel(decision.client, t)}</p>
        <button type="button" onClick={() => void copy(decision.url)} disabled={copying} className="inline-flex items-center gap-1.5 rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"><Copy className="h-3.5 w-3.5" />{t("payment.copyClientLink")}</button>
      </div>
    }
    return <p className="px-3 text-center text-sm text-muted-foreground">{t("payment.openingClient")} · {clientLabel(decision.client, t)}</p>
  }

  if (decision.kind === "deep_link") {
    if (decision.mode === "qr_code") {
      return <div className="flex flex-col items-center gap-2 text-center"><QRCodeSVG value={decision.url} size={184} /><p className="text-xs text-muted-foreground">{t("payment.scanWithClient")} · {clientLabel(decision.client, t)}</p></div>
    }
    return <p className="px-3 text-center text-sm text-muted-foreground">{t("payment.openingClient")} · {clientLabel(decision.client, t)}</p>
  }

  return <div className="flex flex-col items-center gap-3 px-3 text-center" role="status">
    <Loader2 className="h-6 w-6 animate-spin text-primary" />
    <p className="text-sm text-muted-foreground">{t("payment.submittingForm")}</p>
  </div>
}
