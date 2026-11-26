"use client"

import type { HTMLAttributes, ReactNode } from "react"

/**
 * Selective Tailwind 3 adaptation of shadcn/ui Bubble, MessageGroup, Message,
 * Marker, and Attachment composition patterns. Source: shadcn-ui/ui commit
 * 0e5b2e621a9ee4cf6a604bbe6c5c0d5b70561eb4 (MIT); see THIRD_PARTY_NOTICES.md.
 */
export function MessageGroup({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="message-group" className={`flex min-w-0 flex-col ${className}`} {...props} />
}

export function Message({ align = "start", children, className = "", ...props }: HTMLAttributes<HTMLDivElement> & { align?: "start" | "end" }) {
  return <div data-slot="message" data-align={align} className={`flex min-w-0 ${align === "end" ? "justify-end" : "justify-start"} ${className}`} {...props}>{children}</div>
}

export function Bubble({ variant = "muted", children, className = "", ...props }: HTMLAttributes<HTMLDivElement> & { variant?: "primary" | "muted" | "system" }) {
  const appearance = variant === "primary"
    ? "bg-primary text-white"
    : variant === "system"
      ? "border border-border-subtle bg-muted/45 text-foreground"
      : "bg-muted/70 text-foreground dark:bg-muted/55"
  return <div data-slot="bubble" data-variant={variant} className={`w-fit max-w-[min(86%,42rem)] min-w-0 overflow-hidden rounded-[1.35rem] px-3.5 py-2.5 text-[15px] leading-[1.45] [overflow-wrap:anywhere] ${appearance} ${className}`} {...props}>{children}</div>
}

export function Marker({ children, className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="marker" className={`flex min-h-5 w-full items-center justify-center py-2 text-xs text-muted-foreground ${className}`} {...props}>{children}</div>
}

export function Attachment({ children, className = "", state = "done", ...props }: HTMLAttributes<HTMLDivElement> & { children?: ReactNode; state?: "idle" | "uploading" | "processing" | "error" | "done" }) {
  const stateStyle = state === "error" ? "border border-destructive/30 bg-destructive/5 text-destructive"
    : state === "idle" ? "border border-dashed border-border-subtle bg-background/20"
      : "border border-transparent bg-background/20"
  return <div data-slot="attachment" data-state={state} className={`group/attachment relative flex w-fit max-w-full min-w-0 shrink-0 items-center gap-2 rounded-control px-2 py-1 text-xs transition-colors focus-within:ring-1 focus-within:ring-ring/50 ${stateStyle} ${className}`} {...props}>{children}</div>
}

export function AttachmentMedia({ children, className = "", variant = "icon", ...props }: HTMLAttributes<HTMLDivElement> & { variant?: "icon" | "image" }) {
  return <div data-slot="attachment-media" data-variant={variant} className={`relative flex size-9 shrink-0 items-center justify-center overflow-hidden rounded bg-muted text-foreground ${variant === "image" ? "[&_img]:size-full [&_img]:object-cover" : ""} ${className}`} {...props}>{children}</div>
}

export function AttachmentContent({ children, className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="attachment-content" className={`min-w-0 max-w-full flex-1 leading-tight ${className}`} {...props}>{children}</div>
}

export function AttachmentTitle({ children, className = "", ...props }: HTMLAttributes<HTMLSpanElement>) {
  return <span data-slot="attachment-title" className={`block min-w-0 max-w-full truncate font-medium ${className}`} {...props}>{children}</span>
}

export function AttachmentActions({ children, className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="attachment-actions" className={`relative z-10 flex shrink-0 items-center ${className}`} {...props}>{children}</div>
}
