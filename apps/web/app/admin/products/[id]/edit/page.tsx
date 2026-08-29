"use client"

import { use } from "react"

import { ProductEditorPage } from "@/components/admin/products/product-editor-page"

export default function EditProductPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return <ProductEditorPage productId={id} />
}
