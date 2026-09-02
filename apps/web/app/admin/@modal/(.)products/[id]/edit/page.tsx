import { ProductWorkspaceRoute } from "@/components/admin/products/product-workspace-route"

export default async function EditProductModal({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return <ProductWorkspaceRoute productId={id} />
}
