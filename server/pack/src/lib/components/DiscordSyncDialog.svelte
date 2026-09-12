<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage, fieldError } from "../errors"
  import type {
    DeletedOut,
    DiscordEventRouteIn,
    DiscordGuildSummary,
    DiscordSyncSetupIn,
    DiscordSyncSetupOut,
    RemoveDiscordImportIn,
    SaveDiscordSyncIn,
    SyncDiscordImportIn,
  } from "../page-types"

  let {
    open = $bindable(false),
    connectionId = "",
    guild = null,
    onSaved,
    onRemoved,
    onRoutesChange,
  }: {
    open: boolean
    connectionId: string
    guild: DiscordGuildSummary | null
    onSaved: (connectionId: string, summary: DiscordGuildSummary) => void
    onRemoved: (connectionId: string, guild: DiscordGuildSummary) => void
    onRoutesChange?: (guildId: string, calendarCount: number) => void
  } = $props()

  const DEFAULT_ROUTE = "default"
  const SKIP_ROUTE = "skip"

  const getSetup = useAction<DiscordSyncSetupIn, DiscordSyncSetupOut>("kalendee.discordSyncSetup", { reload: false })
  const saveDiscordSync = useAction<SaveDiscordSyncIn, DiscordGuildSummary>("kalendee.saveDiscordSync", { reload: false })
  const syncDiscordImport = useAction<SyncDiscordImportIn, DiscordGuildSummary>("kalendee.syncDiscordImport", {
    reload: false,
  })
  const removeDiscordImport = useAction<RemoveDiscordImportIn, DeletedOut>("kalendee.removeDiscordImport", {
    reload: false,
  })

  let setup = $state<DiscordSyncSetupOut | null>(null)
  let loadedFor = $state("")
  let loadError = $state("")
  let defaultCalendarId = $state("")
  let routes = $state<Record<string, string>>({})
  let enabled = $state(true)
  let dialog = $state<HTMLDialogElement | undefined>()

  const busy = $derived(
    getSetup.isPending || saveDiscordSync.isPending || syncDiscordImport.isPending || removeDiscordImport.isPending,
  )
  const defaultLabel = $derived(
    setup?.calendars.find((calendar) => calendar.id === defaultCalendarId)?.displayName ?? "auto-created",
  )
  const otherCalendars = $derived((setup?.calendars ?? []).filter((calendar) => calendar.id !== defaultCalendarId))
  const saveErrorText = $derived(
    fieldError(saveDiscordSync.error, "calendarId") ??
      fieldError(saveDiscordSync.error, "guildId") ??
      actionMessage(saveDiscordSync.error),
  )
  const removeErrorText = $derived(
    fieldError(removeDiscordImport.error, "externalCalendarId") ?? actionMessage(removeDiscordImport.error),
  )
  const syncErrorText = $derived(
    fieldError(syncDiscordImport.error, "externalCalendarId") ?? actionMessage(syncDiscordImport.error),
  )
  const loadErrorText = $derived(
    loadError || fieldError(getSetup.error, "connectionId") || actionMessage(getSetup.error),
  )

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  $effect(() => {
    if (!open) {
      loadedFor = ""
      return
    }
    if (!connectionId || !guild) return
    const key = `${connectionId}|${guild.id}`
    if (loadedFor === key) return
    loadedFor = key
    untrack(() => {
      getSetup.reset()
      saveDiscordSync.reset()
      syncDiscordImport.reset()
      removeDiscordImport.reset()
      setup = null
      loadError = ""
      defaultCalendarId = ""
      routes = {}
      enabled = true
      void load(connectionId, guild.id)
    })
  })

  async function load(connection: string, guildId: string) {
    try {
      applySetup(await getSetup.mutateAsync({ connectionId: connection, guildId }))
    } catch {
      setup = null
      loadError = "Could not load Discord sync settings."
    }
  }

  function applySetup(out: DiscordSyncSetupOut) {
    setup = out
    loadError = ""
    defaultCalendarId = out.defaultCalendarId ?? ""
    enabled = out.imported ? out.enabled : true
    routes = Object.fromEntries(
      out.events.map((event) => [event.id, initialRoute(event.calendarId, event.skipped, out.defaultCalendarId)]),
    )
    if (guild) onRoutesChange?.(guild.id, destinationCount(out, routes, defaultCalendarId))
  }

  function initialRoute(calendarId: string | null, skipped: boolean, defaultId: string | null): string {
    if (skipped) return SKIP_ROUTE
    return calendarId && calendarId !== defaultId ? calendarId : DEFAULT_ROUTE
  }

  function selectDefaultCalendar(value: string) {
    defaultCalendarId = value
    if (value === "") return
    routes = Object.fromEntries(
      Object.entries(routes).map(([eventId, route]) => [eventId, route === value ? DEFAULT_ROUTE : route]),
    )
  }

  function destinationCount(
    out: DiscordSyncSetupOut,
    selections: Record<string, string>,
    defaultId: string,
  ): number {
    const targets = new Set<string>()
    for (const event of out.events) {
      const value = selections[event.id] ?? DEFAULT_ROUTE
      if (value === SKIP_ROUTE) continue
      targets.add(value === DEFAULT_ROUTE || value === "" || value === defaultId ? `default:${defaultId}` : value)
    }
    return targets.size
  }

  async function save() {
    if (!guild || !connectionId || !setup) return
    saveDiscordSync.reset()
    removeDiscordImport.reset()
    try {
      const summary = await saveDiscordSync.mutateAsync({
        connectionId,
        guildId: guild.id,
        defaultCalendarId: defaultCalendarId === "" ? null : defaultCalendarId,
        routes: setup.events.map((event) => routeFor(event.id)),
        enabled,
      })
      onRoutesChange?.(guild.id, destinationCount(setup, routes, defaultCalendarId))
      onSaved(connectionId, summary)
      close()
    } catch {
      // The action error state renders above the footer.
    }
  }

  function routeFor(eventId: string): DiscordEventRouteIn {
    const value = routes[eventId] ?? DEFAULT_ROUTE
    if (value === SKIP_ROUTE) return { eventId, calendarId: null, skipped: true }
    if (value === DEFAULT_ROUTE || value === "") return { eventId, calendarId: null, skipped: false }
    return { eventId, calendarId: value, skipped: false }
  }

  async function remove() {
    if (!guild?.externalCalendarId || !setup?.imported) return
    if (!window.confirm(`Remove the import for ${guild.name}? The calendar and its events stay in Kalendee.`)) return
    removeDiscordImport.reset()
    saveDiscordSync.reset()
    syncDiscordImport.reset()
    try {
      await removeDiscordImport.mutateAsync({ externalCalendarId: guild.externalCalendarId })
      onRemoved(connectionId, guild)
      close()
    } catch {
      // The action error state renders above the footer.
    }
  }

  async function syncNow() {
    if (!guild?.externalCalendarId || !setup?.imported) return
    syncDiscordImport.reset()
    saveDiscordSync.reset()
    removeDiscordImport.reset()
    try {
      const summary = await syncDiscordImport.mutateAsync({ externalCalendarId: guild.externalCalendarId })
      setup = {
        ...setup,
        imported: summary.imported,
        lastSyncAt: summary.lastSyncAt,
        lastError: summary.lastError,
      }
      onSaved(connectionId, summary)
    } catch {
      // The action error state renders above the footer.
    }
  }

  function formatStart(value: string): string {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
  }

  function close() {
    open = false
    loadedFor = ""
    setup = null
    loadError = ""
    defaultCalendarId = ""
    routes = {}
    enabled = true
    getSetup.reset()
    saveDiscordSync.reset()
    syncDiscordImport.reset()
    removeDiscordImport.reset()
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-2xl">
    <h3 class="text-lg font-bold">Sync {guild?.name ?? "server"}</h3>
    <p class="py-2 text-base-content/70">
      Choose where each Discord event lands. Events use the default calendar unless you route them elsewhere.
    </p>

    {#if loadErrorText}
      <div role="alert" class="alert alert-error mb-2">{loadErrorText}</div>
    {/if}

    {#if setup}
      <div class="flex flex-col gap-4">
        <div class="flex flex-wrap items-center gap-2">
          {#if setup.imported}
            <span class="badge badge-sm {enabled ? 'badge-success' : 'badge-ghost'}">{enabled ? "Enabled" : "Paused"}</span>
          {:else}
            <span class="badge badge-ghost badge-sm">Not imported yet</span>
          {/if}
          {#if setup.imported && setup.lastSyncAt}
            <span class="settings-hint">Last sync {formatStart(setup.lastSyncAt)}</span>
          {/if}
        </div>

        {#if setup.imported && setup.lastError}
          <p class="text-error text-sm">{setup.lastError}</p>
        {/if}

        <label class="flex flex-col gap-1">
          <span class="label py-0">Default calendar</span>
          <select
            class="select w-full"
            value={defaultCalendarId}
            disabled={busy}
            aria-label="Default calendar"
            onchange={(event) => selectDefaultCalendar(event.currentTarget.value)}
          >
            {#if defaultCalendarId === ""}
              <option value="">Default (auto-created)</option>
            {/if}
            {#each setup.calendars as calendar (calendar.id)}
              <option value={calendar.id}>{calendar.displayName}</option>
            {/each}
          </select>
          <span class="settings-hint">
            {defaultCalendarId === ""
              ? "Kalendee creates a read-only calendar for this server on save."
              : "New events without their own route are imported here."}
          </span>
        </label>

        <div class="flex flex-col gap-2">
          <h4 class="text-sm font-semibold">Event routing</h4>
          {#if setup.events.length === 0}
            <p class="text-sm text-base-content/60">No scheduled events in this server yet.</p>
          {:else}
            <ul class="flex max-h-80 flex-col overflow-y-auto">
              {#each setup.events as event (event.id)}
                <li class="flex flex-wrap items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="min-w-0 truncate font-medium">{event.name}</span>
                      {#if event.recurring}
                        <span class="badge badge-ghost badge-sm">Recurring</span>
                      {/if}
                    </div>
                    <div class="settings-hint">{formatStart(event.start)}</div>
                  </div>
                  <select
                    class="select select-sm w-full sm:w-56"
                    bind:value={routes[event.id]}
                    disabled={busy}
                    aria-label={`Route for ${event.name}`}
                  >
                    <option value={DEFAULT_ROUTE}>Default — {defaultLabel}</option>
                    {#each otherCalendars as calendar (calendar.id)}
                      <option value={calendar.id}>{calendar.displayName}</option>
                    {/each}
                    <option value={SKIP_ROUTE}>Don't import</option>
                  </select>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      </div>
    {:else if !loadErrorText}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {/if}

    {#if saveErrorText}
      <p class="mt-2 text-error text-sm">{saveErrorText}</p>
    {/if}
    {#if syncErrorText}
      <p class="mt-2 text-error text-sm">{syncErrorText}</p>
    {/if}
    {#if removeErrorText}
      <p class="mt-2 text-error text-sm">{removeErrorText}</p>
    {/if}

    <div class="modal-action">
      <button
        type="button"
        class="btn btn-ghost btn-sm mr-auto text-error"
        disabled={busy || !setup?.imported}
        onclick={() => void remove()}
      >
        {removeDiscordImport.isPending ? "Removing…" : "Remove import"}
      </button>
      <button
        type="button"
        class="btn btn-sm"
        disabled={busy || !setup?.imported}
        onclick={() => void syncNow()}
      >
        {syncDiscordImport.isPending ? "Syncing…" : "Sync now"}
      </button>
      <button
        type="button"
        class="btn btn-sm"
        disabled={busy || !setup}
        onclick={() => (enabled = !enabled)}
      >
        {enabled ? "Pause sync" : "Enable sync"}
      </button>
      <button type="button" class="btn btn-ghost" onclick={close} disabled={busy}>Cancel</button>
      <button type="button" class="btn btn-primary" disabled={busy || !setup} onclick={() => void save()}>
        {saveDiscordSync.isPending ? "Saving…" : "Save"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
