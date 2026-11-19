"use client"

import { useCallback, useSyncExternalStore } from "react"

export type AdminResource = "products" | "categories" | "inventory" | "users" | "user-levels"
type Listener = () => void
const revisions: Record<AdminResource, number> = { products: 0, categories: 0, inventory: 0, users: 0, "user-levels": 0 }
const listeners: Record<AdminResource, Set<Listener>> = {
  products: new Set(), categories: new Set(), inventory: new Set(), users: new Set(), "user-levels": new Set(),
}

let createdProduct: { id: string; productNo: number } | null = null

export function getAdminDataRevision(resource: AdminResource) {
  return revisions[resource]
}

export function subscribeAdminDataInvalidation(resource: AdminResource, listener: Listener) {
  listeners[resource].add(listener)
  return () => { listeners[resource].delete(listener) }
}

export function invalidateAdminData(...resources: AdminResource[]) {
  for (const resource of new Set(resources)) {
    revisions[resource] += 1
    for (const listener of [...listeners[resource]]) listener()
  }
}

export function announceCreatedProduct(id: string, productNo: number) {
  createdProduct = { id, productNo }
  invalidateAdminData("products", "inventory")
}

export function takeCreatedProduct() {
  const result = createdProduct
  createdProduct = null
  return result
}

export function useAdminDataRevision(resource: AdminResource) {
  const subscribe = useCallback((listener: Listener) => {
    return subscribeAdminDataInvalidation(resource, listener)
  }, [resource])
  const getSnapshot = useCallback(() => getAdminDataRevision(resource), [resource])
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
