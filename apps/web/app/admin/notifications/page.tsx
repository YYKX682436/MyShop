"use client"

import { AdminPage, PageHeader } from "@/components/admin/shared"
import { NotificationManagement } from "@/components/shared/announcement-management"
import { useLocale } from "@/lib/context"

export default function AdminNotificationsPage() {
  const { t } = useLocale()
  return <AdminPage className="max-w-none gap-4">
    <PageHeader title={t("admin.notifications")} />
    <NotificationManagement scope="admin" />
  </AdminPage>
}
