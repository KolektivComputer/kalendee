<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import X from "@lucide/svelte/icons/x"
  import { untrack } from "svelte"
  import HolidaysDialog from "../../../lib/components/HolidaysDialog.svelte"
  import ReminderEditor from "../../../lib/components/ReminderEditor.svelte"
  import { actionMessage, fieldError } from "../../../lib/errors"
  import type {
    CreateCustomHolidayIn,
    CustomHolidaySummary,
    DeleteCustomHolidayIn,
    DeletedOut,
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

  const titles: Record<SettingsTab, string> = {
    account: "My Account",
    appearance: "Appearance",
    holidays: "Holidays",
    notifications: "Notifications",
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
      if (document.querySelector("dialog[open]")) return
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
