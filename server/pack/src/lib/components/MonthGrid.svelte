<script lang="ts">
  import { eventFill, HOLIDAY_COLOR } from "../colors"
  import { eventsOnDate, weeksOf } from "../month"
  import type { CalendarSummary, EventSummary } from "../page-types"
  import { formatClock, formatDayHeading, instantToZoned, zonedToInstant, addDays } from "../time"

  let {
    dates,
    monthStart,
    timeZone,
    calendars,
    events,
    today,
    readOnly = false,
    onDraft,
    onSelect,
    onDay,
  }: {
    dates: string[]
    monthStart: string
    timeZone: string
    calendars: CalendarSummary[]
    events: EventSummary[]
    today: string
    readOnly?: boolean
    onDraft: (draft: { start: string; end: string; allDay: boolean }) => void
    onSelect: (event: EventSummary) => void
    onDay: (date: string) => void
  } = $props()

  const weeks = $derived(weeksOf(dates))
  const colorById = $derived(Object.fromEntries(calendars.map((calendar) => [calendar.id, calendar.color])))
  const weekdays = $derived((weeks[0] ?? []).map((date) => formatDayHeading(date).weekday))
  const monthPrefix = $derived(monthStart.slice(0, 7))

  function fill(calendarId: string): string {
    if (calendarId === "holiday") return eventFill(HOLIDAY_COLOR)
    return eventFill(colorById[calendarId] ?? "primary")
  }

  function createAllDay(date: string) {
    if (readOnly) return
    onDraft({
      start: zonedToInstant(date, 0, timeZone),
      end: zonedToInstant(addDays(date, 1), 0, timeZone),
      allDay: true,
    })
  }

  function chipLabel(event: EventSummary): string {
    if (event.allDay) return event.title
    const start = instantToZoned(event.start, timeZone)
    return `${formatClock(start.minutes)} ${event.title}`
  }
</script>

  <div class="flex h-full min-h-0 flex-1 flex-col overflow-auto bg-base-200">
  <div class="grid grid-cols-7 border-b border-base-300">
    {#each weekdays as weekday, index (index)}
      <div class="px-2 py-2 text-xs tracking-wide text-base-content/50 uppercase">{weekday}</div>
    {/each}
  </div>
  <div class="grid min-h-0 flex-1 grid-rows-[repeat(auto-fit,minmax(0,1fr))]">
    {#each weeks as week (week[0])}
      <div class="grid min-h-24 grid-cols-7 border-b border-base-300">
        {#each week as date (date)}
          {@const dayEvents = eventsOnDate(events, date, timeZone)}
          {@const inMonth = date.startsWith(monthPrefix)}
          <div
            class={`min-h-24 border-l border-base-300 p-1 ${date === today ? "bg-primary/10" : ""} ${inMonth ? "" : "opacity-40"}`}
          >
            <div class="mb-1 flex items-center justify-between">
              <button
                type="button"
                class="btn btn-ghost btn-xs"
                class:text-primary={date === today}
                onclick={() => onDay(date)}
              >
                {Number(date.slice(8))}
              </button>
              {#if !readOnly}
                <button
                  type="button"
                  class="btn btn-ghost btn-xs"
                  aria-label={`Create event on ${date}`}
                  onclick={() => createAllDay(date)}
                >
                  +
                </button>
              {/if}
            </div>
            <div class="flex flex-col gap-0.5">
              {#each dayEvents as event (`${event.id}:${event.start}`)}
                <button
                  type="button"
                  class="truncate rounded-field px-1 py-0.5 text-left text-[0.7rem]"
                  style={fill(event.calendarId)}
                  onclick={() => onSelect(event)}
                >
                  {chipLabel(event)}
                </button>
              {/each}
            </div>
          </div>
        {/each}
      </div>
    {/each}
  </div>
</div>
