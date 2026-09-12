<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage } from "../errors"
  import type {
    AvailabilitySlotOut,
    CalendarSlotsIn,
    CalendarSlotsOut,
    CalendarSummary,
    PublicRequestTimeSlotIn,
    RequestTimeSlotIn,
    RequestTimeSlotOut,
  } from "../page-types"
  import { addDays, formatClock, instantToZoned, pad } from "../time"

  let {
    open = $bindable(false),
    calendar = null,
    publicToken = null,
    anonymous = false,
  }: {
    open: boolean
    calendar: CalendarSummary | null
    publicToken?: string | null
    anonymous?: boolean
  } = $props()

  const getSlots = useAction<CalendarSlotsIn, CalendarSlotsOut>("kalendee.calendarSlots", { reload: false })
  const requestSlot = useAction<RequestTimeSlotIn, RequestTimeSlotOut>("kalendee.requestTimeSlot", { reload: false })
  const publicRequestSlot = useAction<PublicRequestTimeSlotIn, RequestTimeSlotOut>("kalendee.publicRequestTimeSlot", {
    reload: false,
  })

  const today = todayIso()
  const maxDate = addDays(today, 62)

  let date = $state(today)
  let slots = $state<CalendarSlotsOut | null>(null)
  let selected = $state<AvailabilitySlotOut | null>(null)
  let message = $state("")
  let name = $state("")
  let email = $state("")
  let attempted = $state(false)
  let loadError = $state("")
  let loadedFor = $state("")
  let sent = $state(false)
  let dialog = $state<HTMLDialogElement | undefined>()
  let wasOpen = $state(false)

  const busy = $derived(getSlots.isPending || requestSlot.isPending || publicRequestSlot.isPending)
  const sending = $derived(requestSlot.isPending || publicRequestSlot.isPending)
  const errorText = $derived(actionMessage(requestSlot.error) || actionMessage(publicRequestSlot.error))
  const timeZone = $derived(slots?.timeZone ?? calendar?.timeZone ?? "UTC")
  const daySlots = $derived(slots?.days[0]?.slots ?? [])
  const hasSelectable = $derived(daySlots.some(isSelectable))
  const showRequesterFields = $derived(publicToken != null || anonymous)
  const nameError = $derived(showRequesterFields && name.trim() === "" ? "Name is required." : "")
  const emailError = $derived(
    showRequesterFields && email.trim() !== "" && !validEmail(email.trim()) ? "Enter a valid email address." : "",
  )

  function todayIso(): string {
    const now = new Date()
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  }

  function validEmail(value: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
  }

  function isSelectable(slot: AvailabilitySlotOut): boolean {
    return slot.available && Date.parse(slot.start) > Date.now()
  }

  function slotRange(slot: AvailabilitySlotOut): string {
    return `${formatClock(instantToZoned(slot.start, timeZone).minutes)}–${formatClock(
      instantToZoned(slot.end, timeZone).minutes,
    )}`
  }

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })


  $effect(() => {
    if (open && !wasOpen) {
      wasOpen = true
      untrack(() => {
        date = todayIso()
        selected = null
        message = ""
        name = ""
        email = ""
        attempted = false
        sent = false
        loadError = ""
      })
    } else if (!open && wasOpen) {
      wasOpen = false
    }
  })

  $effect(() => {
    if (!open || !calendar || !date) {
      loadedFor = ""
      return
    }
    const key = `${calendar.id}|${date}`
    if (loadedFor === key) return
    loadedFor = key
    untrack(() => {
      getSlots.reset()
      requestSlot.reset()
      publicRequestSlot.reset()
      slots = null
      selected = null
      sent = false
      loadError = ""
      void load(calendar.id, date)
    })
  })

  async function load(calendarId: string, day: string) {
    try {
      slots = await getSlots.mutateAsync({ calendarId, from: day, to: day })
      loadError = ""
    } catch {
      slots = null
      loadError = "Could not load available times."
    }
  }

  async function submit() {
    if (!calendar || !selected || sent || sending) return
    attempted = true
    if (nameError !== "" || emailError !== "") return
    const trimmedMessage = message.trim()
    const trimmedEmail = email.trim()
    try {
      if (publicToken) {
        await publicRequestSlot.mutateAsync({
          calendarToken: publicToken,
          name: name.trim(),
          email: trimmedEmail === "" ? null : trimmedEmail,
          start: selected.start,
          end: selected.end,
          message: trimmedMessage === "" ? null : trimmedMessage,
        })
      } else {
        await requestSlot.mutateAsync({
          calendarId: calendar.id,
          start: selected.start,
          end: selected.end,
          message: trimmedMessage === "" ? null : trimmedMessage,
        })
      }
      sent = true
    } catch {
      // The action error state renders below the form.
    }
  }

  function close() {
    open = false
    date = today
    loadedFor = ""
    slots = null
    selected = null
    message = ""
    name = ""
    email = ""
    attempted = false
    sent = false
    loadError = ""
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-lg">
    <h3 class="text-lg font-bold">Request a time</h3>
    <p class="py-2 text-base-content/70">
      {calendar?.ownerName ?? "The owner"} accepts time requests on {calendar?.displayName ?? "this calendar"}.
    </p>

    <div class="flex flex-wrap items-end gap-3">
      <label class="flex flex-col gap-1 text-sm" for="request-slot-date">
        <span class="label py-0">Day</span>
        <input
          id="request-slot-date"
          class="input input-sm"
          type="date"
          min={today}
          max={maxDate}
          bind:value={date}
          disabled={sending || sent}
        />
      </label>
      <p class="pb-1 text-xs text-base-content/60">Times shown in {timeZone}</p>
    </div>

    {#if loadError}
      <div role="alert" class="alert alert-error my-3">{loadError}</div>
    {:else if getSlots.isPending && !slots}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {:else if daySlots.length === 0}
      <p class="py-3 text-sm text-base-content/60">No office hours on this day.</p>
    {:else}
      <div class="mt-3 flex flex-col gap-2">
        <span class="text-sm font-semibold">Available times</span>
        {#if hasSelectable}
          <div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {#each daySlots as slot (slot.start)}
              {@const selectable = isSelectable(slot)}
              <button
                type="button"
                class="btn btn-sm"
                class:btn-primary={selected?.start === slot.start}
                disabled={!selectable || sent || sending}
                title={selectable ? undefined : "Not available"}
                onclick={() => (selected = slot)}
              >
                {slotRange(slot)}
              </button>
            {/each}
          </div>
        {:else}
          <p class="text-sm text-base-content/60">No free slots on this day.</p>
        {/if}
      </div>
    {/if}

    {#if showRequesterFields}
      <div class="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <label class="flex flex-col gap-1">
          <span class="label py-0">Name</span>
          <input
            class="input input-sm w-full"
            bind:value={name}
            placeholder="Your name"
            autocomplete="name"
            required
            disabled={sending || sent}
          />
          {#if attempted && nameError}
            <span class="text-error text-xs">{nameError}</span>
          {/if}
        </label>
        <label class="flex flex-col gap-1">
          <span class="label py-0">Email (optional)</span>
          <input
            class="input input-sm w-full"
            type="email"
            bind:value={email}
            placeholder="you@example.com"
            autocomplete="email"
            disabled={sending || sent}
          />
          {#if attempted && emailError}
            <span class="text-error text-xs">{emailError}</span>
          {/if}
        </label>
      </div>
    {/if}

    {#if selected && !sent}
      <label class="mt-3 flex flex-col gap-1">
        <span class="label py-0">Message</span>
        <textarea
          class="textarea w-full"
          rows="3"
          bind:value={message}
          disabled={sending}
          placeholder="What would you like to meet about? (optional)"
        ></textarea>
      </label>
    {/if}

    {#if sent}
      <div role="status" class="alert alert-success mt-3">
        <span>
          {anonymous
            ? `Request sent — ${calendar?.ownerName ?? "the owner"} will get back to you.`
            : `Request sent — you'll be notified when ${calendar?.ownerName ?? "the owner"} responds.`}
        </span>
      </div>
    {/if}

    {#if errorText}
      <p class="mt-2 text-error text-sm">{errorText}</p>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={close}>
        {sent ? "Done" : "Cancel"}
      </button>
      <button
        type="button"
        class="btn btn-primary"
        disabled={!selected || busy || sent}
        onclick={() => void submit()}
      >
        {sending ? "Sending…" : "Send request"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
