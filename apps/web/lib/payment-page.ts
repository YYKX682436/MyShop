export interface PaymentCarrierResponse {
  payment_url?: string | null
  qrcode_url?: string | null
  pay_url?: string | null
}

function normalizeCarrier(value: string | null | undefined): string {
  return value?.trim() || ""
}

/** Prefer a gateway QR carrier, then use the persisted effective payment carrier. */
export function getPaymentQrValue(qrcodeUrl: string | null | undefined, payUrl: string | null | undefined): string {
  return normalizeCarrier(qrcodeUrl) || normalizeCarrier(payUrl)
}

/** Normalize the two carrier fields returned by the repay endpoint for immediate rendering. */
export function getRepayPaymentCarriers(result: PaymentCarrierResponse): { qrcodeUrl: string; payUrl: string } {
  return {
    qrcodeUrl: normalizeCarrier(result.qrcode_url) || normalizeCarrier(result.payment_url),
    payUrl: normalizeCarrier(result.pay_url),
  }
}
