<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage } from "../errors"
  import type {
    CalendarRsvpSettingsIn,
    CalendarRsvpSettingsOut,
    CalendarSummary,
    UpdateCalendarRsvpSettingsIn,
  } from "../page-types"

  let {
    open = $bindable(false),
    calendar = null,
  }: {
    open: boolean
    calendar: CalendarSummary | null
  } = $props()

  const getRsvpSettings = useAction<CalendarRsvpSettingsIn, CalendarRsvpSettingsOut>(
    "kalendee.calendarRsvpSettings",
    { reload: false },
  )
  const saveRsvpSettings = useAction<UpdateCalendarRsvpSettingsIn, CalendarRsvpSettingsOut>(
    "kalendee.updateCalendarRsvpSettings",
  )

  let settings = $state<CalendarRsvpSettingsOut | null>(null)
  let loadedFor = $state("")
  let loadError = $state("")
  let rsvpEnabled = $state(false)
  let anonymousRsvpEnabled = $state(false)
  let dialog = $state<HTMLDialogElement | undefined>()

  const busy = $derived(getRsvpSettings.isPending || saveRsvpSettings.isPending)
  const errorText = $derived(actionMessage(saveRsvpSettings.error))
  const dirty = $derived(
    settings != null &&
      (rsvpEnabled !== settings.rsvpEnabled || anonymousRsvpEnabled !== settings.anonymousRsvpEnabled),
  )

  function apply(out: CalendarRsvpSettingsOut) {
    settings = out
    rsvpEnabled = out.rsvpEnabled
    anonymousRsvpEnabled = out.anonymousRsvpEnabled
  }

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  $effect(() => {
    if (!open) {
      loadedFor = ""
      settings = null
      return
    }
    if (!calendar) return
    const id = calendar.id
    if (loadedFor === id) return
    loadedFor = id
    untrack(() => {
      getRsvpSettings.reset()
      saveRsvpSettings.reset()
      settings = null
      loadError = ""
      void load(id)
    })
  })

  async function load(calendarId: string) {
    try {
      apply(await getRsvpSettings.mutateAsync({ calendarId }))
      loadError = ""
    } catch {
      loadError = "Could not load RSVP settings."
    }
  }

  async function save() {
    if (!calendar || busy) return
    try {
      apply(
        await saveRsvpSettings.mutateAsync({
          calendarId: calendar.id,
          rsvpEnabled,
          anonymousRsvpEnabled,
        }),
      )
    } catch {
      // The action error state renders below the form.
    }
  }

  function close() {
    open = false
    loadedFor = ""
    settings = null
    loadError = ""
    rsvpEnabled = false
    anonymousRsvpEnabled = false
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-xl">
    <h3 class="text-lg font-bold">RSVP settings</h3>
    <p class="py-2 text-base-content/70">
      {calendar?.displayName ?? "Calendar"} · these defaults apply to every event unless an event overrides them
    </p>

    {#if loadError}
      <div role="alert" class="alert alert-error mb-2">{loadError}</div>
    {/if}

    {#if settings}
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-3 rounded-box border border-base-300 p-3">
          <label class="label cursor-pointer justify-start gap-3 py-0">
            <input type="checkbox" class="toggle toggle-sm" bind:checked={rsvpEnabled} disabled={busy} />
            <span class="label-text">Allow RSVP</span>
          </label>
          <p class="text-xs text-base-content/60">
            Anyone who can see the calendar can respond to its events. Invited guests can always respond.
          </p>

          <label class="label cursor-pointer justify-start gap-3 py-0">
            <input type="checkbox" class="toggle toggle-sm" bind:checked={anonymousRsvpEnabled} disabled={busy} />
            <span class="label-text">Allow anonymous RSVP</span>
          </label>
          <p class="text-xs text-base-content/60">
            Anonymous guests can respond from a shared event link. They are asked for their name and email.
          </p>
        </div>

        {#if errorText}
          <p class="text-error text-sm">{errorText}</p>
        {/if}
        {#if saveRsvpSettings.isSuccess && !dirty && !errorText}
          <p class="text-sm text-success">RSVP settings saved.</p>
        {/if}
      </div>
    {:else if !loadError}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={close}>Cancel</button>
      <button type="button" class="btn btn-primary" disabled={busy || !settings} onclick={() => void save()}>
        {saveRsvpSettings.isPending ? "Saving…" : "Save"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
