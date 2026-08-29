"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Edit, FolderTree, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  ConfirmAlert,
  DataTableShell,
  FormSection,
  PageHeader,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useLocale } from "@/lib/context"
import { adminCategoryApi, requestOrThrow } from "@/services/api"
import type { Category } from "@/types"

export default function AdminCategoriesPage() {
  const { t } = useLocale()
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Category | null>(null)
  const [formData, setFormData] = useState({ name: "", sortOrder: "" })
  const [saving, setSaving] = useState(false)
  const [deleteCategory, setDeleteCategory] = useState<Category | null>(null)
  const [deleting, setDeleting] = useState(false)
  const nameRef = useRef<HTMLInputElement>(null)

  const loadCategories = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      setCategories(await requestOrThrow(() => adminCategoryApi.getList()))
    } catch {
      setCategories([])
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadCategories() }, [loadCategories])

  const openCreate = () => {
    setEditing(null)
    setFormData({ name: "", sortOrder: "" })
    setDialogOpen(true)
  }

  const openEdit = (category: Category) => {
    setEditing(category)
    setFormData({ name: category.name, sortOrder: String(category.sort_order) })
    setDialogOpen(true)
  }

  const closeDialog = () => {
    if (saving) return
    setDialogOpen(false)
    setEditing(null)
    setFormData({ name: "", sortOrder: "" })
  }

  const handleSave = async () => {
    const name = formData.name.trim()
    if (!name) {
      toast.error(t("admin.categoryNameRequired"))
      nameRef.current?.focus()
      return
    }

    setSaving(true)
    try {
      const sortOrder = Number.parseInt(formData.sortOrder, 10)
      if (editing) {
        await requestOrThrow(() => adminCategoryApi.update(editing.id, { name, sort_order: Number.isNaN(sortOrder) ? undefined : sortOrder }))
      } else {
        await requestOrThrow(() => adminCategoryApi.create({ name, sort_order: Number.isNaN(sortOrder) ? undefined : sortOrder }))
      }
      toast.success(t("admin.productSaved"))
      closeDialog()
      await loadCategories()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!deleteCategory) return
    setDeleting(true)
    try {
      await requestOrThrow(() => adminCategoryApi.delete(deleteCategory.id))
      toast.success(t("admin.productDeleted"))
      setDeleteCategory(null)
      await loadCategories()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.categories")}
        description={t("admin.manageCategories")}
        actions={<Button type="button" onClick={openCreate}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addCategory")}</Button>}
      />

      <DataTableShell>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("admin.category")}</TableHead>
              <TableHead>{t("admin.sortOrderLabel")}</TableHead>
              <TableHead className="text-right">{t("admin.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && <TableLoadingState colSpan={3} />}
            {!loading && error && <TableErrorState colSpan={3} onRetry={() => void loadCategories()} />}
            {!loading && !error && categories.length === 0 && <TableEmptyState colSpan={3}>{t("admin.noCategoryData")}</TableEmptyState>}
            {!loading && !error && categories.map((category) => (
              <TableRow key={category.id}>
                <TableCell>
                  <div className="flex items-center gap-3">
                    <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10"><FolderTree className="h-4 w-4 text-primary" aria-hidden="true" /></span>
                    <span className="font-medium text-foreground">{category.name}</span>
                  </div>
                </TableCell>
                <TableCell>{category.sort_order}</TableCell>
                <TableCell>
                  <div className="flex justify-end gap-1">
                    <Button type="button" variant="ghost" size="icon" title={t("admin.edit")} aria-label={t("admin.edit")} onClick={() => openEdit(category)}><Edit className="h-4 w-4" aria-hidden="true" /></Button>
                    <Button type="button" variant="ghost" size="icon" title={t("admin.delete")} aria-label={t("admin.delete")} onClick={() => setDeleteCategory(category)}><Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DataTableShell>

      <Dialog open={dialogOpen} onOpenChange={(open) => { if (open) setDialogOpen(true); else closeDialog() }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{editing ? t("admin.editCategory") : t("admin.addCategory")}</DialogTitle></DialogHeader>
          <FormSection title={t("admin.category")} className="border-0 bg-transparent p-0 shadow-none">
            <div className="flex flex-col gap-4">
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {t("admin.categoryNameReq")}
                <Input ref={nameRef} value={formData.name} placeholder={t("admin.categoryNamePlaceholder")} onChange={(event) => setFormData((current) => ({ ...current, name: event.target.value }))} />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {t("admin.sortOrderLabel")}
                <Input type="number" value={formData.sortOrder} placeholder={t("admin.sortOrderHint")} onChange={(event) => setFormData((current) => ({ ...current, sortOrder: event.target.value }))} />
              </label>
            </div>
          </FormSection>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={closeDialog} disabled={saving}>{t("admin.cancel")}</Button>
            <Button type="button" onClick={() => void handleSave()} disabled={saving}>{saving ? t("admin.saving") : t("admin.save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmAlert
        open={deleteCategory !== null}
        onOpenChange={(open) => { if (!open && !deleting) setDeleteCategory(null) }}
        title={t("admin.deleteConfirm")}
        description={deleteCategory ? `${deleteCategory.name} · ${t("admin.deleteCategoryMsg")}` : undefined}
        confirmLabel={t("admin.delete")}
        cancelLabel={t("admin.cancel")}
        destructive
        loading={deleting}
        onConfirm={() => void handleDelete()}
      />
    </AdminPage>
  )
}
