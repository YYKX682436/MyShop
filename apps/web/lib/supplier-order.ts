export function supplierStatusLabel(status?: string | null): string {
  switch (status) {
    case "WAITING_PAYMENT": return "待采购"
    case "SUBMITTING": case "PROCESSING": return "正在处理"
    case "COMPLETED": return "已发货"
    case "BLOCKED": case "WITHDRAWN": return "采购异常"
    case "UNKNOWN": return "需要人工核实"
    case "UNPAID": return "上游未付款，需人工处理"
    case "REFUNDING": return "上游退款处理中"
    case "REFUNDED": return "上游已退款，需人工处理"
    default: return "等待处理"
  }
}

export function supplierErrorLabel(code: string): string {
  const labels: Record<string, string> = {
    COST_EXCEEDED: "上游成本超过采购保护金额", NO_MARGIN: "当前售价不足以覆盖成本",
    STOCK: "上游库存不足", STALE_STOCK: "库存同步失败或已过期", BALANCE: "供货账号余额不足",
    SUPPLIER_DISABLED: "供货账号或商品已停用", PRODUCT_DISABLED: "本地商品已停用",
    SKU_DISABLED: "本地规格已停用", SKU_CHANGED: "上游规格已变化", MAPPING_CHANGED: "货源映射已变化",
    PRODUCT_CHANGED: "上游商品状态或发货倍数已变化", TEMPLATE_CHANGED: "充值模板已变化",
    QUANTITY: "购买数量不符合供货限制", CALLBACK_CONFIG: "未配置有效的公开回调地址",
    CONNECTION: "连接上游失败，采购结果需核实", HTTP: "上游请求失败，采购结果需核实",
    BUSINESS: "上游返回业务错误，采购结果需核实", DATA: "上游数据异常，采购结果需核实",
    CONFIG: "货源账号或密钥配置不可用", DELIVERY_INCOMPLETE: "上游交付内容不完整",
    AFTER_SALE: "售后处理中，发货暂缓", ORDER_STATE: "订单状态需要人工核实",
  }
  return labels[code] ?? `采购状态需人工核实（${code}）`
}
