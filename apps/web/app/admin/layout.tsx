"use client"

import React from "react"
import { AdminSidebar } from "@/components/layout/admin-sidebar"
import { AdminHeader } from "@/components/layout/admin-header"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"
import { useRequireAdmin } from "@/lib/hooks"

export default function AdminLayout({ children, modal }: { children: React.ReactNode; modal: React.ReactNode }) {
  const user = useRequireAdmin()

  if (!user) return null

  return (
    <SidebarProvider className="min-h-screen overflow-x-hidden bg-background">
      <AdminSidebar />
      <SidebarInset className="min-w-0 overflow-x-hidden">
        <AdminHeader />
        <main className="min-w-0 px-4 py-6 sm:px-6 sm:py-7 lg:px-8 lg:py-9">{children}</main>
      </SidebarInset>
      {modal}
    </SidebarProvider>
  )
}
