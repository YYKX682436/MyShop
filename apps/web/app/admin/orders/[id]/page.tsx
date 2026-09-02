import { AdminOrderDetailRoute } from "@/components/admin/orders/order-detail-route"

export default async function AdminOrderDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return <AdminOrderDetailRoute orderId={id} presentation="full" />
}
