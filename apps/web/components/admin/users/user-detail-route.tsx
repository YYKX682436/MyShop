"use client"

import { useState } from "react"
import AdminUserDetailPage from "@/app/admin/users/[id]/page"
import { InspectorSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"

export function AdminUserDetailRoute({ userId }: { userId: string }) {
  const { t } = useLocale()
  const [dirty, setDirty] = useState(false)
  const close = useRouteOverlayClose(dirty)

  return (
    <InspectorSheet
      title={t("admin.userDetail")}
      description={userId}
      dirty={dirty}
      onClose={close}
      contentClassName="bg-surface-muted/35 p-0 sm:p-0"
      footer={<div className="flex justify-end"><Button type="button" variant="outline" onClick={close}>{t("common.close")}</Button></div>}
    >
      <AdminUserDetailPage embedded onDirtyChange={setDirty} />
    </InspectorSheet>
  )
}
