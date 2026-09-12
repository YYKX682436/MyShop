"use client"

import { useEffect, useRef, useState } from "react"
import { LoaderCircle, UserRound } from "lucide-react"
import { useLocale } from "@/lib/context"
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { getAvatarDisplayState, type AvatarImageLoadingStatus } from "@/lib/avatar-display"
import { AVATAR_ALLOWED_IMAGE_ACCEPT } from "@/lib/image-upload"

interface AccountAvatarProps {
  avatarUrl: string | null
  username: string
  editable?: boolean
  isBusy?: boolean
  onUpload?: (file: File) => void
}

export function AccountAvatar({
  avatarUrl,
  username,
  editable = true,
  isBusy = false,
  onUpload,
}: AccountAvatarProps) {
  const { t } = useLocale()
  const inputRef = useRef<HTMLInputElement>(null)
  const fallbackCharacter = username.trim().slice(0, 1).toUpperCase()
  const [imageState, setImageState] = useState<{
    avatarUrl: string | null
    status: AvatarImageLoadingStatus
  }>(() => ({
    avatarUrl,
    status: avatarUrl ? "loading" : "error",
  }))

  useEffect(() => {
    setImageState({
      avatarUrl,
      status: avatarUrl ? "loading" : "error",
    })
  }, [avatarUrl])

  const openFilePicker = () => {
    if (editable && !isBusy) inputRef.current?.click()
  }

  const displayState = getAvatarDisplayState(avatarUrl, imageState.avatarUrl, imageState.status)

  const avatar = (
    <Avatar className="size-full border border-border-subtle bg-surface-muted shadow-sm">
      {avatarUrl && (
        <AvatarImage
          key={avatarUrl}
          src={avatarUrl}
          alt={username}
          className="object-cover"
          onLoadingStatusChange={(status) => setImageState({ avatarUrl, status })}
        />
      )}
      {displayState === "loading" ? (
        <span className="flex size-full animate-pulse items-center justify-center rounded-full bg-foreground/[0.07]" aria-hidden="true" />
      ) : (
        <AvatarFallback className="bg-primary/10 text-2xl font-semibold text-primary sm:text-3xl">
          {fallbackCharacter || <UserRound className="size-9" strokeWidth={1.7} aria-hidden="true" />}
        </AvatarFallback>
      )}
    </Avatar>
  )

  return (
    <div className="size-20 shrink-0 sm:size-24">
      {editable ? (
        <button
          type="button"
          onClick={openFilePicker}
          disabled={isBusy}
          aria-label={t("my.editAvatar")}
          aria-busy={isBusy}
          className="group relative block size-full rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-80"
        >
          {avatar}
          {isBusy && (
            <span className="absolute inset-0 flex items-center justify-center rounded-full bg-background/55 text-foreground" aria-hidden="true">
              <LoaderCircle className="size-6 animate-spin" />
            </span>
          )}
        </button>
      ) : (
        <div className="relative size-full">{avatar}</div>
      )}

      {editable && (
        <input
          ref={inputRef}
          type="file"
          accept={AVATAR_ALLOWED_IMAGE_ACCEPT}
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ""
            if (file) onUpload?.(file)
          }}
          disabled={isBusy}
        />
      )}
    </div>
  )
}
