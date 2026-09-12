<script lang="ts">
  import { Head, page, router, useAction } from "@kolektiv/keel-svelte"
  import Bell from "@lucide/svelte/icons/bell"
  import Calendar from "@lucide/svelte/icons/calendar"
  import Lock from "@lucide/svelte/icons/lock"
  import Mail from "@lucide/svelte/icons/mail"
  import type {
    MarkAllNotificationsReadIn,
    MarkNotificationReadIn,
    NotificationStateOut,
    NotificationSummary,
    NotificationsPage,
  } from "../../../lib/page-types"

  const ctx = page<NotificationsPage>()
  const markRead = useAction<MarkNotificationReadIn, NotificationStateOut>("kalendee.markNotificationRead", {
    reload: false,
  })
  const markAll = useAction<MarkAllNotificationsReadIn, NotificationStateOut>("kalendee.markAllNotificationsRead")

  let items = $state<NotificationSummary[]>(ctx.data.notifications)
  $effect(() => {
    items = ctx.data.notifications
  })

  const unread = $derived(items.filter((item) => !item.read).length)

  function kindIcon(kind: string): "mail" | "calendar" | "lock" | "bell" {
    const k = kind.toLowerCase()
    if (k.includes("verify") || k.includes("email") || k.includes("mail") || k.includes("invite") || k.includes("share")) {
      return "mail"
    }
    if (k.includes("event") || k.includes("calendar") || k.includes("reminder")) return "calendar"
    if (k.includes("security") || k.includes("login") || k.includes("password")) return "lock"
    return "bell"
  }

  function relativeTime(iso: string): string {
    const then = Date.parse(iso)
    if (Number.isNaN(then)) return ""
    const seconds = Math.max(0, Math.round((Date.now() - then) / 1000))
    if (seconds < 60) return "just now"
    const minutes = Math.floor(seconds / 60)
    if (minutes < 60) return `${minutes}m ago`
    const hours = Math.floor(minutes / 60)
    if (hours < 24) return `${hours}h ago`
    const days = Math.floor(hours / 24)
    if (days < 7) return `${days}d ago`
    return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(new Date(then))
  }

  async function openNotification(item: NotificationSummary) {
    if (!item.read) {
      try {
        await markRead.mutateAsync({ id: item.id })
        items = items.map((entry) => (entry.id === item.id ? { ...entry, read: true } : entry))
      } catch {
        return
      }
    }
    if (item.href) {
      void router.visit(item.href)
    } else if (!item.read) {
      void router.reload({ preserveScroll: true, preserveState: true })
    }
  }

  function onMarkAll() {
    void markAll.mutateAsync({}).catch(() => undefined)
  }
</script>

<Head />

{#snippet notificationIcon(icon: "mail" | "calendar" | "lock" | "bell")}
  {#if icon === "mail"}
    <Mail class="h-5 w-5" />
  {:else if icon === "calendar"}
    <Calendar class="h-5 w-5" />
  {:else if icon === "lock"}
    <Lock class="h-5 w-5" />
  {:else}
    <Bell class="h-5 w-5" />
  {/if}
{/snippet}

<div class="mx-auto flex w-full max-w-2xl flex-col gap-4 p-4 sm:p-6">
  <div class="flex items-center justify-between gap-3">
    <h1 class="text-2xl font-semibold">Notifications</h1>
    {#if unread > 0}
      <button type="button" class="btn btn-ghost btn-sm" disabled={markAll.isPending} onclick={onMarkAll}>
        {#if markAll.isPending}<span class="loading loading-spinner"></span>{/if}
        {markAll.isPending ? "Marking…" : "Mark all as read"}
      </button>
    {/if}
  </div>
  {#if markAll.error}
    <p class="text-error text-sm">Could not mark notifications as read. Try again.</p>
  {/if}

  {#if items.length === 0}
    <div class="flex flex-col items-center gap-2 rounded-box border border-base-300 bg-base-200 p-10 text-center">
      <span class="text-base-content/40">{@render notificationIcon("bell")}</span>
      <p class="font-medium">No notifications yet</p>
      <p class="settings-hint">Account and calendar activity will show up here.</p>
    </div>
  {:else}
    <ul class="flex flex-col gap-2">
      {#each items as item (item.id)}
        <li>
          <button
            type="button"
            class="flex w-full items-start gap-3 rounded-box border p-3 text-left transition-colors hover:bg-base-300"
            class:border-primary={!item.read}
            class:border-base-300={item.read}
            class:bg-base-200={!item.read}
            class:bg-base-100={item.read}
            onclick={() => void openNotification(item)}
          >
            <span class={item.read ? "mt-0.5 shrink-0 text-base-content/40" : "mt-0.5 shrink-0 text-primary"}>
              {@render notificationIcon(kindIcon(item.kind))}
            </span>
            <span class="min-w-0 flex-1">
              <span class="flex items-baseline justify-between gap-2">
                <span class="truncate" class:font-semibold={!item.read} class:font-medium={item.read}>{item.title}</span>
                <span class="shrink-0 text-xs text-base-content/50" title={item.createdAt}>{relativeTime(item.createdAt)}</span>
              </span>
              {#if item.body}
                <span class="mt-0.5 line-clamp-2 block text-sm text-base-content/70">{item.body}</span>
              {/if}
            </span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>
