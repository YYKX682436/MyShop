"use client"

import type { ReactNode } from "react"
import { ChevronLeft, ChevronRight, Loader2 } from "lucide-react"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"

interface AdminPageProps {
  children: ReactNode
  className?: string
}

export function AdminPage({ children, className }: AdminPageProps) {
  return (
    <div className={cn("mx-auto flex w-full max-w-[1600px] min-w-0 flex-col gap-7", className)}>
      {children}
    </div>
  )
}

interface PageHeaderProps {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  className?: string
}

export function PageHeader({ title, description, actions, className }: PageHeaderProps) {
  return (
    <header className={cn("flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between", className)}>
      <div className="min-w-0">
        <h1 className="text-[1.75rem] font-semibold tracking-[-0.03em] text-foreground sm:text-3xl">{title}</h1>
        {description && <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </header>
  )
}

export function PageToolbar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn("flex flex-col gap-3 rounded-card border border-border-subtle bg-surface-muted/70 p-2.5 shadow-sm lg:flex-row lg:items-center lg:justify-between", className)}>
      {children}
    </div>
  )
}

export function DataTableShell({ children, footer, className }: { children: ReactNode; footer?: ReactNode; className?: string }) {
  return (
    <div className={cn("min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface shadow-sm", className)}>
      <div className="min-w-0 overflow-x-auto">{children}</div>
      {footer}
    </div>
  )
}

export function TableLoadingState({ colSpan, rows = 3 }: { colSpan: number; rows?: number }) {
  return (
    <>
      {Array.from({ length: rows }, (_, row) => (
        <tr key={row} className="border-b border-border-subtle last:border-0">
          <td colSpan={colSpan} className="px-4 py-3">
            <Skeleton className="h-8 w-full" />
          </td>
        </tr>
      ))}
    </>
  )
}

export function TableEmptyState({ colSpan, children }: { colSpan: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-12 text-center text-sm text-muted-foreground">
        {children}
      </td>
    </tr>
  )
}

export function TableErrorState({ colSpan, onRetry, children }: { colSpan: number; onRetry: () => void; children?: ReactNode }) {
  const { t } = useLocale()
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-12 text-center">
        <div className="flex flex-col items-center gap-3 text-sm text-destructive">
          <span>{children || t("admin.loadError")}</span>
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            {t("common.retry")}
          </Button>
        </div>
      </td>
    </tr>
  )
}

type PageItem = number | "ellipsis-left" | "ellipsis-right"

function getPageItems(currentPage: number, totalPages: number): PageItem[] {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index + 1)

  const pages = new Set([1, totalPages, currentPage - 1, currentPage, currentPage + 1])
  const sorted = [...pages].filter((page) => page >= 1 && page <= totalPages).sort((a, b) => a - b)
  const items: PageItem[] = []
  sorted.forEach((page, index) => {
    if (index > 0 && page - sorted[index - 1] > 1) {
      items.push(index === 1 ? "ellipsis-left" : "ellipsis-right")
    }
    items.push(page)
  })
  return items
}

export function AdminPagination({
  page,
  total,
  pageSize,
  onPageChange,
  className,
}: {
  page: number
  total: number
  pageSize: number
  onPageChange: (page: number) => void
  className?: string
}) {
  const { t } = useLocale()
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const currentPage = Math.min(Math.max(page, 1), totalPages)

  return (
    <div className={cn("flex flex-col gap-3 border-t border-border-subtle bg-surface-muted/45 px-4 py-3.5 sm:flex-row sm:items-center sm:justify-between", className)}>
      <span className="text-sm text-muted-foreground">
        {t("admin.totalRecords")} {total} {t("admin.records")}
      </span>
      {totalPages > 1 && (
        <nav className="flex items-center justify-end gap-1" aria-label={t("admin.pagination")}>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="h-8 w-8"
            disabled={currentPage === 1}
            onClick={() => onPageChange(currentPage - 1)}
            aria-label={t("common.prev")}
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
          </Button>
          {getPageItems(currentPage, totalPages).map((item) => {
            if (typeof item !== "number") {
              return (
                <span key={item} className="flex h-8 w-8 items-center justify-center text-sm text-muted-foreground" aria-hidden="true">
                  …
                </span>
              )
            }
            return (
              <Button
                key={item}
                type="button"
                variant={item === currentPage ? "default" : "outline"}
                size="icon"
                className="h-8 w-8 text-sm"
                onClick={() => onPageChange(item)}
                aria-label={t("admin.goToPage").replace("{page}", String(item))}
                aria-current={item === currentPage ? "page" : undefined}
              >
                {item}
              </Button>
            )
          })}
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="h-8 w-8"
            disabled={currentPage === totalPages}
            onClick={() => onPageChange(currentPage + 1)}
            aria-label={t("common.next")}
          >
            <ChevronRight className="h-4 w-4" aria-hidden="true" />
          </Button>
        </nav>
      )}
    </div>
  )
}

type StatusTone = "neutral" | "success" | "info" | "warning" | "danger" | "purple"

const statusToneClasses: Record<StatusTone, string> = {
  neutral: "bg-surface-muted text-muted-foreground ring-1 ring-inset ring-border-subtle",
  success: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-300",
  info: "bg-blue-500/10 text-blue-700 dark:text-blue-300",
  warning: "bg-amber-500/10 text-amber-700 dark:text-amber-300",
  danger: "bg-destructive/10 text-destructive",
  purple: "bg-purple-500/10 text-purple-700 dark:text-purple-300",
}

export function StatusBadge({ label, tone = "neutral" }: { label: ReactNode; tone?: StatusTone }) {
  return <span className={cn("inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium", statusToneClasses[tone])}>{label}</span>
}

export function FormSection({ title, description, children, className }: { title: ReactNode; description?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-surface border border-border-subtle bg-surface p-5 shadow-sm sm:p-6", className)}>
      <div className="mb-5">
        <h2 className="text-base font-semibold text-foreground">{title}</h2>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </section>
  )
}

export function SettingsSection({ title, description, children, className }: { title: ReactNode; description?: ReactNode; children: ReactNode; className?: string }) {
  return <FormSection title={title} description={description} className={cn("max-w-4xl", className)}>{children}</FormSection>
}

export function ConfirmAlert({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  destructive = false,
  loading = false,
  confirmDisabled = false,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description?: ReactNode
  confirmLabel: ReactNode
  cancelLabel: ReactNode
  onConfirm: () => void
  destructive?: boolean
  loading?: boolean
  confirmDisabled?: boolean
  children?: ReactNode
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {description && <AlertDialogDescription>{description}</AlertDialogDescription>}
        </AlertDialogHeader>
        {children}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={loading}>{cancelLabel}</AlertDialogCancel>
          <AlertDialogAction
            disabled={loading || confirmDisabled}
            className={destructive ? "bg-destructive text-destructive-foreground hover:bg-destructive/90" : undefined}
            onClick={(event) => {
              event.preventDefault()
              if (loading || confirmDisabled) {
                return
              }
              onConfirm()
            }}
          >
            {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
