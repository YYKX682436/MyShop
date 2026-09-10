"use client"

import { useSyncExternalStore } from "react"

type AdminOrderInvalidationListener = () => void

let revision = 0
const listeners = new Set<AdminOrderInvalidationListener>()

export function getAdminOrderInvalidationRevision(): number {
  return revision
}

export function subscribeAdminOrderInvalidation(
  listener: AdminOrderInvalidationListener,
): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function invalidateAdminOrders(): number {
  revision += 1
  for (const listener of [...listeners]) listener()
  return revision
}

export function useAdminOrderInvalidation(): number {
  return useSyncExternalStore(
    subscribeAdminOrderInvalidation,
    getAdminOrderInvalidationRevision,
    getAdminOrderInvalidationRevision,
  )
}
