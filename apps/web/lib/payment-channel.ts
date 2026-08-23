import type { PaymentChannelItem } from "@/types"

/** Resolve a channel instance to the provider payment type it represents. */
export function getPaymentTypeForChannel(
  channels: readonly PaymentChannelItem[],
  channelCode: string | null | undefined,
): string | undefined {
  const normalizedCode = channelCode?.trim()
  if (!normalizedCode) return undefined

  const channel = channels.find(({ channel_code }) => channel_code === normalizedCode)
  const paymentType = channel?.payment_type?.trim().toLowerCase()
  return paymentType || undefined
}

/** Mobile routing must follow payment_type, never the channel instance code. */
export function isWechatChannel(
  channels: readonly PaymentChannelItem[],
  channelCode: string | null | undefined,
): boolean {
  return getPaymentTypeForChannel(channels, channelCode) === "wechat"
}
