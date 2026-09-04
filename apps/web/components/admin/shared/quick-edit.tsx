"use client"

import { forwardRef, type ButtonHTMLAttributes, type ComponentProps, type ReactNode } from "react"
import { Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { PopoverContent } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"

export const quickEditTriggerClassName =
  "flex min-h-8 min-w-0 max-w-full items-center rounded-control px-2 py-1 text-left transition-colors hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35 focus-visible:ring-offset-1"

export const QuickEditTrigger = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement>>(
  function QuickEditTrigger({ children, className, ...props }, ref) {
    return <button ref={ref} type="button" className={cn(quickEditTriggerClassName, className)} {...props}>{children}</button>
  },
)

interface QuickEditContentProps {
  title: ReactNode
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
  className?: string
  onOpenAutoFocus?: ComponentProps<typeof PopoverContent>["onOpenAutoFocus"]
}

export function QuickEditContent({ title, description, children, footer, className, onOpenAutoFocus }: QuickEditContentProps) {
  return (
    <PopoverContent align="start" className={cn("w-[min(22rem,calc(100vw-2rem))]", className)} onOpenAutoFocus={onOpenAutoFocus}>
      <div className="mb-3 border-b border-border-subtle pb-3">
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
        {description && <p className="mt-1 text-xs leading-5 text-muted-foreground">{description}</p>}
      </div>
      {children}
      {footer}
    </PopoverContent>
  )
}

export function QuickEditActions({ onCancel, onSave, saving = false }: { onCancel: () => void; onSave: () => void; saving?: boolean }) {
  const { t } = useLocale()
  return (
    <div className="mt-4 flex justify-end gap-2">
      <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={saving}>{t("admin.cancel")}</Button>
      <Button type="button" size="sm" onClick={onSave} disabled={saving}>
        {saving && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />}
        {saving ? t("admin.saving") : t("admin.save")}
      </Button>
    </div>
  )
}
