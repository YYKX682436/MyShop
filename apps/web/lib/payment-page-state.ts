import type { OrderStatus, PaymentNextAction } from "@/types"

export type PaymentPageLoadPhase = "loading" | "ready" | "error"
export type PaymentStatusRequestKind = "initial" | "poll" | "manual"

export type PaymentPageActionState = {
  phase: PaymentPageLoadPhase
  nextAction: PaymentNextAction | null
  errorMessage: string | null
}

export function createPaymentPageActionState(initialAction: PaymentNextAction | null): PaymentPageActionState {
  return {
    phase: initialAction ? "ready" : "loading",
    nextAction: initialAction,
    errorMessage: null,
  }
}

/**
 * Reconcile a server response without throwing away a usable first-render
 * action when a pending response omits next_action.
 */
export function applyPaymentStatus(
  state: PaymentPageActionState,
  status: OrderStatus,
  serverAction: PaymentNextAction | null | undefined,
): PaymentPageActionState {
  const nextAction = status === "PENDING" ? (serverAction ?? state.nextAction) : null
  return {
    phase: "ready",
    nextAction,
    errorMessage: null,
  }
}

export function applyPaymentStatusError(
  state: PaymentPageActionState,
  errorMessage: string,
  requestKind: PaymentStatusRequestKind,
): PaymentPageActionState {
  return {
    phase: state.nextAction || requestKind !== "initial" ? "ready" : "error",
    nextAction: state.nextAction,
    errorMessage,
  }
}
