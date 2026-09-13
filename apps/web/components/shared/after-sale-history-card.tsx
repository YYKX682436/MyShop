"use client"

import Image from "next/image"

import { AfterSaleMessageComposer } from "@/components/shared/after-sale-message-composer"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { formatDateTime } from "@/lib/utils"
import type { AfterSaleRequest } from "@/types"

export function AfterSaleHistoryCard({
  request,
  history = [],
  customerLabel,
  mode = "customer",
  onReply,
  replyLoading = false,
}: {
  request?: AfterSaleRequest | null
  history?: AfterSaleRequest[]
  customerLabel?: string | null
  mode?: "customer" | "admin"
  onReply?: (content: string, attachments: File[]) => Promise<void>
  replyLoading?: boolean
}) {
  const { t, locale } = useLocale()
  const records = history.length > 0 ? history : request ? [request] : []
  if (records.length === 0) return null

  return (
    <section id="after-sale" className="surface space-y-4 p-4 sm:p-5" aria-labelledby="after-sale-history-title">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="after-sale-history-title" className="text-base font-semibold text-foreground">{t("order.afterSaleHistory")}</h2>
        <span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">{statusLabel(records[0], t)}</span>
      </div>
      <div className="space-y-4">
        {records.map((item, index) => (
          <AfterSaleRequestTimeline
            key={item.id}
            item={item}
            locale={locale}
            customerLabel={customerLabel}
            mode={mode}
            onReply={onReply && item.status === "PENDING" ? onReply : undefined}
            replyLoading={replyLoading}
            t={t}
            isLast={index === records.length - 1}
          />
        ))}
      </div>
    </section>
  )
}

function AfterSaleRequestTimeline({
  item,
  locale,
  customerLabel,
  mode,
  onReply,
  replyLoading,
  t,
  isLast,
}: {
  item: AfterSaleRequest
  locale: "zh" | "en"
  customerLabel?: string | null
  mode: "customer" | "admin"
  onReply?: (content: string, attachments: File[]) => Promise<void>
  replyLoading: boolean
  t: (key: TranslationKey) => string
  isLast: boolean
}) {
  const messages = item.messages ?? []
  return (
    <article className="rounded-card border border-border-subtle bg-surface-muted/40 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">{statusLabel(item, t)}</p>
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
      </dl>

      <div className="mt-4 space-y-3" aria-label={t("order.afterSaleConversation")}>
        <TimelineEntry
          label={t("order.afterSaleInitialApplication")}
          content={item.reason}
          time={item.submitted_at}
          locale={locale}
          tone="customer"
          attachments={item.attachments ?? []}
          t={t}
        />
        {messages.map(message => (
          <TimelineEntry
            key={message.id}
            label={message.sender_type === "ADMIN"
              ? t("order.afterSaleAdminMessage")
              : t("order.afterSaleCustomerMessage")}
            content={message.content}
            time={message.created_at}
            locale={locale}
            tone={message.sender_type === "ADMIN" ? "admin" : "customer"}
            attachments={message.attachments ?? []}
            t={t}
          />
        ))}
      </div>

      {item.status === "PENDING" && (
        <div className="mt-4 space-y-3">
          <p className="flex items-center rounded-control border border-primary/20 bg-primary/5 px-3 py-2 text-sm text-primary">
            {mode === "customer" && item.waiting_for === "CUSTOMER" && (
              <span className="mr-2 size-1.5 shrink-0 rounded-full bg-destructive" aria-hidden="true" />
            )}
            {item.waiting_for === "CUSTOMER"
              ? t("order.afterSaleWaitingForCustomer")
              : t("order.afterSaleWaitingForAdmin")}
          </p>
          {onReply && (
            <AfterSaleMessageComposer
              inputId={`after-sale-reply-${item.id}`}
              resetKey={item.id}
              disabled={replyLoading}
              submitLabel={mode === "admin" ? t("admin.afterSaleReply") : t("order.afterSaleSend")}
              placeholder={mode === "admin" ? t("admin.afterSaleReplyPlaceholder") : t("order.afterSaleReplyPlaceholder")}
              successMessage={mode === "admin" ? t("admin.afterSaleReplySuccess") : t("order.afterSaleReplySuccess")}
              onSubmit={onReply}
            />
          )}
        </div>
      )}

      {item.status === "REJECTED" && item.rejection_reason && (
        <div className="mt-4 rounded-control border border-amber-300/50 bg-amber-50 px-3 py-2 dark:border-amber-500/30 dark:bg-amber-950/20">
          <p className="text-xs text-amber-800 dark:text-amber-200">{t("order.afterSaleRejectionReason")}</p>
          <p className="mt-1 whitespace-pre-wrap break-words text-sm text-amber-900 dark:text-amber-100">{item.rejection_reason}</p>
        </div>
      )}
      {item.status !== "PENDING" && (
        <p className="mt-4 rounded-control bg-surface-muted px-3 py-2 text-sm text-muted-foreground">{t("order.afterSaleClosed")}</p>
      )}
      {!isLast && <div className="mt-4 border-t border-border-subtle" />}
    </article>
  )
}

function TimelineEntry({
  label,
  content,
  time,
  locale,
  tone,
  attachments,
  t,
}: {
  label: string
  content: string
  time: string
  locale: "zh" | "en"
  tone: "customer" | "admin"
  attachments: AfterSaleRequest["attachments"]
  t: (key: TranslationKey) => string
}) {
  return (
    <div className={`rounded-control border px-3 py-3 ${tone === "admin"
      ? "border-violet-200/70 bg-violet-50/60 dark:border-violet-500/20 dark:bg-violet-950/15"
      : "border-border-subtle bg-surface"}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <p className="text-xs font-semibold text-foreground">{label}</p>
        <time className="text-xs text-muted-foreground">{formatDateTime(time, locale)}</time>
      </div>
      <p className="mt-2 whitespace-pre-wrap break-words text-sm text-foreground">{content}</p>
      <AttachmentGrid attachments={attachments} t={t} />
    </div>
  )
}

function AttachmentGrid({ attachments, t }: { attachments: AfterSaleRequest["attachments"]; t: (key: TranslationKey) => string }) {
  if (attachments.length === 0) return null
  return (
    <div className="mt-3">
      <p className="text-xs text-muted-foreground">{t("order.afterSaleUpload")}</p>
      <div className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">
        {attachments.map(attachment => (
          <a key={attachment.id} href={attachment.url} target="_blank" rel="noreferrer" className="group relative aspect-square overflow-hidden rounded-control border border-border-subtle bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Image src={attachment.url} alt={attachment.original_filename} fill unoptimized sizes="120px" className="object-cover transition-transform group-hover:scale-105" />
            <span className="sr-only">{attachment.original_filename}</span>
          </a>
        ))}
      </div>
    </div>
  )
}

function statusLabel(item: AfterSaleRequest, t: (key: TranslationKey) => string) {
  if (item.status === "PENDING") {
    return item.waiting_for === "CUSTOMER"
      ? t("order.afterSaleWaitingForCustomer")
      : t("order.afterSalePendingAdmin")
  }
  if (item.status === "REJECTED") return t("afterSaleStatus.REJECTED")
  return t("afterSaleStatus.REFUNDED")
}

function sourceLabel(status: AfterSaleRequest["source_order_status"], t: (key: TranslationKey) => string) {
  return status === "PAID" ? t("status.PAID") : t("status.DELIVERED")
}
