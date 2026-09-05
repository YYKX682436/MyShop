import type { PublicPaymentChannel } from "@/types"

/** Resolve a channel instance to the provider payment type it represents. */
export function getPaymentTypeForChannel(
  channels: readonly PublicPaymentChannel[],
  channelCode: string | null | undefined,
): string | undefined {
  const normalizedCode = channelCode?.trim()
  if (!normalizedCode) return undefined

  const channel = channels.find(({ channel_code }) => channel_code === normalizedCode)
  return channel?.type?.trim().toLowerCase() || undefined
}

/** Mobile routing must follow the stored type, never the channel instance code. */
export function isWechatChannel(
  channels: readonly PublicPaymentChannel[],
  channelCode: string | null | undefined,
): boolean {
  return getPaymentTypeForChannel(channels, channelCode) === "wxpay"
}
