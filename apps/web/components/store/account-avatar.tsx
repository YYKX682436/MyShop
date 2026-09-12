"use client"

import { useRef } from "react"
import { Camera, LoaderCircle, RotateCcw, UserRound } from "lucide-react"
import { useLocale } from "@/lib/context"
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { Button } from "@/components/ui/button"
import { AVATAR_ALLOWED_IMAGE_ACCEPT } from "@/lib/image-upload"

interface AccountAvatarProps {
  avatarUrl: string | null
  username: string
  editable?: boolean
  isBusy?: boolean
  onUpload?: (file: File) => void
  onDelete?: () => void
}

export function AccountAvatar({
  avatarUrl,
  username,
  editable = true,
  isBusy = false,
  onUpload,
  onDelete,
}: AccountAvatarProps) {
  const { t } = useLocale()
  const inputRef = useRef<HTMLInputElement>(null)
  const fallbackCharacter = username.trim().slice(0, 1).toUpperCase()

  const openFilePicker = () => {
    if (!isBusy) inputRef.current?.click()
  }

  return (
    <div className="flex shrink-0 flex-col items-center gap-2">
      <div className="relative">
        <Avatar className="size-20 border border-border-subtle bg-surface-muted shadow-sm sm:size-24">
          {avatarUrl && <AvatarImage src={avatarUrl} alt={username} className="object-cover" />}
          <AvatarFallback className="bg-primary/10 text-2xl font-semibold text-primary sm:text-3xl">
            {fallbackCharacter || <UserRound className="size-9" strokeWidth={1.7} aria-hidden="true" />}
          </AvatarFallback>
        </Avatar>
        {editable && (
          <button
            type="button"
            onClick={openFilePicker}
            disabled={isBusy}
            aria-label={t("my.editAvatar")}
            className="absolute -bottom-1 -right-1 flex size-8 items-center justify-center rounded-full border border-background bg-primary text-primary-foreground shadow-sm transition-[background-color,transform] hover:bg-primary/90 active:scale-95 disabled:pointer-events-none disabled:opacity-60"
          >
            {isBusy ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <Camera className="size-4" aria-hidden="true" />
            )}
          </button>
        )}
      </div>

      {editable && (
        <>
          <input
            ref={inputRef}
            type="file"
            accept={AVATAR_ALLOWED_IMAGE_ACCEPT}
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ""
              if (file) onUpload?.(file)
            }}
            disabled={isBusy}
          />
          <div className="flex flex-wrap items-center justify-center gap-1.5">
            <Button type="button" variant="ghost" size="sm" onClick={openFilePicker} disabled={isBusy}>
              {isBusy ? <LoaderCircle className="animate-spin" aria-hidden="true" /> : <Camera aria-hidden="true" />}
              {t("my.changeAvatar")}
            </Button>
            {avatarUrl && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={onDelete}
                disabled={isBusy}
                className="text-muted-foreground hover:text-foreground"
              >
                <RotateCcw aria-hidden="true" />
                {t("my.restoreAvatar")}
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  )
}
