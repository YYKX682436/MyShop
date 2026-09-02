"use client"

import { cn } from "@/lib/utils"
import { PaymentIcon } from "./payment-icon"
import type { PaymentChannelItem } from "@/types"

interface PaymentSelectorProps {
  channels: PaymentChannelItem[]
  selected: string
  onSelect: (code: string) => void
}

export function PaymentSelector({ channels, selected, onSelect }: PaymentSelectorProps) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap">
      {channels.map((ch) => (
        <button
          key={ch.channel_code}
          onClick={() => onSelect(ch.channel_code)}
          className={cn(
            "flex items-center justify-center gap-2 rounded-control border px-3 py-2.5 text-sm font-medium transition-[border-color,background-color,transform] duration-180",
            selected === ch.channel_code
              ? "border-primary/25 bg-primary/10 text-primary"
              : "border-border-subtle text-foreground hover:border-border-strong hover:bg-surface-muted",
          )}
        >
          <PaymentIcon method={ch.payment_type} className="h-5 w-5 shrink-0" />
          <span className="truncate">{ch.channel_name}</span>
        </button>
      ))}
    </div>
  )
}
