"use client"

import { useCallback, useEffect, useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { adminProductApi, announcementApi, type AnnouncementChannel, type SiteAnnouncement } from "@/services/api"
import { formatDateTime } from "@/lib/utils"

export function AnnouncementManagement({ scope }: { scope: "admin" | "subsite" }) {
  const [items, setItems] = useState<SiteAnnouncement[]>([])
  const [channels, setChannels] = useState<AnnouncementChannel[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [title, setTitle] = useState("")
  const [body, setBody] = useState("")
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const selected = items.find(item => item.id === selectedId)
  const dirty = selected ? selected.title !== title || selected.body !== body : !!title || !!body

  const load = useCallback(async () => {
    const result = await announcementApi.manage(scope).list()
    setItems(result.announcements)
    setChannels(result.channels)
    return result.announcements
  }, [scope])

  useEffect(() => {
    let active = true
    setLoading(true)
    announcementApi.manage(scope).list().then(result => {
      if (!active) return
      setItems(result.announcements); setChannels(result.channels); setError("")
    }).catch(e => { if (active) setError(e instanceof Error ? e.message : "加载失败") })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [scope])

  function select(item: SiteAnnouncement | null) {
    if (dirty && !window.confirm("放弃未保存的更改？")) return
    setSelectedId(item?.id ?? null); setTitle(item?.title ?? ""); setBody(item?.body ?? "")
  }

  async function save(event: React.FormEvent) {
    event.preventDefault(); setBusy(true)
    try {
      const api = announcementApi.manage(scope)
      const id = selectedId ?? (await api.create(title, body)).id
      if (selectedId) await api.edit(id, title, body)
      const updated = await load()
      const saved = updated.find(item => item.id === id)
      if (saved) { setSelectedId(id); setTitle(saved.title); setBody(saved.body) }
      toast.success("已保存")
    } catch (e) { toast.error(e instanceof Error ? e.message : "保存失败") }
    finally { setBusy(false) }
  }

  async function change(action: () => Promise<unknown>, message: string) {
    setBusy(true)
    try { await action(); await load(); toast.success(message) }
    catch (e) { toast.error(e instanceof Error ? e.message : "操作失败") }
    finally { setBusy(false) }
  }

  if (loading) return <p role="status" className="text-sm text-muted-foreground">加载中…</p>
  if (error) return <p role="alert" className="text-sm text-destructive">{error}</p>
  return <div className="max-w-4xl space-y-5">
    <div className="grid gap-3 sm:grid-cols-2">
      {([ ["BANNER", "顶栏"], ["POPUP", "弹窗"] ] as const).map(([channel, label]) => {
        const setting = channels.find(item => item.channel === channel)
        return <label key={channel} className="space-y-1 text-sm"><span>{label}</span>
          <select className="block min-h-11 w-full rounded-control border border-input bg-background px-3 text-base"
            disabled={busy} value={setting?.enabled ? setting.announcement_id ?? "" : ""}
            onChange={e => { const value = e.target.value; void change(() => announcementApi.manage(scope).channel(channel, value || null, !!value), "展示设置已更新") }}>
            <option value="">关闭</option>
            {items.filter(item => item.status === "PUBLISHED").map(item => <option value={item.id} key={item.id}>{item.title}</option>)}
          </select>
        </label>
      })}
    </div>
    <div className="flex items-center justify-between gap-3"><h3 className="font-semibold">公告</h3><Button type="button" variant="outline" onClick={() => select(null)} disabled={busy}>新建公告</Button></div>
    <div className="grid gap-4 md:grid-cols-[minmax(12rem,1fr)_minmax(0,2fr)]">
      <ul className="max-h-80 overflow-auto rounded-control border border-border-subtle">
        {items.map(item => <li key={item.id}><button type="button" onClick={() => select(item)} disabled={busy}
          className={`w-full border-b border-border-subtle px-3 py-3 text-left last:border-b-0 ${selectedId === item.id ? "bg-primary/10" : "hover:bg-surface-muted"}`}>
          <span className="block truncate text-sm font-medium">{item.title}</span>
          <span className="text-xs text-muted-foreground">{item.status === "PUBLISHED" ? "已发布" : item.status === "DRAFT" ? "待发布" : "已撤下"}{item.published_at ? ` · ${formatDateTime(item.published_at)}` : ""}</span>
        </button></li>)}
        {!items.length && <li className="p-4 text-sm text-muted-foreground">暂无公告</li>}
      </ul>
      <form onSubmit={save}><fieldset disabled={busy} className="space-y-3"><legend className="sr-only">编辑公告</legend>
        <label className="block space-y-1 text-sm"><span>标题</span><Input required maxLength={200} value={title} onChange={e => setTitle(e.target.value)} /></label>
        {scope === "admin" ? <div className="space-y-1 text-sm"><label htmlFor="announcement-body">正文</label><MarkdownEditor id="announcement-body" maxLength={100000} value={body} onChange={setBody} uploadImage={file => adminProductApi.uploadImage(file).then(result => result.url)} /></div>
          : <label className="block space-y-1 text-sm"><span>正文</span><Textarea required maxLength={100000} rows={8} value={body} onChange={e => setBody(e.target.value)} /></label>}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={busy || !dirty}>保存</Button>
          {selected && <Button type="button" variant="outline" disabled={busy || dirty}
            onClick={() => void change(() => announcementApi.manage(scope).publish(selected.id), "公告已发布")}>{selected.status === "PUBLISHED" ? "重新发布" : "发布"}</Button>}
          {selected?.status === "PUBLISHED" && <Button type="button" variant="outline" disabled={busy || dirty}
            onClick={() => { if (window.confirm("撤下这条公告？")) void change(() => announcementApi.manage(scope).withdraw(selected.id), "公告已撤下") }}>撤下公告</Button>}
        </div>
      </fieldset></form>
    </div>
  </div>
}
