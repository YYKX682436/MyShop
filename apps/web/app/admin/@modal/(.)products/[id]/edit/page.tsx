import { ProductWorkspaceRoute } from "@/components/admin/products/product-workspace-route"

export default async function EditProductModal({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ tab?: string }> }) {
  const { id } = await params
  const { tab } = await searchParams
  return <ProductWorkspaceRoute productId={id} initialTab={tab === "inventory" ? "inventory" : "basic"} />
}
