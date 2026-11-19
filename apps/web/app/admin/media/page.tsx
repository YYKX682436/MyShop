"use client"

import { MediaLibrary } from "@/components/admin/media-library"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { useLocale } from "@/lib/context"

export default function AdminMediaPage() {
  const { t } = useLocale()
  return <AdminPage><PageHeader title={t("admin.media")} /><MediaLibrary /></AdminPage>
}
