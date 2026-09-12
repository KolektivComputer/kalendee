<script lang="ts">
  import { router, useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import type { ReminderInstanceOut, UpcomingRemindersIn } from "../page-types"
  import {
    BROWSER_NOTIFICATIONS_KEY,
    FIRED_REMINDERS_KEY,
    formatOffset,
    formatReminderWhen,
  } from "../reminders"
  import { clientTimeZone, instantToZoned } from "../time"

  const POLL_MS = 60_000
  const DUE_WINDOW_MS = 90_000
  const FIRED_RETENTION_MS = 7 * 24 * 60 * 60 * 1000

  const upcoming = useAction<UpcomingRemindersIn, ReminderInstanceOut[]>("kalendee.upcomingReminders", {
    reload: false,
  })

  function enabled(): boolean {
    if (typeof Notification === "undefined" || Notification.permission !== "granted") return false
    try {
      return localStorage.getItem(BROWSER_NOTIFICATIONS_KEY) === "1"
    } catch {
      return false
    }
  }

  function dedupeKey(instance: ReminderInstanceOut): string {
    return `${instance.eventId}:${instance.start}:${instance.offsetSeconds}`
  }

  function readFired(): Record<string, number> {
    try {
      const raw = localStorage.getItem(FIRED_REMINDERS_KEY)
      if (!raw) return {}
      const parsed: unknown = JSON.parse(raw)
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {}
      const fired: Record<string, number> = {}
      for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
        if (typeof value === "number" && Number.isFinite(value)) fired[key] = value
      }
      return fired
    } catch {
      return {}
    }
  }

  function writeFired(fired: Record<string, number>) {
    try {
      localStorage.setItem(FIRED_REMINDERS_KEY, JSON.stringify(fired))
    } catch {}
  }

  function pruneFired(fired: Record<string, number>, now: number): boolean {
    let pruned = false
    for (const [key, at] of Object.entries(fired)) {
      if (now - at > FIRED_RETENTION_MS) {
        delete fired[key]
        pruned = true
      }
    }
    return pruned
  }

  function notificationBody(instance: ReminderInstanceOut): string {
    const when = formatReminderWhen(instance.start, instance.allDay, clientTimeZone())
    if (instance.offsetSeconds <= 0) return `Starting now · ${when}`
    return `${formatOffset(instance.offsetSeconds)} · ${when}`
  }

  function fire(instance: ReminderInstanceOut, key: string) {
    if (typeof Notification === "undefined" || Notification.permission !== "granted") return
    try {
      const notification = new Notification(instance.title, {
        body: notificationBody(instance),
        tag: key,
      })
      notification.onclick = () => {
        try {
          window.focus()
        } catch {}
        try {
          notification.close()
        } catch {}
        const date = instantToZoned(instance.start, clientTimeZone()).date
        const href = `/?date=${date}`
        void router.visit(href).catch(() => window.location.assign(href))
      }
    } catch {}
  }

  $effect(() => {
    let disposed = false

    async function poll() {
      if (disposed || !enabled()) return
      let instances: ReminderInstanceOut[]
      try {
        instances = await untrack(() => upcoming.mutateAsync({ hours: 48 }))
      } catch {
        return
      }
      if (disposed) return

      const now = Date.now()
      const fired = readFired()
      const due: ReminderInstanceOut[] = []
      for (const instance of instances) {
        const remindAt = Date.parse(instance.remindAt)
        if (!Number.isFinite(remindAt)) continue
        if (remindAt - now > DUE_WINDOW_MS) continue
        const key = dedupeKey(instance)
        if (fired[key]) continue
        fired[key] = now
        due.push(instance)
      }
      const pruned = pruneFired(fired, now)
      if (due.length > 0 || pruned) writeFired(fired)
      for (const instance of due) fire(instance, dedupeKey(instance))
    }

    void poll()
    const timer = setInterval(() => void poll(), POLL_MS)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  })
</script>
