"use client"

import Image from "next/image"

import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { formatDateTime } from "@/lib/utils"
import type { AfterSaleRequest } from "@/types"

export function AfterSaleHistoryCard({
  request,
  history = [],
  customerLabel,
}: {
  request?: AfterSaleRequest | null
  history?: AfterSaleRequest[]
  customerLabel?: string | null
}) {
  const { t, locale } = useLocale()
  const records = history.length > 0 ? history : request ? [request] : []
  if (records.length === 0) return null

  return (
    <section className="surface space-y-4 p-4 sm:p-5" aria-labelledby="after-sale-history-title">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="after-sale-history-title" className="text-base font-semibold text-foreground">{t("order.afterSaleHistory")}</h2>
        <span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">{statusLabel(records[0].status, t)}</span>
      </div>
      <div className="space-y-4">
        {records.map((item, index) => (
          <article key={item.id} className="rounded-card border border-border-subtle bg-surface-muted/40 p-4">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <p className="text-sm font-medium text-foreground">{statusLabel(item.status, t)}</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {t("order.afterSaleAppliedAt")}: {formatDateTime(item.submitted_at, locale)}
                </p>
              </div>
              {item.handled_at && <p className="text-xs text-muted-foreground">{t("order.afterSaleHandledAt")}: {formatDateTime(item.handled_at, locale)}</p>}
            </div>
            <dl className="mt-3 space-y-3 text-sm">
              {customerLabel && (
                <div>
                  <dt className="text-xs text-muted-foreground">{t("order.afterSaleCustomer")}</dt>
                  <dd className="mt-1 break-words text-foreground">{customerLabel}</dd>
                </div>
              )}
              <div>
                <dt className="text-xs text-muted-foreground">{t("order.afterSaleSource")}</dt>
                <dd className="mt-1 text-foreground">{sourceLabel(item.source_order_status, t)}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">{t("order.afterSaleReason")}</dt>
                <dd className="mt-1 whitespace-pre-wrap break-words text-foreground">{item.reason}</dd>
              </div>
              {item.status === "REJECTED" && item.admin_reply && (
                <div className="rounded-control border border-amber-300/50 bg-amber-50 px-3 py-2 dark:border-amber-500/30 dark:bg-amber-950/20">
                  <dt className="text-xs text-amber-800 dark:text-amber-200">{t("order.afterSaleAdminReply")}</dt>
                  <dd className="mt-1 whitespace-pre-wrap break-words text-sm text-amber-900 dark:text-amber-100">{item.admin_reply}</dd>
                </div>
              )}
            </dl>
            <div className="mt-3">
              <p className="text-xs text-muted-foreground">{t("order.afterSaleUpload")}</p>
              {item.attachments.length === 0 ? (
                <p className="mt-1 text-sm text-muted-foreground">{t("order.afterSaleNoAttachments")}</p>
              ) : (
                <div className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">
                  {item.attachments.map(attachment => (
                    <a key={attachment.id} href={attachment.url} target="_blank" rel="noreferrer" className="group relative aspect-square overflow-hidden rounded-control border border-border-subtle bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                      <Image src={attachment.url} alt={attachment.original_filename} fill unoptimized sizes="120px" className="object-cover transition-transform group-hover:scale-105" />
                      <span className="sr-only">{attachment.original_filename}</span>
                    </a>
                  ))}
                </div>
              )}
            </div>
            {index < records.length - 1 && <div className="mt-4 border-t border-border-subtle" />}
          </article>
        ))}
      </div>
    </section>
  )
}

function statusLabel(status: AfterSaleRequest["status"], t: (key: TranslationKey) => string) {
  if (status === "PENDING") return t("afterSaleStatus.PENDING")
  if (status === "REJECTED") return t("afterSaleStatus.REJECTED")
  return t("afterSaleStatus.REFUNDED")
}

function sourceLabel(status: AfterSaleRequest["source_order_status"], t: (key: TranslationKey) => string) {
  return status === "PAID" ? t("status.PAID") : t("status.DELIVERED")
}
