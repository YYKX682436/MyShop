"use client"

import { useCallback, useEffect, useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { MediaPicker } from "@/components/admin/media-library"
import { announcementApi, type SiteAnnouncement, type NotificationLevel } from "@/services/api"
import { uploadAdminImage } from "@/lib/admin-image-upload"
import { useLocale } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"

type Audience = "ALL" | "LEVELS"

function newRequestKey() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("")
}

function audienceName(item: SiteAnnouncement, levels: NotificationLevel[]) {
  if (item.audience_type !== "LEVELS") return "所有人"
  const names = (item.audience_level_ids ?? []).map(id => levels.find(level => level.id === id)?.name ?? "已失效等级")
  return names.length ? names.join("、") : "等级范围无效"
}

export function NotificationManagement({ scope, onUploadingChange }: { scope: "admin" | "subsite"; onUploadingChange?: (uploading: boolean) => void }) {
  const { locale } = useLocale()
  const [items, setItems] = useState<SiteAnnouncement[]>([])
  const [levels, setLevels] = useState<NotificationLevel[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [requestKey, setRequestKey] = useState(newRequestKey)
  const [title, setTitle] = useState("")
  const [body, setBody] = useState("")
  const [audience, setAudience] = useState<Audience>("ALL")
  const [audienceLevels, setAudienceLevels] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const selected = items.find(item => item.id === selectedId)
  const sortedAudienceLevels = [...audienceLevels].sort()
  const savedAudienceLevels = [...(selected?.audience_level_ids ?? [])].sort()
  const dirty = selected
    ? (selected.title ?? "") !== title || selected.body !== body
      || (selected.audience_type ?? "ALL") !== audience
      || JSON.stringify(sortedAudienceLevels) !== JSON.stringify(savedAudienceLevels)
    : !!title || !!body || audience !== "ALL" || audienceLevels.length > 0

  const load = useCallback(async (signal?: AbortSignal) => {
    const result = await announcementApi.manage(scope).list(signal)
    if (signal?.aborted) return result
    setItems(result.announcements); setLevels(result.levels)
    return result
  }, [scope])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError("")
    load(controller.signal).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "加载失败") })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [load])

  function select(item: SiteAnnouncement | null) {
    if (uploading) { toast.error("请等待图片上传完成"); return }
    if (dirty && !window.confirm("放弃未保存的更改？")) return
    setSelectedId(item?.id ?? null)
    setRequestKey(newRequestKey())
    setTitle(item?.title ?? ""); setBody(item?.body ?? "")
    setAudience(item?.audience_type ?? "ALL"); setAudienceLevels(item?.audience_level_ids ?? [])
  }

  async function save(publish: boolean) {
    if (uploading) { toast.error("请等待图片上传完成"); return }
    if (!body.trim() || body.length > 100000) { toast.error("请填写不超过 100000 个字符的正文"); return }
    if (audience === "LEVELS" && audienceLevels.length === 0) { toast.error("请选择至少一个等级"); return }
    setBusy(true)
    try {
      const id = selectedId
      const result = await announcementApi.manage(scope).save(id, {
        requestKey: id ? undefined : requestKey,
        title: title.trim() || null,
        body,
        audienceType: audience,
        audienceLevelIds: audience === "LEVELS" ? sortedAudienceLevels : [],
        publish,
      })
      const updated = await load()
      setSelectedId(result.id); setRequestKey(newRequestKey())
      const saved = updated.announcements.find(item => item.id === result.id)
      if (saved) { setTitle(saved.title ?? ""); setBody(saved.body); setAudience(saved.audience_type ?? "ALL"); setAudienceLevels(saved.audience_level_ids ?? []) }
      else if (id === result.id) { setTitle(title.trim()); setBody(body) }
      toast.success(publish ? "通知已发布" : "草稿已保存")
    } catch (e) { toast.error(e instanceof Error ? e.message : "保存失败") }
    finally { setBusy(false) }
  }

  async function change(action: () => Promise<unknown>, message: string) {
    setBusy(true)
    try { await action(); await load(); toast.success(message) }
    catch (e) { toast.error(e instanceof Error ? e.message : "操作失败") }
    finally { setBusy(false) }
  }

  if (loading) return <div role="status" aria-label="加载中" className="h-20 animate-pulse rounded-control bg-muted" />
  if (error) return <p role="alert" className="text-sm text-destructive">{error}</p>

  return <div className="grid min-w-0 gap-5 md:grid-cols-[minmax(13rem,0.85fr)_minmax(0,1.6fr)]">
    <section className="min-w-0 space-y-3" aria-label="通知历史">
      <div className="flex items-center justify-between gap-2"><h3 className="font-semibold">通知</h3><Button type="button" variant="outline" onClick={() => select(null)} disabled={busy || uploading}>新建</Button></div>
      <ul className="max-h-[32rem] overflow-auto rounded-control border border-border-subtle">
        {items.map(item => <li key={item.id}>
          <button type="button" onClick={() => select(item)} disabled={busy || uploading}
            className={`w-full min-w-0 border-b border-border-subtle px-3 py-3 text-left last:border-b-0 ${selectedId === item.id ? "bg-primary/10" : "hover:bg-surface-muted"}`}>
            <span className="block truncate text-sm font-medium">{item.title || "无标题通知"}</span>
            <span className="mt-1 block truncate text-xs text-muted-foreground">{item.status === "PUBLISHED" ? "已发布" : item.status === "DRAFT" ? "草稿" : "已撤下"} · {audienceName(item, levels)}</span>
            <span className="mt-1 block text-xs text-muted-foreground">{item.published_at ? formatDateTime(item.published_at) : "发布时间未知"}</span>
          </button>
        </li>)}
        {!items.length && <li className="p-4 text-sm text-muted-foreground">暂无通知</li>}
      </ul>
    </section>

    <form onSubmit={event => { event.preventDefault(); void save(false) }} className="min-w-0">
      <fieldset disabled={busy} className="min-w-0 space-y-4">
        <legend className="sr-only">编辑通知</legend>
        <label className="block space-y-1 text-sm"><span>标题（可选）</span><Input maxLength={200} value={title} onChange={event => setTitle(event.target.value)} /></label>
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">范围</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
            <label className="inline-flex min-h-11 items-center gap-2"><input type="radio" name="notification-audience" checked={audience === "ALL"} onChange={() => { setAudience("ALL"); setAudienceLevels([]) }} />所有人（含游客）</label>
            <label className="inline-flex min-h-11 items-center gap-2"><input type="radio" name="notification-audience" checked={audience === "LEVELS"} onChange={() => setAudience("LEVELS")} />指定等级</label>
          </div>
          {audience === "LEVELS" && <div className="grid gap-x-4 gap-y-1 rounded-control border border-border-subtle p-3 sm:grid-cols-2">
            {levels.map(level => <label key={level.id} className="inline-flex min-h-10 min-w-0 items-center gap-2 text-sm">
              <input type="checkbox" checked={audienceLevels.includes(level.id)} onChange={event => setAudienceLevels(current => event.target.checked ? [...current, level.id] : current.filter(id => id !== level.id))} />
              <span className="min-w-0 break-words">{level.name}</span>
            </label>)}
            {!levels.length && <p className="text-sm text-muted-foreground">没有可用等级</p>}
          </div>}
        </fieldset>
        <div className="min-w-0 space-y-1 text-sm"><label htmlFor="notification-body">正文</label><MarkdownEditor
          key={selectedId || "new"} id="notification-body" maxLength={100000} value={body} onChange={setBody}
          onUploadingChange={next => { setUploading(next); onUploadingChange?.(next) }}
          uploadImage={scope === "admin" && audience === "ALL" ? file => uploadAdminImage(file, locale).then(result => result.url) : undefined}
          renderMediaPicker={scope === "admin" ? props => <MediaPicker disabled={props.disabled} onOpen={props.onOpen}
            onSelect={item => { if (item.url) props.onSelect({ name: item.name, url: item.url }) }} /> : undefined} />
          {audience === "LEVELS" && <p className="text-xs text-muted-foreground">等级通知可选用已公开的商品素材或站外图片；新上传图片会公开访问。</p>}
        </div>
        <div className="flex flex-wrap gap-2 border-t border-border-subtle pt-4">
          <Button type="submit" disabled={busy || uploading || !dirty || !body.trim()}>保存草稿</Button>
          {(!selected || selected.status === "DRAFT") && <Button type="button" variant="outline" disabled={busy || uploading || !body.trim() || (!selected && !dirty)} onClick={() => void save(true)}>{busy ? "发布中…" : selected ? "保存并发布" : "直接发布"}</Button>}
          {selected?.status === "PUBLISHED" && dirty && <span className="self-center text-xs text-muted-foreground">保存修改不会改变发布时间和已读状态</span>}
          {selected?.status === "PUBLISHED" && <Button type="button" variant="outline" disabled={busy || dirty}
            onClick={() => { if (window.confirm("撤下这条通知？")) void change(() => announcementApi.manage(scope).withdraw(selected.id), "通知已撤下") }}>撤下</Button>}
        </div>
      </fieldset>
    </form>
  </div>
}
