export interface AdminOrderProductSummaryItem {
  product_title: string
  product_no: number
  spec_name: string | null
  quantity: number
}

export function formatAdminOrderProductMeta(item: AdminOrderProductSummaryItem): string {
  return `商品 #${item.product_no} · ${item.spec_name || "无规格"} · ×${item.quantity}`
}

export interface AdminOrderCustomerIdentity {
  customer_type?: "REGISTERED" | "GUEST" | string
  username?: string | null
  user_no?: number | null
}

/** Keep guest rows to one line and make the business user number explicit. */
export function formatAdminOrderCustomer(identity: AdminOrderCustomerIdentity, guestLabel = "游客") {
  if (identity.customer_type !== "REGISTERED") {
    return { primary: guestLabel, secondary: null }
  }
  const username = identity.username?.trim() || ""
  const userNumber = identity.user_no === null || identity.user_no === undefined ? null : `用户号 ${identity.user_no}`
  return {
    primary: username || userNumber || "注册用户",
    secondary: username && userNumber ? userNumber : null,
  }
}

export function limitAdminOrderProductItems<T>(items: T[], limit = 2) {
  const safeLimit = Math.max(0, limit)
  return {
    visible: items.slice(0, safeLimit),
    additionalCount: Math.max(0, items.length - safeLimit),
  }
}
