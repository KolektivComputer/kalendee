<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import RefreshCw from "@lucide/svelte/icons/refresh-cw"
  import X from "@lucide/svelte/icons/x"
  import { untrack } from "svelte"
  import DiscordSyncDialog from "../../../lib/components/DiscordSyncDialog.svelte"
  import HolidaysDialog from "../../../lib/components/HolidaysDialog.svelte"
  import ReminderEditor from "../../../lib/components/ReminderEditor.svelte"
  import { actionMessage, fieldError } from "../../../lib/errors"
  import type {
    ConnectProviderIn,
    ConnectProviderOut,
    ConnectionSummary,
    CreateCustomHolidayIn,
    CustomHolidaySummary,
    DeleteCustomHolidayIn,
    DeletedOut,
    DisconnectAccountIn,
    DiscordGuildsIn,
    DiscordGuildsOut,
    DiscordGuildSummary,
    HolidayStateOut,
    LogoutIn,
    LogoutOut,
    ReminderSettingsIn,
    ReminderSettingsOut,
    ResendVerificationIn,
    ResendVerificationOut,
    SetShowHolidaysIn,
    SetUserPublicAccessIn,
    SettingsPage,
    SyncDiscordImportIn,
    UpdateHolidaySubscriptionsIn,
    UpdateReminderSettingsIn,
    UpdateSettingsIn,
    Viewer,
  } from "../../../lib/page-types"
  import {
    BROWSER_NOTIFICATIONS_KEY,
    formatOffset,
    offsetsFromRows,
    reminderRowsError,
    rowsFromOffsets,
    type ReminderRow,
  } from "../../../lib/reminders"
  import { parseSettingsTab, settingsHref, type SettingsTab } from "../../../lib/settings-ui.svelte"
  import { ACCENT_IDS, ACCENTS, accentSwatch, applyAccent, parseAccent } from "../../../lib/theme"
  import { clientTimeZone } from "../../../lib/time"

  const ctx = page<SettingsPage>()
  const saveSettings = useAction<UpdateSettingsIn, Viewer>("kalendee.updateSettings", { reload: false })
  const setShowHolidays = useAction<SetShowHolidaysIn, HolidayStateOut>("kalendee.setShowHolidays")
  const updateHolidaySubscriptions = useAction<UpdateHolidaySubscriptionsIn, HolidayStateOut>(
    "kalendee.updateHolidaySubscriptions",
  )
  const createCustomHoliday = useAction<CreateCustomHolidayIn, CustomHolidaySummary>("kalendee.createCustomHoliday")
  const deleteCustomHoliday = useAction<DeleteCustomHolidayIn, DeletedOut>("kalendee.deleteCustomHoliday")
  const logout = useAction<LogoutIn, LogoutOut>("kalendee.logout")
  const resendVerification = useAction<ResendVerificationIn, ResendVerificationOut>("kalendee.resendVerification", {
    reload: false,
  })
  const getReminderSettings = useAction<ReminderSettingsIn, ReminderSettingsOut>("kalendee.reminderSettings", {
    reload: false,
  })
  const saveReminders = useAction<UpdateReminderSettingsIn, ReminderSettingsOut>("kalendee.updateReminderSettings", {
    reload: false,
  })
  const setUserPublicAccess = useAction<SetUserPublicAccessIn, Viewer>("kalendee.setUserPublicAccess", { reload: false })
  const connectProvider = useAction<ConnectProviderIn, ConnectProviderOut>("kalendee.connectProvider", { reload: false })
  const disconnectAccount = useAction<DisconnectAccountIn, DeletedOut>("kalendee.disconnectAccount")
  const discordGuildsAction = useAction<DiscordGuildsIn, DiscordGuildsOut>("kalendee.discordGuilds", { reload: false })
  const syncDiscordImport = useAction<SyncDiscordImportIn, DiscordGuildSummary>("kalendee.syncDiscordImport", {
    reload: false,
  })

  const OAUTH_RESULTS: Record<string, { tone: string; text: string }> = {
    ok: { tone: "alert-success", text: "Discord account connected." },
    error: { tone: "alert-error", text: "Could not connect the account. Try again." },
    registration_closed: { tone: "alert-warning", text: "Registration is closed on this server." },
    email_taken: {
      tone: "alert-warning",
      text: "An account with that email already exists. Sign in and link it from settings instead.",
    },
  }

  const policy = $derived(ctx.shared?.emailVerificationPolicy as string | undefined)
  const instancePublicAccess = $derived(ctx.shared?.publicAccess as string | undefined)

  const maxAvatarBytes = 2 * 1024 * 1024
  const avatarTypes = ["image/png", "image/jpeg", "image/webp", "image/gif"]

  const localTimeZone = clientTimeZone()
  let tab = $state(parseSettingsTab(ctx.data.tab))
  let displayName = $state(ctx.data.viewer.displayName)
  let timeZone = $state(ctx.data.viewer.timeZone)
  let accent = $state(parseAccent(ctx.data.viewer.accent))
  let email = $state("")
  let avatarUrl = $state(ctx.data.viewer.avatarUrl)
  let avatarError = $state("")
  let avatarPending = $state<"upload" | "remove" | null>(null)
  let avatarInput = $state<HTMLInputElement | null>(null)
  let reminderRows = $state<ReminderRow[]>([])
  let notifyAtStart = $state(false)
  let reminderLocalError = $state("")
  let remindersLoadedFor = $state("")
  let notificationPermission = $state<NotificationPermission | "unsupported">("unsupported")
  let browserNotificationsEnabled = $state(false)
  let userAccess = $state(ctx.data.viewer.publicAccess || "inherit")
  let connectionError = $state("")
  let guildsByConnection = $state<Record<string, DiscordGuildSummary[]>>({})
  let guildsLoading = $state("")
  let guildsError = $state<Record<string, string>>({})
  let guildPending = $state("")
  let guildError = $state<{ connectionId: string; message: string } | null>(null)
  let syncDialogOpen = $state(false)
  let syncDialogConnectionId = $state("")
  let syncDialogGuild = $state<DiscordGuildSummary | null>(null)
  let routeCounts = $state<Record<string, number>>({})

  const titles: Record<SettingsTab, string> = {
    account: "My Account",
    appearance: "Appearance",
    holidays: "Holidays",
    notifications: "Notifications",
    connections: "Connected Accounts",
  }

  const publicAccessOptions = [
    { value: "inherit", label: "Inherit (default)" },
    { value: "public", label: "Anyone with the link" },
    { value: "signed_in", label: "Signed-in users only" },
  ] as const

  function accessLabel(mode: string | undefined): string {
    if (mode === "public") return "Anyone with the link"
    if (mode === "signed_in") return "Signed-in users only"
    return "Inherit"
  }

  function accessDescription(mode: string): string {
    if (mode === "public") return "Anyone with the link can see your public calendars."
    if (mode === "signed_in") return "Only signed-in people can see your public calendars."
    return instancePublicAccess
      ? `Uses the server default (${accessLabel(instancePublicAccess)}).`
      : "Uses the server default."
  }

  const initials = $derived(
    ctx.data.viewer.displayName
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("") || ctx.data.viewer.username.slice(0, 2).toUpperCase(),
  )

  const reminderErrorText = $derived(actionMessage(saveReminders.error))
  const reminderOffsetsError = $derived(fieldError(saveReminders.error, "offsets"))
  const reminderRowsValueError = $derived(reminderRowsError(reminderRows))
  const reminderSummary = $derived(offsetsFromRows(reminderRows).map(formatOffset).join(", "))
  const reminderLoading = $derived(getReminderSettings.isPending)
  const oauthResult = $derived(parseOauthResult(ctx.path))
  const importedGuilds = $derived.by(() => {
    const out: Record<string, DiscordGuildSummary[]> = {}
    for (const [connectionId, guilds] of Object.entries(guildsByConnection)) {
      out[connectionId] = guilds.filter((guild) => guild.imported && guild.externalCalendarId)
    }
    return out
  })

  $effect(() => {
    displayName = ctx.data.viewer.displayName
    timeZone = ctx.data.viewer.timeZone
    accent = parseAccent(ctx.data.viewer.accent)
    avatarUrl = ctx.data.viewer.avatarUrl
    userAccess = ctx.data.viewer.publicAccess || "inherit"
  })

  $effect(() => {
    tab = parseSettingsTab(ctx.data.tab)
  })

  $effect(() => {
    if (typeof Notification === "undefined") {
      notificationPermission = "unsupported"
      return
    }
    notificationPermission = Notification.permission
    try {
      browserNotificationsEnabled = localStorage.getItem(BROWSER_NOTIFICATIONS_KEY) === "1"
    } catch {
      browserNotificationsEnabled = false
    }
    if (Notification.permission === "granted") {
      browserNotificationsEnabled = true
      try {
        localStorage.setItem(BROWSER_NOTIFICATIONS_KEY, "1")
      } catch {}
    }
  })

  $effect(() => {
    if (tab !== "notifications") {
      remindersLoadedFor = ""
      return
    }
    if (remindersLoadedFor === "notifications") return
    remindersLoadedFor = "notifications"
    untrack(() => {
      void loadReminderSettings()
    })
  })

  $effect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key !== "Escape") return
      if (hasOpenDialog()) return
      event.preventDefault()
      closeSettings()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  })

  function selectTab(next: SettingsTab) {
    if (tab === next) return
    tab = next
    void router.replace(settingsHref(next), { preserveState: true, preserveScroll: true })
  }

  function closeSettings() {
    void router.visit("/")
  }

  function hasOpenDialog(): boolean {
    return typeof document !== "undefined" && document.querySelector("dialog[open]") !== null
  }

  function refreshViewer() {
    return router.replace(settingsHref(tab), { preserveState: true, preserveScroll: true })
  }

  function applyReminderSettings(settings: ReminderSettingsOut) {
    notifyAtStart = settings.notifyAtStart
    reminderRows = rowsFromOffsets(settings.defaultOffsetsSeconds)
  }

  async function loadReminderSettings() {
    reminderLocalError = ""
    saveReminders.reset()
    try {
      applyReminderSettings(await getReminderSettings.mutateAsync({}))
    } catch {
      reminderLocalError = "Could not load reminder settings."
    }
  }

  async function enableBrowserNotifications() {
    if (typeof Notification === "undefined") return
    try {
      notificationPermission = await Notification.requestPermission()
    } catch {
      notificationPermission = Notification.permission
      return
    }
    if (notificationPermission === "granted") {
      browserNotificationsEnabled = true
      try {
        localStorage.setItem(BROWSER_NOTIFICATIONS_KEY, "1")
      } catch {}
    }
  }

  function sendTestNotification() {
    if (typeof Notification === "undefined" || Notification.permission !== "granted") return
    try {
      new Notification("Kalendee test notification", {
        body: "Browser notifications are working.",
      })
    } catch {}
  }

  async function saveReminderSettings() {
    reminderLocalError = ""
    const validation = reminderRowsError(reminderRows)
    if (validation) {
      reminderLocalError = validation
      return
    }
    try {
      const saved = await saveReminders.mutateAsync({
        defaultOffsetsSeconds: offsetsFromRows(reminderRows),
        notifyAtStart,
      })
      applyReminderSettings(saved)
      await router.replace(settingsHref("notifications"), { preserveState: true, preserveScroll: true })
    } catch {
      // Failure details render from the action error state.
    }
  }

  function saveAccount() {
    return saveSettings
      .mutateAsync({ displayName, timeZone, accent, email: email.trim() || null })
      .then(() => {
        email = ""
        return refreshViewer()
      })
      .catch(() => undefined)
  }

  function saveEmail() {
    const next = email.trim()
    if (!next) return Promise.resolve()
    return saveSettings
      .mutateAsync({ displayName, timeZone, accent, email: next })
      .then(() => {
        email = ""
        return refreshViewer()
      })
      .catch(() => undefined)
  }

  function removeEmail() {
    return saveSettings
      .mutateAsync({ displayName, timeZone, accent, clearEmail: true })
      .then(() => {
        email = ""
        return refreshViewer()
      })
      .catch(() => undefined)
  }

  function resendEmail() {
    void resendVerification.mutateAsync({ username: ctx.data.viewer.username }).catch(() => undefined)
  }

  function saveAccent(next: typeof accent) {
    const previous = accent
    accent = next
    applyAccent(next)
    return saveSettings
      .mutateAsync({
        displayName: ctx.data.viewer.displayName,
        timeZone: ctx.data.viewer.timeZone,
        accent: next,
      })
      .then(() => refreshViewer())
      .catch(() => {
        accent = previous
        applyAccent(previous)
      })
  }

  function selectUserAccess(next: string) {
    if (userAccess === next || setUserPublicAccess.isPending) return
    const previous = userAccess
    userAccess = next
    void setUserPublicAccess
      .mutateAsync({ mode: next })
      .then(() => refreshViewer())
      .catch(() => {
        userAccess = previous
      })
  }

  function parseOauthResult(path: string): { tone: string; text: string } | null {
    const queryIndex = path.indexOf("?")
    if (queryIndex < 0) return null
    const value = new URLSearchParams(path.slice(queryIndex + 1)).get("oauth")
    return value ? (OAUTH_RESULTS[value] ?? null) : null
  }

  function formatTimestamp(value: string | null | undefined): string {
    if (!value) return ""
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
  }

  function connectionStatusBadge(status: string): string {
    if (status === "active") return "badge-success"
    if (status === "needs_reauth") return "badge-warning"
    return "badge-ghost"
  }

  function connectionStatusLabel(status: string): string {
    if (status === "needs_reauth") return "Needs reconnect"
    return status.charAt(0).toUpperCase() + status.slice(1)
  }

  function guildInitials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  async function connectProviderAccount(providerId: string) {
    connectionError = ""
    connectProvider.reset()
    try {
      const out = await connectProvider.mutateAsync({ providerId, returnTo: "/settings?tab=connections" })
      window.location.href = out.url
    } catch {
      connectionError = actionMessage(connectProvider.error)
    }
  }

  async function disconnectAccountById(connection: ConnectionSummary) {
    const label = connection.displayName ?? connection.accountEmail ?? connection.providerName
    if (!window.confirm(`Disconnect ${label}? Imported calendars stay in Kalendee.`)) return
    connectionError = ""
    disconnectAccount.reset()
    try {
      await disconnectAccount.mutateAsync({ connectionId: connection.id })
    } catch {
      connectionError = actionMessage(disconnectAccount.error)
    }
  }

  async function loadGuilds(connectionId: string) {
    guildsLoading = connectionId
    guildsError = { ...guildsError, [connectionId]: "" }
    guildError = null
    try {
      const out = await discordGuildsAction.mutateAsync({ connectionId })
      guildsByConnection = { ...guildsByConnection, [connectionId]: out.guilds }
    } catch {
      guildsError = { ...guildsError, [connectionId]: actionMessage(discordGuildsAction.error) }
    } finally {
      guildsLoading = ""
    }
  }

  function replaceGuild(connectionId: string, summary: DiscordGuildSummary) {
    const current = guildsByConnection[connectionId] ?? []
    const next = current.some((guild) => guild.id === summary.id)
      ? current.map((guild) => (guild.id === summary.id ? summary : guild))
      : [...current, summary]
    guildsByConnection = { ...guildsByConnection, [connectionId]: next }
  }

  function openSyncDialog(connectionId: string, guild: DiscordGuildSummary) {
    syncDialogConnectionId = connectionId
    syncDialogGuild = guild
    syncDialogOpen = true
  }

  function onSyncDialogSaved(connectionId: string, summary: DiscordGuildSummary) {
    replaceGuild(connectionId, summary)
  }

  function onSyncDialogRemoved(connectionId: string, guild: DiscordGuildSummary) {
    replaceGuild(connectionId, {
      ...guild,
      imported: false,
      enabled: false,
      externalCalendarId: null,
      calendarId: null,
      lastSyncAt: null,
      lastError: null,
    })
    const next = { ...routeCounts }
    delete next[guild.id]
    routeCounts = next
  }

  function onSyncDialogRoutes(guildId: string, calendarCount: number) {
    routeCounts = { ...routeCounts, [guildId]: calendarCount }
  }

  async function syncGuild(connectionId: string, guild: DiscordGuildSummary) {
    if (!guild.externalCalendarId) return
    guildPending = `sync:${guild.id}`
    guildError = null
    try {
      const summary = await syncDiscordImport.mutateAsync({ externalCalendarId: guild.externalCalendarId })
      replaceGuild(connectionId, summary)
    } catch {
      guildError = {
        connectionId,
        message:
          fieldError(syncDiscordImport.error, "externalCalendarId") ?? actionMessage(syncDiscordImport.error),
      }
    } finally {
      guildPending = ""
    }
  }

  async function syncConnectionImports(connectionId: string) {
    for (const guild of importedGuilds[connectionId] ?? []) {
      await syncGuild(connectionId, guild)
    }
  }

  function pickAvatar() {
    avatarInput?.click()
  }

  async function avatarErrorMessage(response: Response): Promise<string> {
    if (response.status === 401) return "Sign in again to change your photo."
    if (response.status === 413) return "Images must be at most 2 MiB."
    if (response.status === 415) return "Choose a PNG, JPEG, WebP, or GIF image."
    if (response.status === 500) return "Could not update the photo. Try again."
    const body = (await response.json().catch(() => null)) as { message?: string } | null
    return body?.message ?? "Could not update the photo. Try again."
  }

  async function uploadAvatar(file: File) {
    avatarError = ""
    if (!avatarTypes.includes(file.type)) {
      avatarError = "Choose a PNG, JPEG, WebP, or GIF image."
      return
    }
    if (file.size > maxAvatarBytes) {
      avatarError = "Images must be at most 2 MiB."
      return
    }
    const formData = new FormData()
    formData.append("file", file)
    avatarPending = "upload"
    try {
      const response = await fetch("/api/v1/auth/me/avatar", {
        method: "POST",
        body: formData,
        credentials: "same-origin",
      })
      if (!response.ok) {
        avatarError = await avatarErrorMessage(response)
        return
      }
      const result = (await response.json()) as { avatarUrl: string }
      avatarUrl = result.avatarUrl
      await refreshViewer()
    } catch {
      avatarError = "Could not update the photo. Try again."
    } finally {
      avatarPending = null
    }
  }

  function onAvatarSelected(event: Event) {
    const input = event.currentTarget as HTMLInputElement
    const file = input.files?.[0]
    input.value = ""
    if (file) void uploadAvatar(file)
  }

  async function removeAvatar() {
    avatarError = ""
    avatarPending = "remove"
    try {
      const response = await fetch("/api/v1/auth/me/avatar", {
        method: "DELETE",
        credentials: "same-origin",
      })
      if (!response.ok) {
        avatarError = await avatarErrorMessage(response)
        return
      }
      avatarUrl = null
      await refreshViewer()
    } catch {
      avatarError = "Could not remove the photo. Try again."
    } finally {
      avatarPending = null
    }
  }

  function onLogout() {
    void logout
      .mutateAsync({})
      .then(() => router.visit("/login"))
      .catch(() => undefined)
  }
</script>

<Head />

<div class="settings-page settings-root">
  <aside class="settings-sidebar-region">
    <div class="settings-sidebar-scroller">
      <nav class="settings-sidebar" aria-label="Settings">
        <div class="settings-mobile-bar">
          <p class="px-2.5 text-sm font-semibold">Settings</p>
          <button type="button" class="settings-close" aria-label="Close settings" onclick={closeSettings}>
            <X class="h-[18px] w-[18px]" />
          </button>
        </div>

        <p class="settings-nav-header">User Settings</p>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "account"}
          aria-current={tab === "account" ? "page" : undefined}
          onclick={() => selectTab("account")}
        >
          My Account
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "connections"}
          aria-current={tab === "connections" ? "page" : undefined}
          onclick={() => selectTab("connections")}
        >
          Connected Accounts
        </button>

        <div class="settings-separator"></div>

        <p class="settings-nav-header">App Settings</p>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "appearance"}
          aria-current={tab === "appearance" ? "page" : undefined}
          onclick={() => selectTab("appearance")}
        >
          Appearance
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "holidays"}
          aria-current={tab === "holidays" ? "page" : undefined}
          onclick={() => selectTab("holidays")}
        >
          Holidays
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "notifications"}
          aria-current={tab === "notifications" ? "page" : undefined}
          onclick={() => selectTab("notifications")}
        >
          Notifications
        </button>
        {#if ctx.data.viewer.admin}
          <Link href="/admin" class="settings-nav">Admin</Link>
        {/if}

        <div class="settings-separator"></div>

        <button type="button" class="settings-nav settings-nav-danger" disabled={logout.isPending} onclick={onLogout}>
          {logout.isPending ? "Signing out…" : "Log Out"}
        </button>
      </nav>
    </div>
  </aside>

  <div class="settings-content-region">
    <div class="settings-content-scroller">
      <div class="settings-content">
        <h1 class="settings-title">{titles[tab]}</h1>

        {#if tab === "account"}
          <form
            class="settings-stack"
            onsubmit={(event) => {
              event.preventDefault()
              void saveAccount().catch(() => undefined)
            }}
          >
            <div class="settings-card">
              <div class="settings-profile-body">
                <div class="settings-profile-header">
                  {#if avatarUrl}
                    <img class="settings-avatar object-cover" src={avatarUrl} alt="" />
                  {:else}
                    <div class="settings-avatar">{initials}</div>
                  {/if}
                  <div class="min-w-0">
                    <div class="text-lg font-semibold">{displayName || ctx.data.viewer.displayName}</div>
                    <div class="settings-hint">{ctx.data.viewer.username}</div>
                  </div>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    class="btn btn-sm"
                    disabled={avatarPending !== null}
                    onclick={pickAvatar}
                  >
                    {avatarPending === "upload" ? "Uploading…" : "Upload photo"}
                  </button>
                  {#if avatarUrl}
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-error"
                      disabled={avatarPending !== null}
                      onclick={() => void removeAvatar()}
                    >
                      {avatarPending === "remove" ? "Removing…" : "Remove"}
                    </button>
                  {/if}
                  <input
                    bind:this={avatarInput}
                    type="file"
                    class="hidden"
                    accept="image/png,image/jpeg,image/webp,image/gif"
                    onchange={onAvatarSelected}
                  />
                </div>
                {#if avatarError}
                  <p class="text-error text-sm">{avatarError}</p>
                {/if}
              </div>
            </div>

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <label class="settings-field">
                  <span class="settings-label">Display Name</span>
                  <input
                    id="settings-name"
                    class="input"
                    bind:value={displayName}
                    required
                    maxlength="80"
                    autocomplete="nickname"
                  />
                  {#if fieldError(saveSettings.error, "displayName")}
                    <span class="text-error text-sm">{fieldError(saveSettings.error, "displayName")}</span>
                  {/if}
                </label>
                <label class="settings-field">
                  <span class="settings-label">Time Zone</span>
                  <input
                    id="settings-tz"
                    class="input"
                    bind:value={timeZone}
                    required
                    placeholder={localTimeZone}
                    autocomplete="off"
                  />
                  {#if fieldError(saveSettings.error, "timeZone")}
                    <span class="text-error text-sm">{fieldError(saveSettings.error, "timeZone")}</span>
                  {/if}
                  {#if timeZone !== localTimeZone}
                    <button type="button" class="btn btn-ghost btn-sm self-start" onclick={() => (timeZone = localTimeZone)}>
                      Use {localTimeZone}
                    </button>
                  {/if}
                </label>
                <p class="settings-hint">
                  Username <code>{ctx.data.viewer.username}</code> stays the same. The week view follows your browser time
                  zone.
                </p>
              </div>
            </div>

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <div class="settings-field">
                  <span class="settings-label">Email</span>
                  {#if ctx.data.viewer.email}
                    <div class="flex flex-wrap items-center gap-2">
                      <span>{ctx.data.viewer.email}</span>
                      {#if ctx.data.viewer.emailVerified}
                        <span class="badge badge-success badge-sm">Verified</span>
                      {:else}
                        <span class="badge badge-warning badge-sm">Unverified</span>
                      {/if}
                    </div>
                  {:else}
                    <p class="settings-hint">No email</p>
                  {/if}
                </div>

                {#if ctx.data.viewer.email && !ctx.data.viewer.emailVerified}
                  <div class="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      class="btn btn-sm"
                      disabled={resendVerification.isPending}
                      onclick={resendEmail}
                    >
                      {#if resendVerification.isPending}<span class="loading loading-spinner"></span>{/if}
                      {resendVerification.isPending ? "Sending…" : "Resend verification email"}
                    </button>
                    {#if resendVerification.isSuccess}
                      <span class="text-sm text-success">Verification email sent.</span>
                    {/if}
                    {#if resendVerification.error}
                      <span class="text-error text-sm">Could not send the email. Try again in a minute.</span>
                    {/if}
                  </div>
                {/if}

                <label class="settings-field">
                  <span class="settings-label">Change email</span>
                  <input
                    id="settings-email"
                    class="input"
                    type="email"
                    bind:value={email}
                    autocomplete="email"
                    placeholder={ctx.data.viewer.email ?? "you@example.com"}
                  />
                  {#if fieldError(saveSettings.error, "email")}
                    <span class="text-error text-sm">{fieldError(saveSettings.error, "email")}</span>
                  {/if}
                  <span class="settings-hint">Changing your email requires verifying the new address.</span>
                </label>

                <div class="flex flex-wrap gap-2">
                  <button
                    type="button"
                    class="btn btn-sm"
                    disabled={saveSettings.isPending || email.trim().length === 0}
                    onclick={() => void saveEmail()}
                  >
                    {saveSettings.isPending ? "Saving…" : "Save email"}
                  </button>
                  {#if ctx.data.viewer.email && policy !== "required"}
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-error"
                      disabled={saveSettings.isPending}
                      onclick={() => void removeEmail()}
                    >
                      Remove email
                    </button>
                  {/if}
                </div>
                {#if policy === "required" && ctx.data.viewer.email}
                  <p class="settings-hint">This server requires an email address, so it can't be removed.</p>
                {/if}
              </div>
            </div>

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <div class="settings-field">
                  <span class="settings-label">Privacy</span>
                  <p class="settings-hint">Who can see your public calendars.</p>
                </div>
                <ul class="grid grid-cols-1 gap-2">
                  {#each publicAccessOptions as option (option.value)}
                    <li>
                      <button
                        type="button"
                        class="settings-choice"
                        class:settings-swatch-active={userAccess === option.value}
                        aria-pressed={userAccess === option.value}
                        disabled={setUserPublicAccess.isPending}
                        onclick={() => selectUserAccess(option.value)}
                      >
                        <span class="font-medium">{option.label}</span>
                        <span class="settings-hint">{accessDescription(option.value)}</span>
                      </button>
                    </li>
                  {/each}
                </ul>
                {#if setUserPublicAccess.error}
                  <p class="text-error text-sm">
                    {fieldError(setUserPublicAccess.error, "mode") ??
                      actionMessage(setUserPublicAccess.error)}
                  </p>
                {/if}
              </div>
            </div>

            <div class="flex justify-end">
              <button type="submit" class="btn btn-primary" disabled={saveSettings.isPending}>
                {saveSettings.isPending ? "Saving…" : "Save Changes"}
              </button>
            </div>
          </form>
        {:else if tab === "appearance"}
          <div class="settings-stack">
            <p class="settings-hint">
              Backgrounds stay daisyUI greys. Pick a primary color for buttons, today, and other accents.
            </p>
            <ul class="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {#each ACCENT_IDS as id (id)}
                <li>
                  <button
                    type="button"
                    class="settings-swatch"
                    class:settings-swatch-active={accent === id}
                    onclick={() => void saveAccent(id).catch(() => undefined)}
                  >
                    <span class="h-8 w-8 shrink-0 rounded-full" style={`background:${accentSwatch(id)}`}></span>
                    <span class="font-medium">{ACCENTS[id].label}</span>
                  </button>
                </li>
              {/each}
            </ul>
            {#if fieldError(saveSettings.error, "accent")}
              <p class="text-error text-sm">{fieldError(saveSettings.error, "accent")}</p>
            {/if}
          </div>
        {:else if tab === "holidays"}
          <HolidaysDialog
            catalog={ctx.data.holidayCatalog}
            subscribedIds={ctx.data.subscribedHolidayIds}
            customHolidays={ctx.data.customHolidays}
            showHolidays={ctx.data.showHolidays}
            subscribePending={updateHolidaySubscriptions.isPending}
            createPending={createCustomHoliday.isPending}
            showPending={setShowHolidays.isPending}
            subscribeError={updateHolidaySubscriptions.error}
            createError={createCustomHoliday.error}
            onShowHolidays={(show) => setShowHolidays.mutateAsync({ showHolidays: show })}
            onSaveSubscriptions={(subscribedIds) => updateHolidaySubscriptions.mutateAsync({ subscribedIds })}
            onCreateCustom={(input) => createCustomHoliday.mutateAsync(input)}
            onDeleteCustom={(id) => deleteCustomHoliday.mutateAsync({ id })}
          />
        {:else if tab === "connections"}
          <div class="settings-stack">
            {#if oauthResult}
              <div
                class="alert {oauthResult.tone}"
                role={oauthResult.tone === "alert-success" ? "status" : "alert"}
              >
                <span>{oauthResult.text}</span>
              </div>
            {/if}

            {#if connectionError}
              <p class="text-error text-sm">{connectionError}</p>
            {/if}

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <div class="settings-field">
                  <span class="settings-label">Connect an account</span>
                  <p class="settings-hint">
                    Link a Discord account to import its server events into a read-only calendar. Nothing is written
                    back to Discord.
                  </p>
                </div>
                {#if ctx.data.providers.filter((provider) => provider.enabled).length === 0}
                  <p class="settings-hint">No account providers are configured on this server.</p>
                {:else}
                  <div class="flex flex-wrap gap-2">
                    {#each ctx.data.providers.filter((provider) => provider.enabled) as provider (provider.id)}
                      <button
                        type="button"
                        class="btn btn-primary btn-sm"
                        disabled={connectProvider.isPending}
                        onclick={() => void connectProviderAccount(provider.id)}
                      >
                        {connectProvider.isPending ? "Opening…" : `Connect ${provider.displayName}`}
                      </button>
                    {/each}
                  </div>
                {/if}
              </div>
            </div>

            {#if ctx.data.connections.length === 0}
              <div class="settings-card">
                <div class="settings-card-body">
                  <p class="settings-hint">No connected accounts yet.</p>
                </div>
              </div>
            {/if}

            {#each ctx.data.connections as connection (connection.id)}
              <div class="settings-card">
                <div class="settings-card-body settings-stack">
                  <div class="flex flex-wrap items-start justify-between gap-3">
                    <div class="min-w-0">
                      <div class="flex flex-wrap items-center gap-2">
                        <span class="badge badge-outline badge-sm">{connection.providerName}</span>
                        <span class="badge badge-sm {connectionStatusBadge(connection.status)}">
                          {connectionStatusLabel(connection.status)}
                        </span>
                      </div>
                      <p class="mt-1 truncate font-medium">
                        {connection.displayName ?? connection.accountEmail ?? connection.providerName}
                      </p>
                      {#if connection.displayName && connection.accountEmail}
                        <p class="settings-hint truncate">{connection.accountEmail}</p>
                      {/if}
                    </div>
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-error"
                      disabled={disconnectAccount.isPending}
                      onclick={() => void disconnectAccountById(connection)}
                    >
                      Disconnect
                    </button>
                  </div>

                  <p class="settings-hint">
                    {connection.lastSyncAt
                      ? `Last sync ${formatTimestamp(connection.lastSyncAt)}`
                      : "Not synced yet"}
                  </p>
                  {#if connection.lastError}
                    <p class="text-error text-sm">{connection.lastError}</p>
                  {/if}
                  {#if connection.status === "needs_reauth"}
                    <p class="settings-hint">Reconnect this account to keep its imports in sync.</p>
                  {/if}

                  {#if connection.provider === "discord"}
                    <div class="flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        class="btn btn-sm"
                        disabled={guildsLoading === connection.id}
                        onclick={() => void loadGuilds(connection.id)}
                      >
                        {guildsLoading === connection.id ? "Loading…" : "Load servers"}
                      </button>
                      {#if (importedGuilds[connection.id] ?? []).length > 0}
                        <button
                          type="button"
                          class="btn btn-sm"
                          disabled={guildPending !== "" || guildsLoading === connection.id}
                          onclick={() => void syncConnectionImports(connection.id)}
                        >
                          <RefreshCw
                            class={`h-4 w-4${guildPending.startsWith("sync:") ? " animate-spin" : ""}`}
                            aria-hidden="true"
                          />
                          Sync now
                        </button>
                      {/if}
                    </div>

                    {#if guildsError[connection.id]}
                      <p class="text-error text-sm">{guildsError[connection.id]}</p>
                    {/if}

                    {#if guildsByConnection[connection.id]}
                      <ul class="grid gap-3 xl:grid-cols-2">
                        {#each guildsByConnection[connection.id] ?? [] as guild (guild.id)}
                          <li class="rounded-box border border-base-300 bg-base-100 p-3">
                            <div class="flex items-start gap-3">
                              {#if guild.iconUrl}
                                <img
                                  src={guild.iconUrl}
                                  alt=""
                                  class="h-10 w-10 shrink-0 rounded-full object-cover"
                                />
                              {:else}
                                <span
                                  class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-base-content/20 text-sm font-semibold"
                                  aria-hidden="true"
                                >
                                  {guildInitials(guild.name)}
                                </span>
                              {/if}
                              <div class="min-w-0 flex-1">
                                <div class="flex flex-wrap items-center gap-2">
                                  <span class="min-w-0 truncate font-medium">{guild.name}</span>
                                  {#if guild.botPresent}
                                    <span class="badge badge-success badge-outline badge-sm">installed</span>
                                  {:else}
                                    <span class="badge badge-ghost badge-sm">not installed</span>
                                  {/if}
                                  {#if guild.imported}
                                    <span class="badge badge-sm {guild.enabled ? 'badge-success' : 'badge-ghost'}">
                                      {guild.enabled ? "enabled" : "paused"}
                                    </span>
                                  {/if}
                                </div>
                                <div class="mt-1 flex flex-wrap items-center gap-2">
                                  {#if guild.botPresent}
                                    <button
                                      type="button"
                                      class="btn btn-sm"
                                      onclick={() => openSyncDialog(connection.id, guild)}
                                    >
                                      Manage sync
                                    </button>
                                  {:else if guild.manageable && guild.inviteUrl}
                                    <a
                                      class="link link-primary text-sm"
                                      href={guild.inviteUrl}
                                      target="_blank"
                                      rel="noreferrer"
                                    >
                                      Install bot
                                    </a>
                                  {:else}
                                    <span class="settings-hint">Ask a server admin to install the Kalendee bot.</span>
                                  {/if}
                                </div>
                                {#if guild.imported && guild.lastSyncAt}
                                  <p class="settings-hint mt-1">Last sync {formatTimestamp(guild.lastSyncAt)}</p>
                                {/if}
                                {#if guild.imported && guild.lastError}
                                  <p class="mt-1 text-error text-sm">{guild.lastError}</p>
                                {/if}
                                {#if guild.botPresent && guild.imported && (routeCounts[guild.id] ?? 0) > 0}
                                  <p class="settings-hint mt-1">
                                    Synced to {routeCounts[guild.id]}
                                    {routeCounts[guild.id] === 1 ? "calendar" : "calendars"}
                                  </p>
                                {/if}
                              </div>
                            </div>
                          </li>
                        {/each}
                      </ul>
                    {/if}

                    {#if guildError && guildError.connectionId === connection.id}
                      <p class="text-error text-sm">{guildError.message}</p>
                    {/if}
                  {/if}
                </div>
              </div>
            {/each}
          </div>
        {:else}
          <div class="settings-stack">
            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <div class="settings-field">
                  <span class="settings-label">Browser notifications</span>
                  <p class="settings-hint">
                    Reminders appear as browser notifications while Kalendee is open in this browser. Native clients
                    will come later.
                  </p>
                </div>
                <div class="flex flex-wrap items-center gap-3">
                  <span
                    class="badge badge-sm"
                    class:badge-success={notificationPermission === "granted"}
                    class:badge-ghost={notificationPermission !== "granted"}
                  >
                    Notification.permission: {notificationPermission}
                  </span>
                  {#if notificationPermission === "granted"}
                    <button type="button" class="btn btn-sm" onclick={sendTestNotification}>
                      Send test notification
                    </button>
                  {:else if notificationPermission !== "unsupported"}
                    <button
                      type="button"
                      class="btn btn-primary btn-sm"
                      onclick={() => void enableBrowserNotifications()}
                    >
                      Enable browser notifications
                    </button>
                  {/if}
                </div>
                {#if notificationPermission === "granted"}
                  <p class="settings-hint">
                    {browserNotificationsEnabled
                      ? "Reminders are enabled for this browser."
                      : "Permission is granted; reminders will run while the app is open."}
                  </p>
                {:else if notificationPermission === "denied"}
                  <p class="settings-hint">
                    Notifications are blocked for this site. Allow them in your browser's site settings, then reload.
                  </p>
                {:else if notificationPermission === "unsupported"}
                  <p class="settings-hint">This browser does not support notifications.</p>
                {:else}
                  <p class="settings-hint">No notification permission has been requested yet.</p>
                {/if}
              </div>
            </div>

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <label class="label cursor-pointer justify-start gap-3 py-0">
                  <input
                    type="checkbox"
                    class="toggle toggle-sm"
                    bind:checked={notifyAtStart}
                    disabled={reminderLoading || saveReminders.isPending}
                  />
                  <span class="label-text">Notify when an event starts</span>
                </label>
                <p class="settings-hint">
                  Every event gets a notification at its start time, in addition to the reminders below.
                </p>
              </div>
            </div>

            <div class="settings-card">
              <div class="settings-card-body settings-stack">
                <div class="settings-field">
                  <span class="settings-label">Default reminders</span>
                  <p class="settings-hint">New events use these unless you set reminders for the event.</p>
                  <ReminderEditor bind:rows={reminderRows} disabled={reminderLoading || saveReminders.isPending} />
                  {#if reminderSummary}
                    <p class="settings-hint">{reminderSummary}</p>
                  {/if}
                </div>
                {#if reminderRowsValueError}
                  <p class="text-error text-sm">{reminderRowsValueError}</p>
                {/if}
                {#if reminderOffsetsError}
                  <p class="text-error text-sm">{reminderOffsetsError}</p>
                {:else if reminderErrorText}
                  <p class="text-error text-sm">{reminderErrorText}</p>
                {/if}
                {#if reminderLocalError}
                  <p class="text-error text-sm">{reminderLocalError}</p>
                {/if}
                {#if saveReminders.isSuccess && !saveReminders.error && !reminderLocalError}
                  <p class="text-sm text-success">Reminder settings saved.</p>
                {/if}
                <div class="flex justify-end">
                  <button
                    type="button"
                    class="btn btn-primary"
                    disabled={reminderLoading || saveReminders.isPending}
                    onclick={() => void saveReminderSettings()}
                  >
                    {saveReminders.isPending ? "Saving…" : "Save reminders"}
                  </button>
                </div>
              </div>
            </div>
          </div>
        {/if}
      </div>
    </div>

    <div class="settings-tools">
      <button type="button" class="settings-close" aria-label="Close settings" onclick={closeSettings}>
        <X class="h-[18px] w-[18px]" />
      </button>
      <span class="settings-esc">ESC</span>
    </div>
  </div>
</div>

<DiscordSyncDialog
  bind:open={syncDialogOpen}
  connectionId={syncDialogConnectionId}
  guild={syncDialogGuild}
  onSaved={onSyncDialogSaved}
  onRemoved={onSyncDialogRemoved}
  onRoutesChange={onSyncDialogRoutes}
/>
