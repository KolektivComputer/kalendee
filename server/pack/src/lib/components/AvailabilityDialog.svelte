<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage, fieldError } from "../errors"
  import type {
    AvailabilityWindowIn,
    CalendarAvailabilityIn,
    CalendarAvailabilityOut,
    CalendarSummary,
    UpdateCalendarAvailabilityIn,
  } from "../page-types"
  import OfficeHoursEditor from "./OfficeHoursEditor.svelte"

  let {
    open = $bindable(false),
    calendar = null,
  }: {
    open: boolean
    calendar: CalendarSummary | null
  } = $props()

  const getAvailability = useAction<CalendarAvailabilityIn, CalendarAvailabilityOut>(
    "kalendee.calendarAvailability",
    { reload: false },
  )
  const saveAvailability = useAction<UpdateCalendarAvailabilityIn, CalendarAvailabilityOut>(
    "kalendee.updateCalendarAvailability",
    { reload: false },
  )

  const slotOptions = [15, 30, 45, 60, 90, 120]

  let availability = $state<CalendarAvailabilityOut | null>(null)
  let loadedFor = $state("")
  let loadError = $state("")
  let requestsEnabled = $state(false)
  let slotMinutes = $state(60)
  let accessMode = $state("inherit")
  let windows = $state<AvailabilityWindowIn[]>([])
  let dialog = $state<HTMLDialogElement | undefined>()

  const busy = $derived(getAvailability.isPending || saveAvailability.isPending)
  const errorText = $derived(actionMessage(saveAvailability.error))
  const windowsError = $derived(fieldError(saveAvailability.error, "windows"))
  const inheritLabel = $derived(
    availability && availability.accessMode === "inherit" && availability.effectiveAccessMode !== "inherit"
      ? `Use default (${accessLabel(availability.effectiveAccessMode)})`
      : "Use default",
  )
  const effectiveLabel = $derived(availability ? accessLabel(availability.effectiveAccessMode) : "")
  const slotOptionsWithCurrent = $derived(
    slotOptions.includes(slotMinutes) ? slotOptions : [...slotOptions, slotMinutes].sort((a, b) => a - b),
  )
  const dirty = $derived.by(() => {
    if (!availability) return false
    if (requestsEnabled !== availability.requestsEnabled) return true
    if (slotMinutes !== availability.slotMinutes) return true
    if (accessMode !== availability.accessMode) return true
    return JSON.stringify(windows) !== JSON.stringify(availability.windows)
  })

  function accessLabel(mode: string): string {
    if (mode === "public") return "Anyone with the link"
    if (mode === "signed_in") return "Signed-in users only"
    return "Inherit"
  }

  function apply(out: CalendarAvailabilityOut) {
    availability = out
    requestsEnabled = out.requestsEnabled
    slotMinutes = out.slotMinutes
    accessMode = out.accessMode
    windows = out.windows.map((window) => ({ ...window }))
  }

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  $effect(() => {
    if (!open) {
      loadedFor = ""
      availability = null
      return
    }
    if (!calendar) return
    const id = calendar.id
    if (loadedFor === id) return
    loadedFor = id
    untrack(() => {
      getAvailability.reset()
      saveAvailability.reset()
      availability = null
      loadError = ""
      void load(id)
    })
  })

  async function load(calendarId: string) {
    try {
      apply(await getAvailability.mutateAsync({ calendarId }))
      loadError = ""
    } catch {
      loadError = "Could not load availability settings."
    }
  }

  async function save() {
    if (!calendar || busy) return
    try {
      apply(
        await saveAvailability.mutateAsync({
          calendarId: calendar.id,
          requestsEnabled,
          slotMinutes,
          accessMode,
          windows: windows.map((window) => ({ ...window })),
        }),
      )
    } catch {
      // The action error state renders below the form.
    }
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={() => (open = false)}>
  <div class="modal-box max-w-xl">
    <h3 class="text-lg font-bold">Office hours</h3>
    <p class="py-2 text-base-content/70">
      {calendar?.displayName ?? "Calendar"} · times use {availability?.timeZone ?? calendar?.timeZone ?? "UTC"}
    </p>

    {#if loadError}
      <div role="alert" class="alert alert-error mb-2">{loadError}</div>
    {/if}

    {#if availability}
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-3 rounded-box border border-base-300 p-3">
          <label class="label cursor-pointer justify-start gap-3 py-0">
            <input type="checkbox" class="toggle toggle-sm" bind:checked={requestsEnabled} disabled={busy} />
            <span class="label-text">Accept time requests</span>
          </label>
          <p class="text-xs text-base-content/60">
            People with read or follow access can propose a slot inside your office hours.
          </p>

          <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
            <label class="flex items-center gap-2 text-sm" for="availability-slot">
              Slot length
              <select id="availability-slot" class="select select-sm" bind:value={slotMinutes} disabled={busy}>
                {#each slotOptionsWithCurrent as minutes (minutes)}
                  <option value={minutes}>{minutes} minutes</option>
                {/each}
              </select>
            </label>
            <label class="flex items-center gap-2 text-sm" for="availability-access">
              Who can view
              <select id="availability-access" class="select select-sm" bind:value={accessMode} disabled={busy}>
                <option value="inherit">{inheritLabel}</option>
                <option value="public">Anyone with the link</option>
                <option value="signed_in">Signed-in users only</option>
              </select>
            </label>
          </div>
          <p class="text-xs text-base-content/60">
            Effective visibility: {effectiveLabel}{accessMode === "inherit" ? " (inherited)" : ""}
          </p>
        </div>

        <OfficeHoursEditor bind:windows disabled={busy} />

        {#if windowsError}
          <p class="text-error text-sm">{windowsError}</p>
        {:else if errorText}
          <p class="text-error text-sm">{errorText}</p>
        {/if}
        {#if saveAvailability.isSuccess && !dirty && !errorText}
          <p class="text-sm text-success">Availability saved.</p>
        {/if}
      </div>
    {:else if !loadError}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={() => (open = false)}>Cancel</button>
      <button type="button" class="btn btn-primary" disabled={busy || !availability} onclick={() => void save()}>
        {saveAvailability.isPending ? "Saving…" : "Save"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
