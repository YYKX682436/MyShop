"use client"

import { useEffect, useState } from "react"
import { useAuth } from "@/lib/context"
import { getOrCreateGuestSessionToken, setMessageGuestToken } from "@/services/api"

export function useSupportIdentity() {
  const { authLoaded, isLoggedIn, user } = useAuth()
  const [guest, setGuest] = useState("")
  useEffect(() => {
    if (!authLoaded || isLoggedIn) { setGuest(""); return }
    const update = () => { try { const token = getOrCreateGuestSessionToken(); setMessageGuestToken(token); setGuest(token) } catch { setGuest("") } }
    update()
    const onStorage = (event: StorageEvent) => { if (event.key === "session_token") update() }
    window.addEventListener("storage", onStorage)
    return () => window.removeEventListener("storage", onStorage)
  }, [authLoaded, isLoggedIn, user?.id])
  return !authLoaded ? "" : isLoggedIn ? (user ? `user:${user.id}` : "") : guest ? `guest:${guest}` : ""
}
