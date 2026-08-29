"use client"

import React from "react"
import { useState } from "react"
import { User, Lock, Eye, EyeOff, Save } from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useAuth } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, requestOrThrow, getApiErrorMessage } from "@/services/api"
import { cn, formatYuan } from "@/lib/utils"

type Tab = "info" | "password"

export default function ProfilePage() {
  const { t } = useLocale()
  const currentUser = useRequireAuth()
  const { user } = useAuth()
  const [activeTab, setActiveTab] = useState<Tab>("info")

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: "info", label: t("profile.info"), icon: <User className="h-4 w-4" /> },
    { key: "password", label: t("profile.changePassword"), icon: <Lock className="h-4 w-4" /> },
  ]

  if (!currentUser) return null

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-bold text-foreground">{t("profile.title")}</h1>

      {/* Tab Navigation */}
      <div className="mb-6 flex rounded-lg bg-muted p-1">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors",
              activeTab === tab.key
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {tab.icon}
            <span className="hidden sm:inline">{tab.label}</span>
          </button>
        ))}
      </div>

      {/* Info Tab */}
      {activeTab === "info" && (
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-4">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                <User className="h-8 w-8 text-primary" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-card-foreground">
                  {user?.username || "Guest"}
                </h2>
                <p className="text-sm text-muted-foreground">
                  {user?.email || "Not logged in"}
                </p>
              </div>
            </div>
            <hr className="border-border" />
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-lg bg-muted/50 p-3">
                <p className="text-xs text-muted-foreground">{t("profile.balance")}</p>
                <p className="text-2xl font-bold text-foreground">{formatYuan(user?.balance ?? 0)}</p>
              </div>
              <div className="rounded-lg bg-muted/50 p-3">
                <p className="text-xs text-muted-foreground">{t("profile.userNumber")}</p>
                <p className="text-sm font-medium text-foreground">
                  {user?.user_no ?? "-"}
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Password Tab */}
      {activeTab === "password" && <ChangePasswordForm />}

    </div>
  )
}

function ChangePasswordForm() {
  const { t } = useLocale()
  const [form, setForm] = useState({
    oldPassword: "",
    newPassword: "",
    confirmNew: "",
  })
  const [showOld, setShowOld] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [isLoading, setIsLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (form.newPassword !== form.confirmNew) {
      toast.error(t("profile.passwordMismatch"))
      return
    }
    setIsLoading(true)
    try {
      await requestOrThrow(() => userApi.updatePassword({
          old_password: form.oldPassword,
          new_password: form.newPassword,
        }))
      toast.success(t("common.success"))
      setForm({ oldPassword: "", newPassword: "", confirmNew: "" })
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.oldPassword")}
          </label>
          <div className="relative">
            <input
              type={showOld ? "text" : "password"}
              value={form.oldPassword}
              onChange={(e) => setForm({ ...form, oldPassword: e.target.value })}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowOld(!showOld)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            >
              {showOld ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.newPassword")}
          </label>
          <div className="relative">
            <input
              type={showNew ? "text" : "password"}
              value={form.newPassword}
              onChange={(e) => setForm({ ...form, newPassword: e.target.value })}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowNew(!showNew)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            >
              {showNew ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.confirmNew")}
          </label>
          <input
            type="password"
            value={form.confirmNew}
            onChange={(e) => setForm({ ...form, confirmNew: e.target.value })}
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            required
          />
        </div>
        <button
          type="submit"
          disabled={isLoading}
          className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          {isLoading ? (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
          ) : (
            <Save className="h-4 w-4" />
          )}
          {t("profile.save")}
        </button>
      </form>
    </div>
  )
}
