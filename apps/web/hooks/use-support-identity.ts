"use client"

import { useEffect, useState } from "react"
import { useAuth } from "@/lib/context"
import { getOrCreateGuestSessionToken, setMessageGuestToken } from "@/services/api"

export function useSupportIdentity() {
  const { authLoaded, isLoggedIn, user } = useAuth()
  const [guest, setGuest] = useState("")
  const [site, setSite] = useState("")
  useEffect(() => {
    setSite(window.location.host.toLowerCase())
  }, [])
  useEffect(() => {
    if (!authLoaded || isLoggedIn) { setGuest(""); return }
    const update = () => { try { const token = getOrCreateGuestSessionToken(); setMessageGuestToken(token); setGuest(token) } catch { setGuest("") } }
    update()
    const onStorage = (event: StorageEvent) => { if (event.key === "session_token") update() }
    window.addEventListener("storage", onStorage)
    return () => window.removeEventListener("storage", onStorage)
  }, [authLoaded, isLoggedIn, user?.id])
  if (!authLoaded || !site) return ""
  return isLoggedIn
    ? user ? `${site}:user:${user.id}:level:${user.user_level_id ?? "none"}` : ""
    : guest ? `${site}:guest:${guest}` : ""
}
