"use client"

import { useEffect, useRef } from "react"

/** One request at a time; pause in the background and catch up on return/reconnect. */
export function useVisiblePoll(task: (signal: AbortSignal) => Promise<void>, enabled: boolean, delay: number, identity: string) {
  const current = useRef(task)
  useEffect(() => { current.current = task }, [task])
  useEffect(() => {
    if (!enabled) return
    let stopped = false
    let running = false
    let failures = 0
    let resumeRequested = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined

    const schedule = (ms: number) => {
      if (timer) clearTimeout(timer)
      if (!stopped && document.visibilityState === "visible" && navigator.onLine) timer = setTimeout(run, ms)
    }
    const run = async () => {
      if (stopped || running || document.visibilityState !== "visible" || !navigator.onLine) return
      running = true
      controller = new AbortController()
      try {
        await current.current(controller.signal)
        if (!controller.signal.aborted) failures = 0
      } catch {
        if (!controller.signal.aborted) failures = Math.min(failures + 1, 5)
      } finally {
        running = false
        controller = undefined
        schedule(resumeRequested ? 0 : failures ? Math.min(60_000, delay * 2 ** failures) : delay)
        resumeRequested = false
      }
    }
    const onVisibility = () => {
      if (document.visibilityState !== "visible" || !navigator.onLine) {
        if (timer) clearTimeout(timer)
        controller?.abort()
      } else if (!running) schedule(0)
      else resumeRequested = true
    }
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("focus", onVisibility)
    window.addEventListener("online", onVisibility)
    window.addEventListener("offline", onVisibility)
    window.addEventListener("myshop:messages-changed", onVisibility)
    schedule(0)
    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
      controller?.abort()
      document.removeEventListener("visibilitychange", onVisibility)
      window.removeEventListener("focus", onVisibility)
      window.removeEventListener("online", onVisibility)
      window.removeEventListener("offline", onVisibility)
      window.removeEventListener("myshop:messages-changed", onVisibility)
    }
  }, [enabled, delay, identity])
}
