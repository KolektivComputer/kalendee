<script lang="ts">
  import { Link, router, useAction } from "@kolektiv/keel-svelte"
  import ChevronLeft from "@lucide/svelte/icons/chevron-left"
  import ChevronRight from "@lucide/svelte/icons/chevron-right"
  import Menu from "@lucide/svelte/icons/menu"
  import type {
    CalendarSummary,
    CreateCalendarIn,
    CreateEventIn,
    DeleteCalendarIn,
    DeleteEventIn,
    DeletedOut,
    EventRemindersOut,
    EventSummary,
    FollowOut,
    HolidayStateOut,
    HomePage,
    MoveEventIn,
    MoveEventOut,
    SetCalendarHiddenIn,
    SetEventRemindersIn,
    SetShowHolidaysIn,
    UnfollowCalendarIn,
    UpdateCalendarIn,
    UpdateEventIn,
  } from "../page-types"
  import { isHolidayEvent } from "../colors"
  import type { ViewerOrganization } from "../organizations"
  import type { ReminderSelection } from "../reminders"
  import { settingsHref } from "../settings-ui.svelte"
  import { clientTimeZone, datesBetween } from "../time"
  import CalendarSidebar from "./CalendarSidebar.svelte"
  import EventDialog from "./EventDialog.svelte"
  import MonthGrid from "./MonthGrid.svelte"
  import RequestSlotDialog from "./RequestSlotDialog.svelte"
  import WeekGrid from "./WeekGrid.svelte"

  let {
    data,
    organizations = [],
  }: {
    data: HomePage
    organizations?: ViewerOrganization[]
  } = $props()

  const viewTimeZone = clientTimeZone()

  const createCalendar = useAction<CreateCalendarIn, CalendarSummary>("kalendee.createCalendar")
  const updateCalendar = useAction<UpdateCalendarIn, CalendarSummary>("kalendee.updateCalendar")
  const deleteCalendar = useAction<DeleteCalendarIn, DeletedOut>("kalendee.deleteCalendar")
  const setCalendarHidden = useAction<SetCalendarHiddenIn, CalendarSummary>("kalendee.setCalendarHidden")
  const setShowHolidays = useAction<SetShowHolidaysIn, HolidayStateOut>("kalendee.setShowHolidays")
  const createEvent = useAction<CreateEventIn, EventSummary>("kalendee.createEvent")
  const updateEvent = useAction<UpdateEventIn, EventSummary>("kalendee.updateEvent", { preserveState: true })
  const moveEvent = useAction<MoveEventIn, MoveEventOut>("kalendee.moveEvent", { preserveState: true })
  const deleteEvent = useAction<DeleteEventIn, DeletedOut>("kalendee.deleteEvent")
  const saveEventReminders = useAction<SetEventRemindersIn, EventRemindersOut>("kalendee.setEventReminders", {
    reload: false,
  })
  const unfollowCalendar = useAction<UnfollowCalendarIn, FollowOut>("kalendee.unfollowCalendar")

  let selectedId = $state("")
  let eventOpen = $state(false)
  let eventMode = $state<"create" | "edit">("create")
  let editingEvent = $state<EventSummary | null>(null)
  let draftStart = $state("")
  let draftEnd = $state("")
  let draftAllDay = $state(false)
  let requestOpen = $state(false)
  let requestCalendar = $state<CalendarSummary | null>(null)

  const visibleCalendars = $derived(data.calendars.filter((calendar) => !calendar.hidden))
  const visibleEvents = $derived(
    data.events.filter((event) => {
      if (isHolidayEvent(event)) return data.showHolidays
      return visibleCalendars.some((calendar) => calendar.id === event.calendarId)
    }),
  )
  const viewDates = $derived(datesBetween(data.gridStart, data.gridEnd))
  const timedDates = $derived(data.view === "day" ? [data.date] : viewDates.slice(0, 7))
  const selected = $derived(data.calendars.find((calendar) => calendar.id === selectedId))
  const requestableCalendar = $derived(
    !data.readOnly &&
      selected &&
      (selected.permission === "read" || selected.permission === "follow") &&
      selected.requestsEnabled
      ? selected
      : null,
  )
  const canEditSelected = $derived(
    !data.readOnly && (selected?.permission === "owner" || selected?.permission === "write"),
  )
  const writableCalendars = $derived(
    data.calendars.filter((calendar) => calendar.permission === "owner" || calendar.permission === "write"),
  )
  const eventDialogReadOnly = $derived(
    eventMode === "edit" && editingEvent ? !canWriteCalendar(editingEvent.calendarId) : !canEditSelected,
  )

  $effect(() => {
    if (!data.calendars.some((calendar) => calendar.id === selectedId)) {
      selectedId = visibleCalendars[0]?.id ?? data.calendars[0]?.id ?? ""
    }
  })

  $effect(() => {
    if (typeof window === "undefined") return
    if (data.timeZone === viewTimeZone) return
    const url = new URL(window.location.href)
    if (url.searchParams.get("tz") === viewTimeZone) return
    url.searchParams.set("tz", viewTimeZone)
    void router.visit(`${url.pathname}${url.search}`, { replace: true, preserveScroll: true })
  })

  function href(view: string, date: string): string {
    const params = new URLSearchParams({
      view,
      date,
      tz: viewTimeZone,
    })
    if (data.viewingUser) params.set("as", data.viewingUser.id)
    return `/?${params.toString()}`
  }

  function calendarById(id: string): CalendarSummary | undefined {
    return data.calendars.find((calendar) => calendar.id === id)
  }

  function canWriteCalendar(id: string): boolean {
    if (data.readOnly) return false
    const permission = calendarById(id)?.permission
    return permission === "owner" || permission === "write"
  }

  function openDraft(draft: { start: string; end: string; allDay: boolean }) {
    if (data.readOnly) return
    if (!canEditSelected) {
      const fallback = writableCalendars[0]
      if (!fallback) return
      selectedId = fallback.id
    }
    eventMode = "create"
    editingEvent = null
    draftStart = draft.start
    draftEnd = draft.end
    draftAllDay = draft.allDay
    eventOpen = true
  }

  function openEvent(event: EventSummary) {
    if (isHolidayEvent(event)) {
      void router.visit(settingsHref("holidays"))
      return
    }
    eventMode = "edit"
    editingEvent = event
    eventOpen = true
  }

  function onCreateCalendar(input: CreateCalendarIn) {
    return createCalendar.mutateAsync(input)
  }

  function onUpdateCalendar(input: UpdateCalendarIn) {
    return updateCalendar.mutateAsync(input)
  }

  function onDeleteCalendar(id: string) {
    return deleteCalendar.mutateAsync({ id })
  }

  function onHiddenCalendar(id: string, hidden: boolean) {
    return setCalendarHidden.mutateAsync({ id, hidden })
  }

  async function persistEventReminders(eventId: string, reminders: ReminderSelection) {
    saveEventReminders.reset()
    await saveEventReminders.mutateAsync({
      eventId,
      useDefaults: reminders.useDefaults,
      offsetsSeconds: reminders.useDefaults ? [] : reminders.offsetsSeconds,
    })
  }

  async function onCreateEvent(input: CreateEventIn, reminders: ReminderSelection) {
    const created = await createEvent.mutateAsync(input)
    try {
      await persistEventReminders(created.id, reminders)
      eventOpen = false
    } catch (error) {
      // The event exists now; switch to edit so a retry cannot duplicate it.
      eventMode = "edit"
      editingEvent = created
      throw error
    }
  }

  async function onUpdateEvent(input: UpdateEventIn, reminders: ReminderSelection) {
    const updated = await updateEvent.mutateAsync(input)
    await persistEventReminders(updated.id, reminders)
    eventOpen = false
  }

  function onMoveEvent(move: { id: string; start: string; end: string; etag: string }): Promise<EventSummary> {
    const event = data.events.find((item) => item.id === move.id)
    if (!event || !canWriteCalendar(event.calendarId)) {
      return Promise.reject(new Error(`Cannot move event ${move.id}`))
    }
    return updateEvent.mutateAsync(move)
  }

  function onMoveToCalendar({ event, calendarId }: { event: EventSummary; calendarId: string }) {
    if (!canWriteCalendar(event.calendarId) || !canWriteCalendar(calendarId)) return
    void moveEvent
      .mutateAsync({
        id: event.id,
        calendarId,
        scope: "following",
        from: event.recurrence ? event.start : null,
        etag: event.etag,
      })
      .catch(() => undefined)
  }

  function onDeleteEvent(event: EventSummary) {
    if (isHolidayEvent(event) || !canWriteCalendar(event.calendarId)) return
    void deleteEvent
      .mutateAsync({ id: event.id, etag: event.etag })
      .then(() => {
        eventOpen = false
      })
      .catch(() => undefined)
  }

  function onUnfollow(id: string) {
    return unfollowCalendar.mutateAsync({ calendarId: id })
  }
</script>

<div class="drawer lg:drawer-open h-full min-h-0">
  <input id="calendar-drawer" type="checkbox" class="drawer-toggle" />
  <div class="drawer-content flex h-full min-h-0 flex-col overflow-hidden">
    {#if data.viewingUser}
      <div role="alert" class="alert alert-info rounded-none">
        <span>
          Viewing {data.viewingUser.displayName}
          <code>({data.viewingUser.username})</code> — read only.
        </span>
        <Link class="btn btn-sm" href={href(data.view, data.date)}>Back to mine</Link>
      </div>
    {/if}
    <div class="flex shrink-0 flex-wrap items-center gap-3 border-b border-base-300 px-3 py-2">
      <label for="calendar-drawer" class="btn btn-ghost btn-square drawer-button lg:hidden">
        <Menu class="h-5 w-5" />
      </label>
      <div class="join">
        <Link class="btn join-item" href={href(data.view, data.previousDate)} aria-label="Previous">
          <ChevronLeft class="h-4 w-4" />
        </Link>
        <Link class="btn join-item" href={href(data.view, data.today)}>Today</Link>
        <Link class="btn join-item" href={href(data.view, data.nextDate)} aria-label="Next">
          <ChevronRight class="h-4 w-4" />
        </Link>
      </div>
      <h1 class="text-lg font-semibold tracking-tight">{data.label}</h1>
      <div role="tablist" class="tabs tabs-box ml-auto">
        <Link role="tab" class={data.view === "day" ? "tab tab-active" : "tab"} href={href("day", data.date)}>Day</Link>
        <Link role="tab" class={data.view === "week" ? "tab tab-active" : "tab"} href={href("week", data.date)}>
          Week
        </Link>
        <Link role="tab" class={data.view === "month" ? "tab tab-active" : "tab"} href={href("month", data.date)}>
          Month
        </Link>
      </div>
    </div>
    {#if data.calendars.length === 0}
      <p class="px-4 py-3 text-sm text-base-content/60">
        Create a calendar in the sidebar, then drag on the grid to add events.
      </p>
    {/if}
    {#if requestableCalendar}
      <div class="flex shrink-0 items-center gap-3 border-b border-base-300 bg-base-200 px-3 py-1.5 text-sm">
        <span class="min-w-0 truncate text-base-content/80">
          {requestableCalendar.ownerName} accepts time requests
        </span>
        <button
          type="button"
          class="btn btn-primary btn-xs ml-auto"
          onclick={() => {
            requestCalendar = requestableCalendar
            requestOpen = true
          }}
        >
          Request a time…
        </button>
      </div>
    {/if}
    <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
      {#if data.view === "month"}
        <MonthGrid
          dates={viewDates}
          monthStart={data.date}
          timeZone={viewTimeZone}
          calendars={visibleCalendars}
          events={visibleEvents}
          today={data.today}
          readOnly={data.readOnly}
          onDraft={openDraft}
          onSelect={openEvent}
          onDay={(date) => router.visit(href("day", date))}
        />
      {:else}
        <WeekGrid
          dates={timedDates}
          timeZone={viewTimeZone}
          calendars={visibleCalendars}
          events={visibleEvents}
          readOnly={data.readOnly}
          moveTargets={writableCalendars}
          onDraft={openDraft}
          onMove={onMoveEvent}
          onSelect={openEvent}
          onDelete={onDeleteEvent}
          onMoveToCalendar={onMoveToCalendar}
        />
      {/if}
    </div>
  </div>
  <div class="drawer-side z-20">
    <label for="calendar-drawer" aria-label="close sidebar" class="drawer-overlay"></label>
    <aside class="bg-base-100 min-h-full w-64 border-r border-base-300">
      <CalendarSidebar
        calendars={data.calendars}
        bind:selectedId
        timeZone={data.viewer?.timeZone || viewTimeZone}
        showHolidays={data.showHolidays}
        friends={data.friends}
        friendRequests={data.friendRequests}
        organizations={data.readOnly ? [] : organizations}
        teams={data.readOnly ? [] : data.teams}
        createPending={createCalendar.isPending}
        updatePending={updateCalendar.isPending}
        deletePending={deleteCalendar.isPending}
        createError={createCalendar.error}
        updateError={updateCalendar.error}
        readOnly={data.readOnly}
        onCreate={onCreateCalendar}
        onUpdate={onUpdateCalendar}
        onDelete={onDeleteCalendar}
        onHidden={onHiddenCalendar}
        onShowHolidays={(show: boolean) => setShowHolidays.mutateAsync({ showHolidays: show })}
        onUnfollow={onUnfollow}
      />
    </aside>
  </div>
</div>

<EventDialog
  bind:open={eventOpen}
  bind:calendarId={selectedId}
  mode={eventMode}
  event={editingEvent}
  calendars={writableCalendars}
  readOnly={eventDialogReadOnly}
  viewer={data.viewer}
  timeZone={viewTimeZone}
  {draftStart}
  {draftEnd}
  {draftAllDay}
  createPending={createEvent.isPending}
  updatePending={updateEvent.isPending}
  deletePending={deleteEvent.isPending}
  createError={createEvent.error}
  updateError={updateEvent.error}
  reminderPending={saveEventReminders.isPending}
  reminderError={saveEventReminders.error}
  onCreate={onCreateEvent}
  onUpdate={onUpdateEvent}
  onDelete={onDeleteEvent}
/>

<RequestSlotDialog bind:open={requestOpen} calendar={requestCalendar} />
