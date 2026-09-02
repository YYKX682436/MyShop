"use client"

import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts"
import { useLocale } from "@/lib/context"
import { formatYuan } from "@/lib/utils"
import type { SalesTrend } from "@/types"

export function SalesChart({ trends }: { trends: SalesTrend[] }) {
  const { t } = useLocale()
  const salesLabel = `${t("admin.salesAmount")} (¥)`
  return (
    <div className="flex min-w-0 flex-col overflow-hidden rounded-surface border border-border-subtle bg-surface p-4 shadow-sm sm:p-5 xl:h-full">
      <div className="mb-4 flex items-center justify-between gap-3 sm:mb-6">
        <h3 className="font-semibold text-foreground">{t("admin.salesTrend")}</h3>
        <div className="hidden gap-4 text-xs text-muted-foreground sm:flex">
          <span className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-primary" />
            {t("admin.salesAmount")}
          </span>
          <span className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full bg-chart-2" />
            {t("admin.orderCount")}
          </span>
        </div>
      </div>
      {trends.length === 0 ? (
        <div className="flex min-h-64 flex-1 items-center justify-center text-sm text-muted-foreground sm:min-h-72">
          {t("admin.noSalesTrendData")}
        </div>
      ) : (
        <div className="min-h-64 flex-1 sm:min-h-72">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={trends} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
            <defs>
              <linearGradient id="salesGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="hsl(var(--primary))" stopOpacity={0.16} />
                <stop offset="95%" stopColor="hsl(var(--primary))" stopOpacity={0} />
              </linearGradient>
              <linearGradient id="ordersGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="hsl(var(--chart-2))" stopOpacity={0.14} />
                <stop offset="95%" stopColor="hsl(var(--chart-2))" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 12, fill: "hsl(var(--muted-foreground))" }}
                axisLine={{ stroke: "hsl(var(--border))" }}
                tickLine={false}
                minTickGap={24}
                interval="preserveStartEnd"
              />
              <YAxis
                tick={{ fontSize: 12, fill: "hsl(var(--muted-foreground))" }}
                axisLine={false}
                tickLine={false}
                tickFormatter={(value) => formatYuan(Number(value))}
                width={64}
              />
              <Tooltip
                contentStyle={{
                  background: "hsl(var(--surface-raised))",
                  border: "1px solid hsl(var(--border-subtle))",
                  borderRadius: "12px",
                  fontSize: "12px",
                }}
                formatter={(value, name) =>
                  name === salesLabel ? [formatYuan(Number(value)), name] : [value, name]
                }
              />
              <Area
                type="monotone"
                dataKey="sales_amount"
                stroke="hsl(var(--primary))"
                strokeWidth={2}
                fill="url(#salesGradient)"
                name={salesLabel}
              />
              <Area
                type="monotone"
                dataKey="order_count"
                stroke="hsl(var(--chart-2))"
                strokeWidth={2}
                fill="url(#ordersGradient)"
                name={t("admin.orderCount")}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
