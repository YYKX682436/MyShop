import type { PaymentActionDecision } from "./payment-action.ts"

export type ExecutablePaymentAction =
  | Extract<PaymentActionDecision, { kind: "redirect" | "form_post" }>
  | Extract<PaymentActionDecision, { kind: "open_in_client"; mode: "direct" }>
  | Extract<PaymentActionDecision, { kind: "deep_link"; mode: "open" }>

export function isExecutablePaymentAction(
  decision: PaymentActionDecision,
): decision is ExecutablePaymentAction {
  return decision.kind === "redirect"
    || decision.kind === "form_post"
    || (decision.kind === "open_in_client" && decision.mode === "direct")
    || (decision.kind === "deep_link" && decision.mode === "open")
}

/** Execute an already-resolved provider-neutral browser action. */
export function executePaymentAction(action: ExecutablePaymentAction): void {
  if (typeof window === "undefined" || typeof document === "undefined") return

  if (action.kind === "form_post") {
    const form = document.createElement("form")
    form.method = "POST"
    form.action = action.url
    form.style.display = "none"
    Object.entries(action.fields).forEach(([name, value]) => {
      const input = document.createElement("input")
      input.type = "hidden"
      input.name = name
      input.value = value
      form.appendChild(input)
    })
    document.body.appendChild(form)
    try {
      // Calling the prototype avoids a field named "submit" shadowing the API.
      HTMLFormElement.prototype.submit.call(form)
      window.setTimeout(() => form.remove(), 0)
    } catch (error) {
      form.remove()
      throw error
    }
    return
  }

  window.location.assign(action.url)
}
