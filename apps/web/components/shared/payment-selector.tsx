"use client"

import { cn } from "@/lib/utils"
import { PaymentIcon } from "./payment-icon"
import type { PublicPaymentChannel } from "@/types"

interface PaymentSelectorProps {
  channels: PublicPaymentChannel[]
  selected: string
  onSelect: (code: string) => void
}

export function PaymentSelector({ channels, selected, onSelect }: PaymentSelectorProps) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap">
      {channels.map((ch) => {
        const hint = ch.payment_hint?.trim()
        const badge = ch.badge_text?.trim()
        return (
          <button
            key={ch.channel_code}
            onClick={() => onSelect(ch.channel_code)}
            className={cn(
              "flex min-h-14 flex-col items-stretch justify-center gap-1 rounded-control border px-3 py-2.5 text-sm font-medium transition-[border-color,background-color,transform] duration-180",
              selected === ch.channel_code
                ? "border-primary/25 bg-primary/10 text-primary"
                : "border-border-subtle text-foreground hover:border-border-strong hover:bg-surface-muted",
            )}
          >
            <span className="flex items-center justify-center gap-2">
              <PaymentIcon method={ch.type} className="h-5 w-5 shrink-0" />
              <span className="truncate">{ch.name}</span>
              {badge && <span className="max-w-20 truncate rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-semibold leading-none text-primary">{badge}</span>}
            </span>
            {hint && <span className="truncate text-[11px] font-normal text-muted-foreground">{hint}</span>}
          </button>
        )
      })}
    </div>
  )
}
