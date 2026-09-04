export interface AdminOrderProductSummaryItem {
  product_title: string
  product_no: number
  spec_name: string | null
  quantity: number
}

export function formatAdminOrderNumber(orderNo: number | null | undefined): string {
  return orderNo === null || orderNo === undefined ? "" : String(orderNo)
}

export function formatAdminOrderProductMeta(item: AdminOrderProductSummaryItem): string {
  return `商品 #${item.product_no} · ${item.spec_name || "无规格"} · ×${item.quantity}`
}

export function limitAdminOrderProductItems<T>(items: T[], limit = 2) {
  const safeLimit = Math.max(0, limit)
  return {
    visible: items.slice(0, safeLimit),
    additionalCount: Math.max(0, items.length - safeLimit),
  }
}
