import { AdminUserDetailRoute } from "@/components/admin/users/user-detail-route"

export default async function AdminUserDetailModal({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return <AdminUserDetailRoute userId={id} />
}
