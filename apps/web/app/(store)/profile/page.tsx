"use client"

import React from "react"
import { useState } from "react"
import { User, Lock, Eye, EyeOff, Save } from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useAuth } from "@/lib/context"
import { isAdminRole, useRequireAuth } from "@/lib/hooks"
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
    <div className="mx-auto w-full max-w-2xl space-y-6">
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("profile.title")}</h1>
      </div>

      {/* Tab Navigation */}
      <div className="flex rounded-card border border-border-subtle bg-surface-muted p-1.5">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "flex flex-1 items-center justify-center gap-2 rounded-control px-3 py-2.5 text-sm font-medium transition-colors duration-180",
              activeTab === tab.key
                ? "bg-surface text-foreground shadow-sm"
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
        <div className="surface p-5 sm:p-6">
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
            <hr className="border-border-subtle" />
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-card border border-border-subtle bg-surface-muted p-3.5">
                <p className="text-xs text-muted-foreground">{t("profile.balance")}</p>
                <p className="text-2xl font-bold text-foreground">{user?.unlimited ? "∞" : formatYuan(user?.balance ?? 0)}</p>
              </div>
              <div className="rounded-card border border-border-subtle bg-surface-muted p-3.5">
                <p className="text-xs text-muted-foreground">{t("profile.userNumber")}</p>
                <p className="text-sm font-medium text-foreground">
                  {isAdminRole(user?.role) ? t("profile.administrator") : user?.user_no ?? "-"}
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
    <div className="surface p-5 sm:p-6">
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
              className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowOld(!showOld)}
              className="absolute right-2 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface"
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
              className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowNew(!showNew)}
              className="absolute right-2 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface"
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
            className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            required
          />
        </div>
        <button
          type="submit"
          disabled={isLoading}
          className="inline-flex h-11 items-center justify-center gap-2 rounded-control bg-primary text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50"
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
