<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage, fieldError } from "../errors"
  import type {
    CalendarSummary,
    CreateEventIn,
    EventAttendeeSummary,
    EventAttendeesIn,
    EventAttendeesOut,
    EventRemindersOut,
    EventSummary,
    GetEventRemindersIn,
    InviteToEventIn,
    PublicRsvpIn,
    RecurrenceIn,
    ReminderSettingsIn,
    ReminderSettingsOut,
    RemoveEventAttendeeIn,
    RespondEventInviteIn,
    RsvpOut,
    SetEventOpenRsvpIn,
    UpdateEventIn,
    Viewer,
  } from "../page-types"
  import {
    formatOffset,
    offsetsFromRows,
    reminderRowsError,
    rowsFromOffsets,
    type ReminderRow,
    type ReminderSelection,
  } from "../reminders"
  import { addDays, instantToZoned, zonedToInstant } from "../time"
  import ReminderEditor from "./ReminderEditor.svelte"

  let {
    open = $bindable(false),
    mode,
    event = null,
    calendars,
    calendarId = $bindable(""),
    timeZone,
    draftStart = "",
    draftEnd = "",
    draftAllDay = false,
    readOnly = false,
    viewer = null,
    createPending = false,
    updatePending = false,
    deletePending = false,
    createError = null,
    updateError = null,
    reminderPending = false,
    reminderError = null,
    onCreate,
    onUpdate,
    onDelete,
  }: {
    open: boolean
    mode: "create" | "edit"
    event?: EventSummary | null
    calendars: CalendarSummary[]
    calendarId: string
    timeZone: string
    draftStart?: string
    draftEnd?: string
    draftAllDay?: boolean
    readOnly?: boolean
    viewer?: Viewer | null
    createPending?: boolean
    updatePending?: boolean
    deletePending?: boolean
    createError?: unknown
    updateError?: unknown
    reminderPending?: boolean
    reminderError?: unknown
    onCreate: (input: CreateEventIn, reminders: ReminderSelection) => Promise<void>
    onUpdate: (input: UpdateEventIn, reminders: ReminderSelection) => Promise<void>
    onDelete: (event: EventSummary) => void
  } = $props()

  const getEventReminders = useAction<GetEventRemindersIn, EventRemindersOut>("kalendee.eventReminders", {
    reload: false,
  })
  const getReminderSettings = useAction<ReminderSettingsIn, ReminderSettingsOut>("kalendee.reminderSettings", {
    reload: false,
  })
  const getAttendees = useAction<EventAttendeesIn, EventAttendeesOut>("kalendee.eventAttendees", {
    reload: false,
  })
  const inviteToEvent = useAction<InviteToEventIn, EventAttendeesOut>("kalendee.inviteToEvent", {
    reload: false,
  })
  const removeEventAttendee = useAction<RemoveEventAttendeeIn, EventAttendeesOut>("kalendee.removeEventAttendee", {
    reload: false,
  })
  const setEventOpenRsvp = useAction<SetEventOpenRsvpIn, EventSummary>("kalendee.setEventOpenRsvp", {
    reload: false,
  })
  const respondInvite = useAction<RespondEventInviteIn, RsvpOut>("kalendee.respondEventInvite")
  const respondPublic = useAction<PublicRsvpIn, RsvpOut>("kalendee.publicRsvp")

  let title = $state("")
  let description = $state("")
  let url = $state("")
  let allDay = $state(false)
  let startDate = $state("")
  let startTime = $state("09:00")
  let endDate = $state("")
  let endTime = $state("09:30")
  let repeats = $state(false)
  let frequency = $state("WEEKLY")
  let interval = $state(1)
  let untilDate = $state("")
  let count = $state("")
  let dialog = $state<HTMLDialogElement | undefined>()
  let reminderUseDefaults = $state(true)
  let reminderRows = $state<ReminderRow[]>([])
  let reminderDefaults = $state<number[]>([])
  let reminderNotifyAtStart = $state(false)
  let reminderLoading = $state(false)
  let reminderLoadError = $state("")
  let remindersFor = $state("")
  let attendees = $state<EventAttendeeSummary[]>([])
  let attendeesOpenRsvp = $state(false)
  let attendeesFor = $state("")
  let attendeesLoading = $state(false)
  let attendeesLoadError = $state("")
  let inviteUsername = $state("")
  let rsvpStatus = $state<string | null>(null)
  let rsvpMessage = $state("")
  let copied = $state(false)
  let copyTimer: ReturnType<typeof setTimeout> | undefined

  $effect(() => {
    if (!open) return
    const sourceStart = mode === "edit" && event ? event.start : draftStart
    const sourceEnd = mode === "edit" && event ? event.end : draftEnd
    const sourceAllDay = mode === "edit" && event ? event.allDay : draftAllDay
    const start = instantToZoned(sourceStart || new Date().toISOString(), timeZone)
    const end = instantToZoned(sourceEnd || sourceStart || new Date().toISOString(), timeZone)
    title = mode === "edit" && event ? event.title : ""
    description = mode === "edit" && event ? (event.description ?? "") : ""
    url = mode === "edit" && event ? (event.url ?? "") : ""
    allDay = sourceAllDay
    repeats = Boolean(mode === "edit" && event?.recurrence)
    frequency = event?.recurrence?.frequency ?? "WEEKLY"
    interval = event?.recurrence?.interval ?? 1
    untilDate = event?.recurrence?.until ? instantToZoned(event.recurrence.until, timeZone).date : ""
    count = event?.recurrence?.count != null ? String(event.recurrence.count) : ""
    startDate = start.date
    endDate = sourceAllDay && end.minutes === 0 ? addDays(end.date, -1) : end.date
    startTime = `${String(Math.floor(start.minutes / 60)).padStart(2, "0")}:${String(start.minutes % 60).padStart(2, "0")}`
    endTime = `${String(Math.floor(end.minutes / 60)).padStart(2, "0")}:${String(end.minutes % 60).padStart(2, "0")}`
    if (mode === "edit" && event) calendarId = event.calendarId
  })

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  function close() {
    open = false
    title = ""
    description = ""
    url = ""
    allDay = false
    startDate = ""
    startTime = "09:00"
    endDate = ""
    endTime = "09:30"
    repeats = false
    frequency = "WEEKLY"
    interval = 1
    untilDate = ""
    count = ""
    reminderUseDefaults = true
    reminderRows = []
    reminderDefaults = []
    reminderNotifyAtStart = false
    reminderLoadError = ""
    remindersFor = ""
    attendees = []
    attendeesOpenRsvp = false
    attendeesFor = ""
    attendeesLoadError = ""
    inviteUsername = ""
    rsvpStatus = null
    rsvpMessage = ""
    copied = false
  }

  $effect(() => {
    if (!open) {
      remindersFor = ""
      return
    }
    if (readOnly) return
    const key = mode === "edit" && event ? event.id : "new"
    if (remindersFor === key) return
    if (remindersFor === "new") {
      // The event was just created; keep the in-progress reminder edits.
      remindersFor = key
      return
    }
    remindersFor = key
    untrack(() => {
      void loadReminders(key)
    })
  })

  $effect(() => {
    if (!open) {
      attendeesFor = ""
      return
    }
    if (readOnly || mode !== "edit" || !event) return
    const key = event.id
    if (attendeesFor === key) return
    attendeesFor = key
    untrack(() => {
      getAttendees.reset()
      inviteToEvent.reset()
      removeEventAttendee.reset()
      setEventOpenRsvp.reset()
      inviteUsername = ""
      attendees = []
      attendeesLoadError = ""
      copied = false
      void loadAttendees(key)
    })
  })

  $effect(() => {
    if (!open || mode !== "edit" || !event) return
    rsvpStatus = event.rsvpStatus ?? null
    rsvpMessage = ""
    copied = false
  })

  $effect(() => {
    return () => {
      if (copyTimer) clearTimeout(copyTimer)
    }
  })

  const error = $derived(mode === "create" ? createError : updateError)
  const pending = $derived(mode === "create" ? createPending : updatePending)
  const reminderRowsValueError = $derived(reminderUseDefaults ? "" : reminderRowsError(reminderRows))
  const reminderOffsetsError = $derived(fieldError(reminderError, "offsets"))
  const reminderErrorText = $derived(actionMessage(reminderError))
  const reminderSummary = $derived(reminderDefaults.map(formatOffset).join(", "))
  const canManageAttendees = $derived(mode === "edit" && event != null && !readOnly)
  const isAttendee = $derived(event?.rsvpStatus != null)
  const canRespond = $derived(
    mode === "edit" && event != null && (viewer != null || isAttendee || event.openRsvp),
  )
  const attendeesBusy = $derived(inviteToEvent.isPending || removeEventAttendee.isPending)
  const attendeeFieldError = $derived(fieldError(inviteToEvent.error, "username"))
  const attendeeInviteError = $derived(actionMessage(inviteToEvent.error))
  const attendeeRemoveError = $derived(actionMessage(removeEventAttendee.error))
  const openRsvpErrorText = $derived(actionMessage(setEventOpenRsvp.error))
  const rsvpPending = $derived(respondInvite.isPending || respondPublic.isPending)
  const rsvpError = $derived(actionMessage(respondInvite.error) || actionMessage(respondPublic.error))

  async function loadReminders(key: string) {
    reminderLoading = true
    reminderLoadError = ""
    try {
      if (key === "new") {
        const settings = await getReminderSettings.mutateAsync({})
        reminderUseDefaults = true
        reminderDefaults = settings.defaultOffsetsSeconds
        reminderNotifyAtStart = settings.notifyAtStart
        reminderRows = rowsFromOffsets(settings.defaultOffsetsSeconds)
      } else {
        const result = await getEventReminders.mutateAsync({ eventId: key })
        reminderUseDefaults = result.useDefaults
        reminderDefaults = result.defaultOffsetsSeconds
        reminderNotifyAtStart = result.notifyAtStart
        reminderRows = rowsFromOffsets(result.offsetsSeconds)
      }
    } catch {
      reminderLoadError = "Could not load reminders."
    } finally {
      reminderLoading = false
    }
  }

  async function loadAttendees(id: string) {
    attendeesLoading = true
    attendeesLoadError = ""
    try {
      const result = await getAttendees.mutateAsync({ eventId: id })
      attendees = result.attendees
      attendeesOpenRsvp = result.openRsvp
    } catch {
      attendeesLoadError = "Could not load attendees."
    } finally {
      attendeesLoading = false
    }
  }

  async function inviteAttendee() {
    if (!event || attendeesBusy) return
    const username = inviteUsername.trim()
    if (username === "") return
    try {
      await inviteToEvent.mutateAsync({ eventId: event.id, username })
    } catch {
      return
    }
    inviteUsername = ""
    await loadAttendees(event.id)
  }

  async function removeAttendee(id: string) {
    if (!event) return
    await removeEventAttendee.mutateAsync({ eventId: event.id, attendeeId: id }).catch(() => undefined)
    await loadAttendees(event.id)
  }

  async function toggleOpenRsvp(enabled: boolean) {
    if (!event) return
    try {
      const updated = await setEventOpenRsvp.mutateAsync({ eventId: event.id, enabled })
      attendeesOpenRsvp = updated.openRsvp
    } catch {
      attendeesOpenRsvp = event.openRsvp
    }
  }

  async function respond(status: string) {
    if (!event || rsvpPending) return
    rsvpMessage = ""
    try {
      const result =
        viewer != null
          ? await respondInvite.mutateAsync({ eventId: event.id, status })
          : await respondPublic.mutateAsync({
              eventId: event.id,
              name: viewer?.displayName ?? "",
              email: viewer?.email ?? null,
              status,
            })
      rsvpStatus = result.status
      rsvpMessage = `Thanks — your response: ${rsvpLabel(result.status)}`
    } catch {
      rsvpMessage = ""
    }
  }

  function rsvpUrl(eventId: string): string {
    if (typeof window === "undefined") return `/rsvp/${eventId}`
    return `${window.location.origin}/rsvp/${eventId}`
  }

  async function copyRsvpLink() {
    if (!event) return
    try {
      await navigator.clipboard.writeText(rsvpUrl(event.id))
      copied = true
      if (copyTimer) clearTimeout(copyTimer)
      copyTimer = setTimeout(() => (copied = false), 2000)
    } catch {
      copied = false
    }
  }

  function attendeeLabel(attendee: EventAttendeeSummary): string {
    return attendee.displayName ?? attendee.name ?? attendee.email ?? attendee.username ?? "Guest"
  }

  function attendeeSecondary(attendee: EventAttendeeSummary): string {
    const label = attendeeLabel(attendee)
    if (attendee.username && attendee.username !== label) return attendee.username
    if (attendee.email && attendee.email !== label) return attendee.email
    return ""
  }

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function rsvpLabel(status: string): string {
    switch (status) {
      case "yes":
        return "Yes"
      case "no":
        return "No"
      case "maybe":
        return "Maybe"
      case "invited":
        return "Invited"
      default:
        return status
    }
  }

  function rsvpBadgeClass(status: string): string {
    switch (status) {
      case "yes":
        return "badge-success"
      case "no":
        return "badge-error"
      case "maybe":
        return "badge-warning"
      default:
        return "badge-ghost"
    }
  }

  function minutesFromTime(value: string): number {
    const [hour, minute] = value.split(":").map(Number)
    return hour * 60 + minute
  }

  function startInstant(): string {
    return allDay
      ? zonedToInstant(startDate, 0, timeZone)
      : zonedToInstant(startDate, minutesFromTime(startTime), timeZone)
  }

  function endInstant(): string {
    return allDay
      ? zonedToInstant(addDays(endDate || startDate, 1), 0, timeZone)
      : zonedToInstant(endDate, minutesFromTime(endTime), timeZone)
  }

  function recurrencePayload(): RecurrenceIn | null {
    if (!repeats) return null
    const parsedCount = count.trim() === "" ? null : Number(count)
    return {
      frequency,
      interval,
      until: untilDate.trim() === "" ? null : zonedToInstant(untilDate, 0, timeZone),
      count: parsedCount != null && Number.isFinite(parsedCount) ? parsedCount : null,
    }
  }

  function createInput(): CreateEventIn {
    return {
      calendarId,
      title,
      description: description.trim() === "" ? null : description,
      location: null,
      url: url.trim() === "" ? null : url,
      start: startInstant(),
      end: endInstant(),
      allDay,
      timeZone,
      recurrence: recurrencePayload(),
    }
  }

  function updateInput(): UpdateEventIn | null {
    if (!event) return null
    return {
      id: event.id,
      title,
      description,
      url: url.trim() === "" ? null : url,
      start: startInstant(),
      end: endInstant(),
      allDay,
      recurrence: recurrencePayload(),
      clearRecurrence: !repeats,
      etag: event.etag,
    }
  }

  async function submit() {
    if (readOnly) return
    if (!reminderUseDefaults && reminderRowsError(reminderRows) !== "") return
    const reminders: ReminderSelection = {
      useDefaults: reminderUseDefaults,
      offsetsSeconds: reminderUseDefaults ? [] : offsetsFromRows(reminderRows),
    }
    try {
      if (mode === "create") {
        await onCreate(createInput(), reminders)
      } else {
        const input = updateInput()
        if (input) await onUpdate(input, reminders)
      }
    } catch {
      // Action errors render through the error props.
    }
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box">
    <h3 class="text-lg font-bold">
      {mode === "create" ? "New event" : readOnly ? "View event" : "Edit event"}
    </h3>
    <p class="py-2 text-base-content/70">
      {mode === "create"
        ? "Add it to one of your calendars."
        : readOnly
          ? "You have read-only access to this calendar."
          : "Times snap in 15-minute steps on the grid."}
    </p>
    <form
      class="flex flex-col gap-4"
      onsubmit={(submitEvent) => {
        submitEvent.preventDefault()
        void submit()
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Title</legend>
        <input id="event-title" class="input w-full" bind:value={title} required disabled={readOnly} />
        {#if fieldError(error, "title")}
          <p class="label text-error">{fieldError(error, "title")}</p>
        {/if}
      </fieldset>
      {#if mode === "create"}
        <fieldset class="fieldset">
          <legend class="fieldset-legend">Calendar</legend>
          <select id="event-calendar" class="select w-full" bind:value={calendarId} aria-label="Calendar" disabled={readOnly}>
            {#each calendars as calendar (calendar.id)}
              <option value={calendar.id}>{calendar.displayName}</option>
            {/each}
          </select>
        </fieldset>
      {/if}
      <label class="label cursor-pointer justify-start gap-2">
        <input id="all-day" type="checkbox" class="checkbox" bind:checked={allDay} disabled={readOnly} />
        <span class="label-text">All day</span>
      </label>
      <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <fieldset class="fieldset">
          <legend class="fieldset-legend">Starts</legend>
          <input id="start-date" class="input w-full" type="date" bind:value={startDate} required disabled={readOnly} />
        </fieldset>
        {#if !allDay}
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Time</legend>
            <input id="start-time" class="input w-full" type="time" step="900" bind:value={startTime} required disabled={readOnly} />
          </fieldset>
        {:else}
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Ends</legend>
            <input id="end-date" class="input w-full" type="date" bind:value={endDate} required disabled={readOnly} />
          </fieldset>
        {/if}
      </div>
      {#if !allDay}
        <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Ends</legend>
            <input id="end-date-timed" class="input w-full" type="date" bind:value={endDate} required disabled={readOnly} />
          </fieldset>
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Time</legend>
            <input id="end-time" class="input w-full" type="time" step="900" bind:value={endTime} required disabled={readOnly} />
          </fieldset>
        </div>
      {/if}
      {#if fieldError(error, "start") || fieldError(error, "end")}
        <p class="text-error text-sm">{fieldError(error, "start") ?? fieldError(error, "end")}</p>
      {/if}
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Notes</legend>
        <textarea id="event-notes" class="textarea w-full" bind:value={description} disabled={readOnly}></textarea>
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Link</legend>
        <input id="event-link" class="input w-full" type="url" bind:value={url} placeholder="https://" disabled={readOnly} />
        {#if fieldError(error, "url")}
          <p class="label text-error">{fieldError(error, "url")}</p>
        {/if}
      </fieldset>
      <label class="label cursor-pointer justify-start gap-2">
        <input id="repeats" type="checkbox" class="checkbox" bind:checked={repeats} disabled={readOnly} />
        <span class="label-text">Repeats</span>
      </label>
      {#if repeats}
        <p class="text-sm text-base-content/50">Edits apply to the whole series. Times are stored in UTC.</p>
        <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Every</legend>
            <input id="event-interval" class="input w-full" type="number" min="1" max="99" bind:value={interval} required disabled={readOnly} />
          </fieldset>
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Unit</legend>
            <select id="event-freq" class="select w-full" bind:value={frequency} disabled={readOnly}>
              <option value="DAILY">days</option>
              <option value="WEEKLY">weeks</option>
              <option value="MONTHLY">months</option>
              <option value="YEARLY">years</option>
            </select>
          </fieldset>
        </div>
        <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Until</legend>
            <input id="event-until" class="input w-full" type="date" bind:value={untilDate} disabled={readOnly} />
          </fieldset>
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Count</legend>
            <input id="event-count" class="input w-full" type="number" min="1" max="999" bind:value={count} placeholder="optional" disabled={readOnly} />
          </fieldset>
        </div>
        {#if fieldError(error, "recurrence")}
          <p class="text-error text-sm">{fieldError(error, "recurrence")}</p>
        {/if}
      {/if}
      {#if !readOnly}
        <div class="flex flex-col gap-2 rounded-box border border-base-300 p-3">
          <h4 class="text-sm font-semibold">Reminders</h4>
          {#if reminderLoadError}
            <p class="text-error text-sm">{reminderLoadError}</p>
          {:else if reminderLoading}
            <p class="text-sm text-base-content/60">Loading reminders…</p>
          {/if}
          <label class="label cursor-pointer justify-start gap-2 py-0">
            <input
              id="event-use-default-reminders"
              type="checkbox"
              class="toggle toggle-sm"
              bind:checked={reminderUseDefaults}
              disabled={reminderLoading || reminderPending}
            />
            <span class="label-text">Use default reminders</span>
          </label>
          {#if reminderUseDefaults}
            {#if reminderDefaults.length > 0}
              <p class="text-sm text-base-content/70">{reminderSummary}</p>
            {:else}
              <p class="text-sm text-base-content/60">You have no default reminders.</p>
            {/if}
          {:else}
            <ReminderEditor bind:rows={reminderRows} disabled={reminderLoading || reminderPending} />
            {#if reminderRowsValueError}
              <p class="text-error text-sm">{reminderRowsValueError}</p>
            {/if}
          {/if}
          {#if reminderNotifyAtStart}
            <p class="text-sm text-base-content/60">Also notify when the event starts.</p>
          {/if}
          {#if reminderOffsetsError}
            <p class="text-error text-sm">{reminderOffsetsError}</p>
          {:else if reminderErrorText}
            <p class="text-error text-sm">{reminderErrorText}</p>
          {/if}
        </div>
      {/if}
      {#if canManageAttendees && event}
        <div class="flex flex-col gap-2 rounded-box border border-base-300 p-3">
          <div class="flex items-center justify-between gap-2">
            <h4 class="text-sm font-semibold">Attendees</h4>
            {#if attendees.length > 0}
              <span class="text-xs text-base-content/60">{attendees.length}</span>
            {/if}
          </div>
          {#if attendeesLoadError}
            <p class="text-error text-sm">{attendeesLoadError}</p>
          {:else if attendeesLoading}
            <p class="text-sm text-base-content/60">Loading attendees…</p>
          {:else if attendees.length === 0}
            <p class="text-sm text-base-content/60">No one is invited yet.</p>
          {:else}
            <ul class="flex flex-col">
              {#each attendees as attendee (attendee.id)}
                <li class="flex items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                  {#if attendee.avatarUrl}
                    <img class="h-7 w-7 rounded-full object-cover" src={attendee.avatarUrl} alt="" />
                  {:else}
                    <span
                      class="flex h-7 w-7 items-center justify-center rounded-full bg-base-content/20 text-[0.6rem] font-semibold leading-none"
                      aria-hidden="true"
                    >
                      {initials(attendeeLabel(attendee))}
                    </span>
                  {/if}
                  <div class="min-w-0 flex-1">
                    <div class="truncate text-sm">{attendeeLabel(attendee)}</div>
                    {#if attendeeSecondary(attendee)}
                      <div class="truncate text-xs text-base-content/60">{attendeeSecondary(attendee)}</div>
                    {/if}
                  </div>
                  <span class="badge badge-sm {rsvpBadgeClass(attendee.status)}">{rsvpLabel(attendee.status)}</span>
                  <button
                    type="button"
                    class="btn btn-ghost btn-xs text-error"
                    disabled={attendeesBusy}
                    onclick={() => void removeAttendee(attendee.id)}
                  >
                    Remove
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
          <div class="flex flex-col gap-1">
            <div class="flex flex-col gap-2 sm:flex-row">
              <input
                class="input input-sm w-full"
                bind:value={inviteUsername}
                placeholder="Username or email"
                aria-label="Username or email"
                disabled={attendeesBusy}
                onkeydown={(keyEvent) => {
                  if (keyEvent.key === "Enter") {
                    keyEvent.preventDefault()
                    void inviteAttendee()
                  }
                }}
              />
              <button
                type="button"
                class="btn btn-primary btn-sm"
                disabled={attendeesBusy || inviteUsername.trim() === ""}
                onclick={() => void inviteAttendee()}
              >
                {inviteToEvent.isPending ? "Inviting…" : "Invite"}
              </button>
            </div>
            {#if attendeeFieldError}
              <p class="text-error text-sm">{attendeeFieldError}</p>
            {:else if attendeeInviteError}
              <p class="text-error text-sm">{attendeeInviteError}</p>
            {/if}
          </div>
          <label class="label cursor-pointer justify-start gap-2 py-0">
            <input
              type="checkbox"
              class="toggle toggle-sm"
              checked={attendeesOpenRsvp}
              disabled={setEventOpenRsvp.isPending}
              onchange={(changeEvent) => void toggleOpenRsvp(changeEvent.currentTarget.checked)}
            />
            <span class="label-text">Open RSVP</span>
          </label>
          {#if attendeesOpenRsvp}
            <div class="join w-full">
              <input
                class="input input-sm join-item w-full"
                readonly
                value={rsvpUrl(event.id)}
                aria-label="Open RSVP link"
              />
              <button type="button" class="btn btn-sm join-item" onclick={() => void copyRsvpLink()}>
                {copied ? "Copied" : "Copy"}
              </button>
            </div>
          {:else}
            <input
              class="input input-sm w-full"
              readonly
              disabled
              value=""
              placeholder="Turn on Open RSVP to share a link"
              aria-label="Open RSVP link"
            />
          {/if}
          <p class="text-xs text-base-content/60">
            Anyone with the link can respond. Anonymous access requires the calendar to be public; signed-in users can
            always respond on signed-in calendars.
          </p>
          {#if openRsvpErrorText}
            <p class="text-error text-sm">{openRsvpErrorText}</p>
          {/if}
          {#if attendeeRemoveError}
            <p class="text-error text-sm">{attendeeRemoveError}</p>
          {/if}
        </div>
      {/if}
      {#if canRespond && event}
        <div class="flex flex-col gap-2 rounded-box border border-base-300 p-3">
          <h4 class="text-sm font-semibold">Your response</h4>
          {#if rsvpStatus}
            <p class="text-sm text-base-content/70">
              Current:
              <span class="badge badge-sm {rsvpBadgeClass(rsvpStatus)}">{rsvpLabel(rsvpStatus)}</span>
            </p>
          {/if}
          <div class="flex flex-wrap gap-2">
            <button type="button" class="btn btn-sm" disabled={rsvpPending} onclick={() => void respond("yes")}>Yes</button>
            <button type="button" class="btn btn-sm" disabled={rsvpPending} onclick={() => void respond("no")}>No</button>
            <button type="button" class="btn btn-sm" disabled={rsvpPending} onclick={() => void respond("maybe")}>
              Maybe
            </button>
          </div>
          {#if rsvpMessage}
            <p class="text-success text-sm">{rsvpMessage}</p>
          {/if}
          {#if rsvpError}
            <p class="text-error text-sm">{rsvpError}</p>
          {/if}
        </div>
      {/if}
      <div class="modal-action">
        {#if mode === "edit" && event && !readOnly}
          <button type="button" class="btn btn-ghost text-error mr-auto" disabled={deletePending} onclick={() => event && onDelete(event)}>
            {deletePending ? "Deleting…" : "Delete"}
          </button>
        {/if}
        <button type="button" class="btn btn-ghost" onclick={close}>{readOnly ? "Close" : "Cancel"}</button>
        {#if !readOnly}
          <button type="submit" class="btn btn-primary" disabled={pending || reminderPending}>
            {pending || reminderPending ? "Saving…" : mode === "create" ? "Create" : "Save"}
          </button>
        {/if}
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
