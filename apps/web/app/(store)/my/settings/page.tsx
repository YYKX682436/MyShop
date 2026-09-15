"use client"

import Link from "next/link"
import { ArrowLeft, Check, Languages, Monitor, Moon, Sun, type LucideIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale, useTheme, type Theme } from "@/lib/context"
import type { Locale } from "@/lib/i18n"

export default function MySettingsPage() {
  const { t, locale, setLocale } = useLocale()
  const { theme, setTheme } = useTheme()

  const themeOptions: { value: Theme; label: string; icon: LucideIcon }[] = [
    { value: "light", label: t("theme.light"), icon: Sun },
    { value: "dark", label: t("theme.dark"), icon: Moon },
    { value: "system", label: t("theme.system"), icon: Monitor },
  ]

  const localeOptions: { value: Locale; label: string }[] = [
    { value: "zh", label: t("settings.chinese") },
    { value: "en", label: t("settings.english") },
  ]

  return (
    <div className="account-page max-w-2xl space-y-6">
      <header>
        <Link href="/my" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          {t("my.backToAccount")}
        </Link>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("settings.title")}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t("settings.description")}</p>
      </header>

      <div className="space-y-4">
        <SettingsChoiceGroup
          icon={Languages}
          title={t("settings.language")}
          description={t("settings.languageDescription")}
          options={localeOptions}
          value={locale}
          onChange={setLocale}
        />
        <SettingsChoiceGroup
          icon={Monitor}
          title={t("settings.appearance")}
          description={t("settings.appearanceDescription")}
          options={themeOptions}
          value={theme}
          onChange={setTheme}
        />
      </div>
    </div>
  )
}

interface SettingsChoiceGroupProps<T extends string> {
  icon: LucideIcon
  title: string
  description: string
  options: { value: T; label: string; icon?: LucideIcon }[]
  value: T
  onChange: (value: T) => void
}

function SettingsChoiceGroup<T extends string>({ icon: Icon, title, description, options, value, onChange }: SettingsChoiceGroupProps<T>) {
  return (
    <section className="settings-section" aria-labelledby={`${title}-heading`}>
      <header className="settings-section-header flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Icon className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 id={`${title}-heading`} className="text-base font-semibold text-foreground">{title}</h2>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">{description}</p>
        </div>
      </header>
      <div className="settings-section-content grid gap-2 sm:grid-cols-3">
        {options.map((option) => {
          const OptionIcon = option.icon
          const selected = option.value === value
          return (
            <button
              key={option.value}
              type="button"
              onClick={() => onChange(option.value)}
              aria-pressed={selected}
              className={cn(
                "flex min-h-11 items-center justify-between gap-2 rounded-control border px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                selected
                  ? "border-primary bg-primary/10 text-primary"
                  : "border-border-subtle bg-surface-muted text-muted-foreground hover:border-border-strong hover:text-foreground",
              )}
            >
              <span className="flex min-w-0 items-center gap-2">
                {OptionIcon && <OptionIcon className="size-4 shrink-0" aria-hidden="true" />}
                <span className="truncate">{option.label}</span>
              </span>
              {selected && <Check className="size-4 shrink-0" aria-hidden="true" />}
            </button>
          )
        })}
      </div>
    </section>
  )
}
