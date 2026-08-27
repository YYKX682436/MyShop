import type { DashboardStats, ServiceStatus, SystemStatus } from "@/types"

export const DASHBOARD_THRESHOLDS = {
  usageWarningPct: 70,
  usageCriticalPct: 85,
  paymentWarningPct: 90,
  paymentMinimumSampleCount: 5,
} as const

export type UsageSeverity = "normal" | "warning" | "critical" | null
export type SystemStatusLevel = "normal" | "warning" | "critical" | "unavailable"

export function getUsageSeverity(usagePct: number | null | undefined): UsageSeverity {
  if (usagePct === null || usagePct === undefined || !Number.isFinite(usagePct)) {
    return null
  }
  if (usagePct > DASHBOARD_THRESHOLDS.usageCriticalPct) return "critical"
  if (usagePct >= DASHBOARD_THRESHOLDS.usageWarningPct) return "warning"
  return "normal"
}

export function formatBytes(bytes: number | null | undefined, unavailable = "—"): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) {
    return unavailable
  }
  if (bytes === 0) return "0 B"

  const units = ["B", "KB", "MB", "GB", "TB", "PB"]
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** unitIndex)
  const fractionDigits = unitIndex === 0 || value >= 10 ? 0 : 1
  const formatted = value.toFixed(fractionDigits).replace(/\.0+$/, "")
  return `${formatted} ${units[unitIndex]}`
}

export function formatChangePct(value: number | null | undefined, unavailable = "—"): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return unavailable
  if (value === 0) return "0%"
  return `${value > 0 ? "+" : ""}${value.toFixed(1)}%`
}

export function formatPercent(value: number | null | undefined, unavailable = "—"): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return unavailable
  return `${value.toFixed(1)}%`
}

export function getSystemStatusLevel(system: SystemStatus | null): SystemStatusLevel {
  if (!system) return "unavailable"

  const serviceStatuses: ServiceStatus[] = [
    system.services.api.status,
    system.services.web.status,
    system.services.database.status,
    system.services.caddy.status,
  ]
  if (serviceStatuses.includes("DOWN")) return "critical"
  if (serviceStatuses.includes("UNKNOWN")) return "warning"
  if (!system.available) return "unavailable"
  if (!hasSystemMeasurements(system)) return "unavailable"

  const resourceSeverities = [
    getUsageSeverity(system.cpu?.usage_pct),
    getUsageSeverity(system.memory?.usage_pct),
    getUsageSeverity(system.disk?.usage_pct),
  ]
  if (resourceSeverities.includes("critical")) return "critical"
  if (resourceSeverities.includes("warning")) return "warning"
  return "normal"
}

export function formatNumber(value: number, locale: "zh" | "en"): string {
  return new Intl.NumberFormat(locale === "en" ? "en-US" : "zh-CN").format(value)
}

export interface DashboardAttentionItem {
  id: string
  severity: "critical" | "warning"
  message: string
}

export function mergeAndSortAttention(
  items: DashboardAttentionItem[],
  limit = Number.POSITIVE_INFINITY,
): DashboardAttentionItem[] {
  const unique = new Map<string, DashboardAttentionItem>()
  for (const item of items) {
    const existing = unique.get(item.id)
    if (!existing || severityRank(item.severity) < severityRank(existing.severity)) {
      unique.set(item.id, item)
    }
  }
  return [...unique.values()]
    .sort((left, right) => severityRank(left.severity) - severityRank(right.severity))
    .slice(0, limit)
}

export interface DashboardAttentionMessages {
  paidWaitingDelivery: (count: number) => string
  lowStock: (count: number) => string
  zeroStock: (count: number) => string
  paymentSuccessRate: (rate: number) => string
  monitorUnavailable: () => string
  resource: (resource: "cpu" | "memory" | "disk", usagePct: number, severity: "critical" | "warning") => string
  serviceUnavailable: (service: "api" | "web" | "database" | "caddy") => string
  serviceUnknown: (service: "api" | "web" | "database" | "caddy") => string
}

export function buildDashboardAttention(
  stats: DashboardStats | null,
  system: SystemStatus | null,
  messages: DashboardAttentionMessages,
): DashboardAttentionItem[] {
  const items: DashboardAttentionItem[] = []

  if (stats) {
    if (stats.paid_waiting_delivery > 0) {
      items.push({
        id: "paid-waiting-delivery",
        severity: "critical",
        message: messages.paidWaitingDelivery(stats.paid_waiting_delivery),
      })
    }
    if (stats.low_stock_products.length > 0) {
      items.push({
        id: "low-stock",
        severity: "warning",
        message: messages.lowStock(stats.low_stock_products.length),
      })
    }
    if (stats.zero_stock_products > 0) {
      items.push({
        id: "zero-stock",
        severity: "warning",
        message: messages.zeroStock(stats.zero_stock_products),
      })
    }
    if (stats.today_payment_success_sample_count >= DASHBOARD_THRESHOLDS.paymentMinimumSampleCount
      && stats.today_payment_success_rate < DASHBOARD_THRESHOLDS.paymentWarningPct) {
      items.push({
        id: "payment-success-rate",
        severity: "warning",
        message: messages.paymentSuccessRate(stats.today_payment_success_rate),
      })
    }
  }

  if (system) {
    if (!system.available || !hasSystemMeasurements(system)) {
      items.push({
        id: "system-monitor",
        severity: "warning",
        message: messages.monitorUnavailable(),
      })
    }
    const services: Array<["api" | "web" | "database" | "caddy", ServiceStatus]> = [
      ["api", system.services.api.status],
      ["web", system.services.web.status],
      ["database", system.services.database.status],
      ["caddy", system.services.caddy.status],
    ]
    for (const [service, status] of services) {
      if (status === "DOWN") {
        items.push({
          id: `service-${service}`,
          severity: "critical",
          message: messages.serviceUnavailable(service),
        })
      } else if (status === "UNKNOWN") {
        items.push({
          id: `service-${service}`,
          severity: "warning",
          message: messages.serviceUnknown(service),
        })
      }
    }
    if (system.available) {
      addResourceAttention(items, "cpu", system.cpu?.usage_pct, messages)
      addResourceAttention(items, "memory", system.memory?.usage_pct, messages)
      addResourceAttention(items, "disk", system.disk?.usage_pct, messages)
    }
  }

  return mergeAndSortAttention(items)
}

function hasSystemMeasurements(system: SystemStatus): boolean {
  return [
    system.cpu?.usage_pct,
    system.memory?.usage_pct,
    system.disk?.usage_pct,
    system.uptime_seconds,
    system.load_1m,
  ].some(value => value !== null && value !== undefined && Number.isFinite(value))
}

function addResourceAttention(
  items: DashboardAttentionItem[],
  resource: "cpu" | "memory" | "disk",
  usagePct: number | null | undefined,
  messages: DashboardAttentionMessages,
) {
  const severity = getUsageSeverity(usagePct)
  if (severity === "warning" || severity === "critical") {
    items.push({
      id: `${resource}-usage`,
      severity,
      message: messages.resource(resource, usagePct as number, severity),
    })
  }
}

function severityRank(severity: DashboardAttentionItem["severity"]): number {
  return severity === "critical" ? 0 : 1
}
