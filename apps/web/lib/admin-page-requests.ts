"use client"

import { useCallback, useEffect, useRef, useState } from "react"

// Each independent data source gets its own guard. Starting a newer request
// invalidates older responses, including responses arriving after unmount.
export function useRequestGuard() {
  const sequence = useRef(0)
  useEffect(() => () => { sequence.current += 1 }, [])
  return useCallback(() => {
    const current = ++sequence.current
    return () => sequence.current === current
  }, [])
}

export function useAdminPageRefresh(action: () => Promise<unknown>) {
  const [refreshing, setRefreshing] = useState(false)
  const busy = useRef(false)
  const refresh = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    setRefreshing(true)
    try {
      await action()
    } finally {
      busy.current = false
      setRefreshing(false)
    }
  }, [action])
  return { refresh, refreshing }
}
