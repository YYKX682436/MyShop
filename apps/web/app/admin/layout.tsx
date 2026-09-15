"use client"

import React from "react"
import { AdminSidebar } from "@/components/layout/admin-sidebar"
import { AdminHeader } from "@/components/layout/admin-header"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"
import { useRequireAdmin } from "@/lib/hooks"
import { AdminAttentionProvider } from "@/lib/admin-attention"

export default function AdminLayout({ children, modal }: { children: React.ReactNode; modal: React.ReactNode }) {
  const user = useRequireAdmin()

  if (!user) return null

  return (
    <AdminAttentionProvider>
      <SidebarProvider className="admin-workspace min-h-screen overflow-x-hidden">
        <AdminSidebar />
        <SidebarInset className="admin-workspace-main min-w-0 overflow-x-hidden bg-transparent">
          <AdminHeader />
          <div className="admin-scroll-region">
            <main className="admin-page-main min-w-0 px-4 pb-8 pt-5 sm:px-6 sm:pb-10 sm:pt-7 lg:px-10 lg:pb-12 lg:pt-9">{children}</main>
          </div>
        </SidebarInset>
        {modal}
      </SidebarProvider>
    </AdminAttentionProvider>
  )
}
