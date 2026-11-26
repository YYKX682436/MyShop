export type SupportSendContext = {
  conversationId: string
  afterSale: boolean
  orderId?: string
  requestId?: string
  round?: number
  noticeId?: string
}

export type PendingSupportMessage = {
  key: string
  context: Readonly<SupportSendContext>
  content: string
  files: File[]
  state: "sending" | "sent" | "failed"
  error?: string
}

/** Snapshot the exact context and files used by both the first send and retries. */
export function createPendingSupportMessage(
  key: string,
  context: SupportSendContext,
  content: string,
  files: File[],
): PendingSupportMessage {
  return { key, context: Object.freeze({ ...context }), content, files: [...files], state: "sending" }
}

export function shouldFetchConversationDetail(detailLoaded: boolean, newActivityCount: number) {
  return !detailLoaded || newActivityCount > 0
}

/** Batches remain queued until the server confirms their exact visible receipts. */
export class SupportReadQueue {
  private readonly queued = new Set<string>()
  private readonly saved = new Set<string>()
  private readonly flights = new Map<AbortSignal, Promise<void>>()

  enqueue(cursors: Iterable<string>) {
    for (const cursor of cursors) if (!this.saved.has(cursor)) this.queued.add(cursor)
  }

  clear() {
    this.queued.clear()
    this.saved.clear()
  }

  pending() { return [...this.queued] }
  acknowledged() { return [...this.saved] }

  flush(signal: AbortSignal, send: (batch: string[], signal: AbortSignal) => Promise<void>, onSuccess?: () => void) {
    const existing = this.flights.get(signal)
    if (existing) return existing
    const flight = this.drain(signal, send, onSuccess)
    this.flights.set(signal, flight)
    void flight.finally(() => { if (this.flights.get(signal) === flight) this.flights.delete(signal) })
    return flight
  }

  private async drain(signal: AbortSignal, send: (batch: string[], signal: AbortSignal) => Promise<void>, onSuccess?: () => void) {
    while (!signal.aborted && this.queued.size) {
      const batch = [...this.queued].filter(cursor => !this.saved.has(cursor)).slice(0, 50)
      if (!batch.length) return
      try {
        await send(batch, signal)
      } catch {
        return
      }
      if (signal.aborted) return
      batch.forEach(cursor => { this.queued.delete(cursor); this.saved.add(cursor) })
      onSuccess?.()
    }
  }
}
