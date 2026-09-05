export interface PaymentCarrierResponse {
  payment_url?: string | null
  qrcode_url?: string | null
  pay_url?: string | null
}

function normalizeCarrier(value: string | null | undefined): string {
  return value?.trim() || ""
}

/** Only a provider-supplied QR carrier may be rendered as QR code content. */
export function getPaymentQrValue(qrcodeUrl: string | null | undefined): string {
  return normalizeCarrier(qrcodeUrl)
}

/** Normalize the two carrier fields returned by the repay endpoint for immediate rendering. */
export function getRepayPaymentCarriers(result: PaymentCarrierResponse): { qrcodeUrl: string; payUrl: string } {
  const qrcodeUrl = normalizeCarrier(result.qrcode_url)
  return {
    qrcodeUrl,
    // payment_url is the legacy effective carrier. If no explicit QR carrier
    // exists, it is safe to treat it as a page URL, never as QR content.
    payUrl: normalizeCarrier(result.pay_url) || (!qrcodeUrl ? normalizeCarrier(result.payment_url) : ""),
  }
}
