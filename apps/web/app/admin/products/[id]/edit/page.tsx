"use client"

import { use } from "react"

import { ProductEditorPage } from "@/components/admin/products/product-editor-page"

export default function EditProductPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ tab?: string }> }) {
  const { id } = use(params)
  const { tab } = use(searchParams)
  return <ProductEditorPage productId={id} initialTab={tab === "inventory" ? "inventory" : "basic"} />
}
