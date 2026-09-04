"use client"

import { Check, Copy } from "lucide-react"
import { useState } from "react"
import { toast } from "sonner"

import { useLocale } from "@/lib/context"
import { copyText } from "@/lib/clipboard"
import { cn } from "@/lib/utils"

export function CopyableValue({
  value,
  label,
  displayValue = value,
  mono = false,
  className,
}: {
  value: string
  label: string
  displayValue?: string
  mono?: boolean
  className?: string
}) {
  const { t } = useLocale()
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    const success = await copyText(value)
    if (success) {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
      toast.success(`${label} ${t("admin.copySuccess")}`)
    } else {
      toast.error(`${label} ${t("admin.copyFailed")}`)
    }
  }

  return (
    <button
      type="button"
      className={cn("group inline-flex min-w-0 max-w-full items-center gap-1 rounded-control text-left transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35", className)}
      onClick={() => void handleCopy()}
      title={`${t("admin.copyField")}: ${label}`}
      aria-label={`${t("admin.copyField")}: ${label}`}
    >
      <span className={cn("min-w-0 break-all", mono && "font-mono")}>{displayValue}</span>
      {copied ? <Check className="size-3.5 shrink-0 text-emerald-600" aria-hidden="true" /> : <Copy className="size-3.5 shrink-0 text-muted-foreground opacity-70 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100" aria-hidden="true" />}
    </button>
  )
}
