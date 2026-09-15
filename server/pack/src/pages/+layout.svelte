<script lang="ts">
  import { Link, page, useAction } from "@kolektiv/keel-svelte"
  import Bell from "@lucide/svelte/icons/bell"
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert"
  import X from "@lucide/svelte/icons/x"
  import type { Snippet } from "svelte"
  import ReminderWatcher from "../lib/components/ReminderWatcher.svelte"
  import { applyAccent } from "../lib/theme"
  import type { LogoutIn, LogoutOut, Viewer } from "../lib/page-types"
  import "../styles.css"

  let { children }: { children: Snippet } = $props()
  const ctx = page()
  const viewer = $derived(ctx.shared?.viewer as Viewer | undefined)
  const logout = useAction<LogoutIn, LogoutOut>("kalendee.logout")
  const settingsMode = $derived(ctx.page === "kalendee.settings")
  const adminMode = $derived(ctx.page === "kalendee.admin")
  const appMode = $derived(Boolean(viewer) && ctx.page === "kalendee.home")
  const fill = $derived(appMode || settingsMode || adminMode)
  const unreadNotifications = $derived(Number(ctx.shared?.unreadNotifications ?? 0))
  const verificationPolicy = $derived(ctx.shared?.emailVerificationPolicy as string | undefined)

  let dismissedVerificationFor = $state("")
  const showVerificationBanner = $derived.by(() => {
    if (!viewer || verificationPolicy !== "soft") return false
    if (!viewer.email || viewer.emailVerified) return false
    if (dismissedVerificationFor === viewer.id) return false
    try {
      return sessionStorage.getItem(`kalendee.verify-banner:${viewer.id}`) !== "1"
    } catch {
      return true
    }
  })

  function dismissVerificationBanner() {
    if (!viewer) return
    dismissedVerificationFor = viewer.id
    try {
      sessionStorage.setItem(`kalendee.verify-banner:${viewer.id}`, "1")
    } catch {}
  }

  function onLogout() {
    void logout.mutateAsync({}).catch(() => undefined)
  }

  $effect(() => {
    applyAccent(viewer?.accent)
  })

  // The installed keel-pack does not compile +head.svelte, so the favicon is
  // declared client-side. The first request still asks for /favicon.ico,
  // which the server serves from the same SVG bytes.
  $effect(() => {
    if (typeof document === "undefined") return
    if (document.querySelector('link[rel="icon"]')) return
    const link = document.createElement("link")
    link.rel = "icon"
    link.type = "image/svg+xml"
    link.href = "/favicon.svg"
    document.head.append(link)
  })
</script>

<div
  class="flex min-h-dvh flex-col bg-base-100"
  class:h-dvh={fill}
  class:overflow-hidden={fill}
>
  {#if viewer}
    <ReminderWatcher />
  {/if}
  {#if !settingsMode && !adminMode}
    <div class="navbar bg-base-100 text-base-content min-h-12 shrink-0 border-b border-base-300 px-3">
      <div class="navbar-start">
        <Link href="/" class="btn btn-ghost text-lg">
          <img src="/favicon.svg" alt="" class="h-6 w-6 shrink-0" />
          Kalendee
        </Link>
        <Link href="/directory" class="btn btn-ghost hidden sm:inline-flex" prefetch="hover">Directory</Link>
      </div>
      <div class="navbar-end gap-2">
        {#if viewer}
          <Link href="/notifications" class="btn btn-ghost btn-circle relative" aria-label="Notifications" prefetch="hover">
            <Bell class="h-5 w-5" />
            {#if unreadNotifications > 0}
              <span class="badge badge-error badge-xs absolute right-0 top-0">
                {unreadNotifications > 9 ? "9+" : unreadNotifications}
              </span>
            {/if}
          </Link>
          <details class="dropdown dropdown-end">
            <summary class="btn btn-ghost gap-2">
              {#if viewer.avatarUrl}
                <img class="h-6 w-6 rounded-full object-cover" src={viewer.avatarUrl} alt="" />
              {/if}
              {viewer.displayName}
            </summary>
            <ul class="dropdown-content menu bg-base-200 rounded-box z-50 w-52 p-2 shadow-sm">
              <li class="menu-disabled"><button type="button" disabled>{viewer.username}</button></li>
              <li><Link href={`/u/${viewer.username}`}>Profile</Link></li>
              <li><Link href="/directory">Organizations</Link></li>
              <li><Link href="/settings">Settings</Link></li>
              {#if viewer.admin}
                <li><Link href="/admin">Admin</Link></li>
              {/if}
              <li>
                <button type="button" onclick={onLogout} disabled={logout.isPending}>
                  {logout.isPending ? "Signing out…" : "Sign out"}
                </button>
              </li>
            </ul>
          </details>
        {:else}
          <Link href="/login" class="btn btn-ghost" prefetch="hover">Sign in</Link>
          <Link href="/register" class="btn btn-primary" prefetch="hover">Create account</Link>
        {/if}
      </div>
    </div>
  {/if}
      </div>
    </div>
  {/if}
  {#if showVerificationBanner}
    <div role="alert" class="flex shrink-0 items-center gap-3 border-b border-warning/40 bg-warning/15 px-3 py-2 text-sm">
      <TriangleAlert class="h-4 w-4 shrink-0 text-warning" />
      <span class="min-w-0 flex-1 text-base-content/80">Verify your email address to secure your account.</span>
      <Link href="/verify-email" class="link link-warning font-medium whitespace-nowrap">Verify now</Link>
      <button type="button" class="btn btn-ghost btn-xs" aria-label="Dismiss" onclick={dismissVerificationBanner}>
        <X class="h-3.5 w-3.5" />
      </button>
    </div>
  {/if}
  <div class="min-h-0 flex-1" class:h-full={fill} class:overflow-hidden={fill}>
    {@render children()}
  </div>
  {#if !fill}
    <footer class="flex shrink-0 items-center justify-center gap-4 border-t border-base-300 px-4 py-3 text-xs text-base-content/45">
      <Link href="/privacy" class="link-hover link">Privacy</Link>
      <Link href="/terms" class="link-hover link">Terms</Link>
    </footer>
  {/if}
</div>
