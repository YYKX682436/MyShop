import { redirect } from "next/navigation"

type Search = Record<string, string | string[] | undefined>

export default async function LegacyNewSupportPage({ searchParams }: { searchParams: Promise<Search> }) {
  const values = await searchParams
  const params = new URLSearchParams()
  for (const key of ["order", "request_id", "round", "notice"]) {
    const value = values[key]
    const first = Array.isArray(value) ? value[0] : value
    if (first) params.set(key, first)
  }
  redirect("/messages" + (params.size ? "?" + params.toString() : ""))
}
