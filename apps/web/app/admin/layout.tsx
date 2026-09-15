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
        <SidebarInset className="min-w-0 overflow-x-hidden bg-transparent">
          <AdminHeader />
          <main className="min-w-0 px-4 py-6 sm:px-6 sm:py-8 lg:px-10 lg:py-10">{children}</main>
        </SidebarInset>
        {modal}
      </SidebarProvider>
    </AdminAttentionProvider>
  )
}
