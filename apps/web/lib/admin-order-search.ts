export type AdminOrderSearchSortDirection = "asc" | "desc"

export interface AdminOrderSearchParams {
  page?: number
  page_size?: number
  status?: string
  keyword?: string
  created_from?: string
  created_to?: string
  sort_direction?: AdminOrderSearchSortDirection
}

/** Build the JSON-only request body used by the global admin order search. */
export function buildAdminOrderSearchBody(params: AdminOrderSearchParams = {}) {
  return {
    page: params.page ?? 1,
    page_size: params.page_size ?? 20,
    keyword: params.keyword?.trim() || undefined,
    status: params.status?.trim() || undefined,
    created_from: params.created_from || undefined,
    created_to: params.created_to || undefined,
    sort_direction: params.sort_direction ?? "desc" as const,
  }
}
