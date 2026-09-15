"use client"

import { Fragment, useCallback, useEffect, useRef, useState } from "react"
import { CornerDownRight, Edit, FolderTree, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  ConfirmAlert,
  DataTableShell,
  FormSection,
  PageHeader,
  PageToolbar,
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

type CategoryFormData = {
  name: string
  sortOrder: string
  parentId: string
}

export default function AdminCategoriesPage() {
  const { t } = useLocale()
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Category | null>(null)
  const [formData, setFormData] = useState<CategoryFormData>({ name: "", sortOrder: "", parentId: "" })
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

  const openCreate = (parentId = "") => {
    setEditing(null)
    setFormData({ name: "", sortOrder: "", parentId })
    setDialogOpen(true)
  }

  const openEdit = (category: Category) => {
    setEditing(category)
    setFormData({
      name: category.name,
      sortOrder: String(category.sort_order),
      parentId: category.parent_id ?? "",
    })
    setDialogOpen(true)
  }

  const resetDialog = () => {
    setEditing(null)
    setFormData({ name: "", sortOrder: "", parentId: "" })
  }

  const closeDialog = () => {
    if (saving) return
    setDialogOpen(false)
    resetDialog()
  }

  const closeDialogAfterSuccess = () => {
    setDialogOpen(false)
    resetDialog()
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
      const parentId = formData.parentId || null
      if (editing) {
        await requestOrThrow(() => adminCategoryApi.update(editing.id, {
          name,
          sort_order: Number.isNaN(sortOrder) ? undefined : sortOrder,
          parent_id: parentId,
        }))
      } else {
        await requestOrThrow(() => adminCategoryApi.create({
          name,
          sort_order: Number.isNaN(sortOrder) ? undefined : sortOrder,
          parent_id: parentId,
        }))
      }
      toast.success(t("admin.categorySaved"))
      closeDialogAfterSuccess()
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
      toast.success(t("admin.categoryDeleted"))
      setDeleteCategory(null)
      await loadCategories()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setDeleting(false)
    }
  }

  const renderCategoryRow = (category: Category, depth: 0 | 1) => {
    const isRoot = depth === 0
    return (
      <TableRow key={category.id}>
        <TableCell>
          <div className={isRoot ? "flex items-center gap-3" : "flex items-center gap-3 pl-10"}>
            <span className={isRoot
              ? "flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10"
              : "flex h-8 w-8 items-center justify-center rounded-lg bg-surface-muted text-muted-foreground"}>
              {isRoot
                ? <FolderTree className="h-4 w-4 text-primary" aria-hidden="true" />
                : <CornerDownRight className="h-4 w-4" aria-hidden="true" />}
            </span>
            <span className="min-w-0">
              <span className="block font-medium text-foreground">{category.name}</span>
              <span className="block text-xs text-muted-foreground">
                {isRoot ? t("admin.categoryRootLevel") : t("admin.categoryChildLevel")}
              </span>
            </span>
          </div>
        </TableCell>
        <TableCell>{category.sort_order}</TableCell>
        <TableCell>
          <div className="flex justify-end gap-1">
            {isRoot && (
              <Button type="button" variant="outline" size="sm" title={t("admin.addSubcategory")} onClick={() => openCreate(category.id)}>
                <Plus className="mr-1.5 h-3.5 w-3.5" aria-hidden="true" />{t("admin.addSubcategory")}
              </Button>
            )}
            <Button type="button" variant="ghost" size="icon" title={t("admin.edit")} aria-label={`${t("admin.edit")}: ${category.name}`} onClick={() => openEdit(category)}>
              <Edit className="h-4 w-4" aria-hidden="true" />
            </Button>
            <Button type="button" variant="ghost" size="icon" title={t("admin.delete")} aria-label={`${t("admin.delete")}: ${category.name}`} onClick={() => setDeleteCategory(category)}>
              <Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" />
            </Button>
          </div>
        </TableCell>
      </TableRow>
    )
  }

  const movingRootWithChildren = Boolean(editing && editing.parent_id === null && editing.children.length > 0)
  const parentOptions = categories.filter((category) => category.id !== editing?.id)

  return (
    <AdminPage>
      <PageHeader
        title={t("admin.categories")}
        description={t("admin.manageCategories")}
      />

      <PageToolbar>
        <span className="text-sm text-muted-foreground">{t("admin.categoryRootLevel")}</span>
        <Button type="button" onClick={() => openCreate()}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addRootCategory")}</Button>
      </PageToolbar>

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
            {!loading && !error && categories.map((root) => (
              <Fragment key={root.id}>
                {renderCategoryRow(root, 0)}
                {root.children.map((child) => renderCategoryRow(child, 1))}
              </Fragment>
            ))}
          </TableBody>
        </Table>
      </DataTableShell>

      <Dialog open={dialogOpen} onOpenChange={(open) => { if (open) setDialogOpen(true); else closeDialog() }}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>{editing ? t("admin.editCategory") : formData.parentId ? t("admin.addSubcategory") : t("admin.addRootCategory")}</DialogTitle></DialogHeader>
          <FormSection title={t("admin.category")} className="border-0 bg-transparent p-0 shadow-none">
            <div className="flex flex-col gap-4">
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {t("admin.categoryNameReq")}
                <Input ref={nameRef} value={formData.name} placeholder={t("admin.categoryNamePlaceholder")} onChange={(event) => setFormData((current) => ({ ...current, name: event.target.value }))} />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {t("admin.categoryParent")}
                <select
                  className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal disabled:cursor-not-allowed disabled:opacity-60"
                  value={formData.parentId}
                  onChange={(event) => setFormData((current) => ({ ...current, parentId: event.target.value }))}
                  disabled={movingRootWithChildren}
                  aria-describedby={movingRootWithChildren ? "category-parent-hint" : undefined}
                >
                  <option value="">{t("admin.categoryRootOption")}</option>
                  {parentOptions.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                </select>
                {movingRootWithChildren && <span id="category-parent-hint" className="text-xs font-normal text-muted-foreground">{t("admin.categoryMoveDisabled")}</span>}
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
