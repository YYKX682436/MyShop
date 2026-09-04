"use client"

import { useState } from "react"
import AdminUserDetailPage from "@/app/admin/users/[id]/page"
import { WorkspaceSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { useLocale } from "@/lib/context"

export function AdminUserDetailRoute({ userId }: { userId: string }) {
  const { t } = useLocale()
  const [dirty, setDirty] = useState(false)
  const close = useRouteOverlayClose(dirty)

  return (
    <WorkspaceSheet
      title={t("admin.userDetail")}
      description={userId}
      dirty={dirty}
      onClose={close}
      contentClassName="bg-surface p-0 sm:p-0"
    >
      <AdminUserDetailPage embedded onDirtyChange={setDirty} />
    </WorkspaceSheet>
  )
}
