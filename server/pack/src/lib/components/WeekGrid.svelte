<script lang="ts">
  import { ContextMenu } from "bits-ui"
  import Calendar from "@lucide/svelte/icons/calendar"
  import CalendarSync from "@lucide/svelte/icons/calendar-sync"
  import ChevronRight from "@lucide/svelte/icons/chevron-right"
  import PenLine from "@lucide/svelte/icons/pen-line"
  import Plus from "@lucide/svelte/icons/plus"
  import Trash from "@lucide/svelte/icons/trash"
  import { eventFill, HOLIDAY_COLOR, isHolidayEvent } from "../colors"
  import { router } from "@kolektiv/keel-svelte"
  import type { CalendarSummary, EventSummary } from "../page-types"
  import { settingsHref } from "../settings-ui.svelte"
  import {
    DAY_MINUTES,
    HOUR_HEIGHT,
    SNAP_MINUTES,
    dayColumnFromX,
    layoutAllDay,
    layoutTimed,
    snapMinutes,
    snapStartMinutes,
    type TimedBlock,
  } from "../week"
  import {
    addDays,
    formatClock,
    formatDayHeading,
    formatHour,
    instantToZoned,
    zonedToInstant,
  } from "../time"

  let {
    dates,
    timeZone,
    calendars,
    events,
    readOnly = false,
    moveTargets,
    onDraft,
    onMove,
    onSelect,
    onDelete,
    onMoveToCalendar,
  }: {
    dates: string[]
    timeZone: string
    calendars: CalendarSummary[]
    events: EventSummary[]
    readOnly?: boolean
    moveTargets?: CalendarSummary[]
    onDraft: (draft: { start: string; end: string; allDay: boolean }) => void
    onMove: (move: { id: string; start: string; end: string; etag: string }) => Promise<unknown> | void
    onSelect: (event: EventSummary) => void
    onDelete: (event: EventSummary) => void
    onMoveToCalendar?: (move: { event: EventSummary; calendarId: string }) => void
  } = $props()

  let nowIso = $state(new Date().toISOString())

  $effect(() => {
    const timer = setInterval(() => {
      nowIso = new Date().toISOString()
    }, 30_000)
    return () => clearInterval(timer)
  })

  const columns = $derived(Math.max(1, dates.length))
  const startDate = $derived(dates[0] ?? "")
  const colorById = $derived(Object.fromEntries(calendars.map((calendar) => [calendar.id, calendar.color])))
  const writableCalendarIds = $derived(
    new Set(
      calendars
        .filter((calendar) => calendar.permission === "owner" || calendar.permission === "write")
        .map((calendar) => calendar.id),
    ),
  )
  const pushCalendarIds = $derived(
    new Set(
      calendars
        .filter((calendar) => calendar.syncDirection === "push" || calendar.syncDirection === "both")
        .map((calendar) => calendar.id),
    ),
  )
  const timed = $derived(layoutTimed(events, startDate, columns, timeZone))
  const allDay = $derived(layoutAllDay(events, startDate, columns, timeZone))
  const hours = Array.from({ length: 24 }, (_, hour) => hour)
  const nowParts = $derived(instantToZoned(nowIso, timeZone))
  const today = $derived(nowParts.date)
  const nowDay = $derived(dates.indexOf(nowParts.date))
  const columnsTemplate = $derived(`var(--gutter) repeat(${columns}, minmax(0, 1fr))`)
  const dayWidth = $derived(`(100% - var(--gutter)) / ${columns}`)

  let timedEl = $state<HTMLElement | undefined>()
  let scrolled = $state(false)
  const DRAG_THRESHOLD_PX = 4
  const COLUMN_HYSTERESIS_PX = 12
  type MoveDrag = {
    kind: "move"
    pointerId: number
    event: EventSummary
    durationMs: number
    durationMin: number
    grabOffsetMin: number
    originX: number
    originY: number
    active: boolean
    dayIndex: number
    startMin: number
  }
  let drag = $state<
    | { kind: "create"; pointerId: number; day: number; origin: number; current: number }
    | MoveDrag
    | { kind: "resize"; pointerId: number; event: EventSummary; liveEnd: string }
    | null
  >(null)
  let pendingMove = $state<{
    token: number
    eventId: string
    dayIndex: number
    startMin: number
    endMin: number
  } | null>(null)
  let moveToken = 0
  const preview = $derived.by(() => {
    if (drag?.kind === "move" && drag.active) {
      return {
        event: drag.event,
        dayIndex: drag.dayIndex,
        startMin: drag.startMin,
        endMin: drag.startMin + drag.durationMin,
      }
    }
    if (pendingMove) {
      const event = events.find((item) => item.id === pendingMove.eventId)
      if (event) {
        return { event, dayIndex: pendingMove.dayIndex, startMin: pendingMove.startMin, endMin: pendingMove.endMin }
      }
    }
    return null
  })

  $effect(() => {
    if (scrolled || !timedEl) return
    const hour = Math.max(0, Math.floor(nowParts.minutes / 60) - 1)
    timedEl.scrollTop = hour * HOUR_HEIGHT
    scrolled = true
  })

  $effect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") drag = null
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  })

  let contextMenuOpen = $state(false)
  let menu = $state<
    | { kind: "event"; event: EventSummary }
    | { kind: "holiday"; event: EventSummary }
    | { kind: "allday"; day: number }
    | { kind: "timed"; day: number; minutes: number }
    | { kind: "none" }
  >({ kind: "none" })

  function closeMenu() {
    contextMenuOpen = false
    menu = { kind: "none" }
  }

  const moveDestinations = $derived.by(() => {
    if (menu.kind !== "event") return []
    const event = menu.event
    if (readOnly || event.externalCalendarId || !writableCalendarIds.has(event.calendarId)) return []
    return (moveTargets ?? calendars).filter(
      (calendar) =>
        calendar.id !== event.calendarId &&
        (calendar.permission === "owner" || calendar.permission === "write"),
    )
  })

  function fill(calendarId: string): string {
    if (calendarId === "holiday") return eventFill(HOLIDAY_COLOR)
    return eventFill(colorById[calendarId] ?? "primary")
  }

  function rawMinutesFromClientY(clientY: number): number {
    if (!timedEl) return 0
    const top = timedEl.getBoundingClientRect().top
    const y = clientY - top + timedEl.scrollTop
    return (y / HOUR_HEIGHT) * 60
  }

  function minutesFromClientY(clientY: number): number {
    return snapMinutes(rawMinutesFromClientY(clientY))
  }

  function dayFromClientX(clientX: number, previous = -1, hysteresisPx = 0): number {
    if (!timedEl) return 0
    const gutter = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--gutter")) || 56
    const left = timedEl.getBoundingClientRect().left + gutter
    const dayWidthPx = Math.max(0, timedEl.clientWidth - gutter) / columns
    return dayColumnFromX(clientX - left, dayWidthPx, columns, previous, hysteresisPx)
  }

  function at(dayIndex: number, minutes: number): string {
    if (minutes >= DAY_MINUTES) return zonedToInstant(addDays(dates[dayIndex], 1), minutes - DAY_MINUTES, timeZone)
    if (minutes < 0) return zonedToInstant(addDays(dates[dayIndex], -1), DAY_MINUTES + minutes, timeZone)
    return zonedToInstant(dates[dayIndex], minutes, timeZone)
  }

  function canEditEventTimes(item: EventSummary): boolean {
    if (readOnly || isHolidayEvent(item) || item.recurrence) return false
    if (item.externalCalendarId != null) {
      return pushCalendarIds.has(item.calendarId)
    }
    return writableCalendarIds.has(item.calendarId)
  }

  function onTimedPointerDown(event: PointerEvent) {
    if (readOnly || event.button !== 0 || !event.isPrimary || drag) return
    const target = event.target as HTMLElement
    if (target.closest("[data-event-id]") || target.closest("[data-resize]")) return
    const day = dayFromClientX(event.clientX)
    const origin = minutesFromClientY(event.clientY)
    drag = { kind: "create", pointerId: event.pointerId, day, origin, current: origin + 30 }
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }

  function onEventPointerDown(event: PointerEvent, block: TimedBlock) {
    if (event.button !== 0 || !event.isPrimary || drag) return
    event.stopPropagation()
    const item = block.event
    if (!canEditEventTimes(item)) {
      onSelect(item)
      return
    }
    if (pendingMove?.eventId === item.id) pendingMove = null
    const durationMs = Date.parse(item.end) - Date.parse(item.start)
    const durationMin = Math.max(SNAP_MINUTES, Math.round(durationMs / 60_000))
    const grabbedMin = rawMinutesFromClientY(event.clientY)
    const grabOffsetMin = Math.max(0, Math.min(durationMin, grabbedMin - block.startMin))
    drag = {
      kind: "move",
      pointerId: event.pointerId,
      event: item,
      durationMs,
      durationMin,
      grabOffsetMin,
      originX: event.clientX,
      originY: event.clientY,
      active: false,
      dayIndex: block.dayIndex,
      startMin: block.startMin,
    }
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }

  function onResizePointerDown(event: PointerEvent, item: EventSummary) {
    if (readOnly || event.button !== 0 || !event.isPrimary || drag) return
    if (!canEditEventTimes(item)) return
    event.stopPropagation()
    drag = { kind: "resize", pointerId: event.pointerId, event: item, liveEnd: item.end }
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }

  function updateMoveDrag(event: PointerEvent, current: MoveDrag): void {
    let next = current
    if (!next.active) {
      const distance = Math.hypot(event.clientX - next.originX, event.clientY - next.originY)
      if (distance < DRAG_THRESHOLD_PX) return
      next = { ...next, active: true }
    }
    const dayIndex = dayFromClientX(event.clientX, next.dayIndex, COLUMN_HYSTERESIS_PX)
    const rawStart = rawMinutesFromClientY(event.clientY) - next.grabOffsetMin
    const snapped = snapStartMinutes(rawStart, SNAP_MINUTES)
    const maxStart = Math.max(0, DAY_MINUTES - next.durationMin)
    const startMin = Math.max(0, Math.min(maxStart, snapped))
    drag = { ...next, dayIndex, startMin }
  }

  function onPointerMove(event: PointerEvent) {
    const current = drag
    if (!current || event.pointerId !== current.pointerId) return
    if (current.kind === "create") {
      drag = { ...current, current: minutesFromClientY(event.clientY), day: dayFromClientX(event.clientX) }
      return
    }
    if (current.kind === "move") {
      updateMoveDrag(event, current)
      return
    }
    const start = instantToZoned(current.event.start, timeZone)
    const day = Math.max(0, dates.indexOf(start.date))
    const endMin = Math.max(start.minutes + SNAP_MINUTES, minutesFromClientY(event.clientY))
    drag = { ...current, liveEnd: at(day, endMin) }
  }

  function onPointerCancel(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) return
    drag = null
  }

  function onPointerUp(event: PointerEvent) {
    const current = drag
    if (!current || event.pointerId !== current.pointerId) return
    drag = null
    if (readOnly) return
    if (current.kind === "create") {
      const startMin = Math.min(current.origin, current.current)
      const endMin = Math.max(current.origin, current.current, startMin + 30)
      onDraft({ start: at(current.day, startMin), end: at(current.day, endMin), allDay: false })
      return
    }
    if (current.kind === "move") {
      if (!current.active) {
        onSelect(current.event)
        return
      }
      const start = at(current.dayIndex, current.startMin)
      const end = new Date(Date.parse(start) + current.durationMs).toISOString()
      if (Date.parse(start) === Date.parse(current.event.start) && Date.parse(end) === Date.parse(current.event.end)) {
        return
      }
      const token = ++moveToken
      pendingMove = {
        token,
        eventId: current.event.id,
        dayIndex: current.dayIndex,
        startMin: current.startMin,
        endMin: current.startMin + current.durationMin,
      }
      const clearPending = () => {
        if (pendingMove?.token === token) pendingMove = null
      }
      void Promise.resolve(
        onMove({
          id: current.event.id,
          start,
          end,
          etag: current.event.etag,
        }),
      ).then(clearPending, clearPending)
      return
    }
    if (current.liveEnd !== current.event.end) {
      void Promise.resolve(
        onMove({
          id: current.event.id,
          start: current.event.start,
          end: current.liveEnd,
          etag: current.event.etag,
        }),
      ).catch(() => undefined)
    }
  }

  function eventStyle(block: {
    dayIndex: number
    startMin: number
    endMin: number
    col: number
    cols: number
    event: EventSummary
  }) {
    const live = liveOverride(block.event)
    const startMin = live?.startMin ?? block.startMin
    const endMin = live?.endMin ?? block.endMin
    const left = `calc(var(--gutter) + ${block.dayIndex} * ${dayWidth} + ${block.col} * (${dayWidth}) / ${block.cols} + 2px)`
    const width = `calc((${dayWidth}) / ${block.cols} - 4px)`
    return `top:${(startMin / 60) * HOUR_HEIGHT}px;height:${Math.max(16, ((endMin - startMin) / 60) * HOUR_HEIGHT)}px;left:${left};width:${width};${fill(block.event.calendarId)};`
  }

  function liveOverride(event: EventSummary): { startMin: number; endMin: number } | null {
    if (drag?.kind === "resize" && drag.event.id === event.id) {
      const start = instantToZoned(event.start, timeZone)
      const end = instantToZoned(drag.liveEnd, timeZone)
      return { startMin: start.minutes, endMin: Math.max(start.minutes + SNAP_MINUTES, end.minutes) }
    }
    return null
  }

  function onGridContextMenu(event: MouseEvent): boolean {
    const target = event.target as HTMLElement
    const eventEl = target.closest("[data-event-id]")
    if (eventEl) {
      const id = eventEl.getAttribute("data-event-id")
      const start = eventEl.getAttribute("data-event-start")
      const found =
        events.find((item) => item.id === id && item.start === start) ?? events.find((item) => item.id === id)
      if (found) {
        if (isHolidayEvent(found)) {
          menu = { kind: "holiday", event: found }
          return true
        }
        if (!readOnly && !found.externalCalendarId && writableCalendarIds.has(found.calendarId)) {
          menu = { kind: "event", event: found }
          return true
        }
        menu = { kind: "none" }
        return false
      }
    }
    if (readOnly) {
      menu = { kind: "none" }
      return false
    }
    const allDayCell = target.closest("[data-day]")
    if (allDayCell && target.closest("[data-all-day]")) {
      menu = { kind: "allday", day: Number(allDayCell.getAttribute("data-day") ?? "0") }
      return true
    }
    if (target.closest("[data-timed]")) {
      menu = {
        kind: "timed",
        day: dayFromClientX(event.clientX),
        minutes: minutesFromClientY(event.clientY),
      }
      return true
    }
    menu = { kind: "none" }
    return false
  }

  function draftStyle(): string | null {
    if (drag?.kind !== "create") return null
    const startMin = Math.min(drag.origin, drag.current)
    const endMin = Math.max(drag.origin, drag.current, startMin + 30)
    return `top:${(startMin / 60) * HOUR_HEIGHT}px;height:${((endMin - startMin) / 60) * HOUR_HEIGHT}px;left:calc(var(--gutter) + ${drag.day} * ${dayWidth} + 2px);width:calc(${dayWidth} - 4px);`
  }

  function ghostStyle(block: { dayIndex: number; startMin: number; endMin: number }): string {
    const left = `calc(var(--gutter) + ${block.dayIndex} * ${dayWidth} + 2px)`
    const width = `calc(${dayWidth} - 4px)`
    return `top:${(block.startMin / 60) * HOUR_HEIGHT}px;height:${Math.max(16, ((block.endMin - block.startMin) / 60) * HOUR_HEIGHT)}px;left:${left};width:${width};`
  }

  function createAllDay(date: string) {
    if (readOnly) return
    onDraft({
      start: zonedToInstant(date, 0, timeZone),
      end: zonedToInstant(addDays(date, 1), 0, timeZone),
      allDay: true,
    })
  }
</script>

<ContextMenu.Root
  bind:open={contextMenuOpen}
  onOpenChange={(open) => {
    if (!open) menu = { kind: "none" }
  }}
>
  <ContextMenu.Trigger>
    {#snippet child({ props })}
      <div
        class="flex h-full min-h-0 flex-1 flex-col overflow-hidden"
        {...props}
        oncontextmenu={(event: MouseEvent) => {
          if (!onGridContextMenu(event)) {
            event.preventDefault()
            return
          }
          props.oncontextmenu?.(event)
        }}
      >
        <div class="grid shrink-0 border-b border-base-300" style={`grid-template-columns:${columnsTemplate}`}>
          <div></div>
          {#each dates as date (date)}
            {@const heading = formatDayHeading(date)}
            <div class={`border-l border-base-300 px-2 py-2 ${date === today ? "bg-primary/10" : ""}`}>
              <small class="block text-xs tracking-wide text-base-content/50 uppercase">{heading.weekday}</small>
              <strong class="text-lg" class:text-primary={date === today}>{heading.day}</strong>
            </div>
          {/each}
        </div>

        <div
          class="relative grid min-h-10 shrink-0 border-b border-base-300"
          style={`grid-template-columns:${columnsTemplate}`}
          data-all-day
        >
          <div class="px-1 pt-2 text-right text-xs text-base-content/50">All day</div>
          {#each dates as date, index (date)}
            <div
              class={`border-l border-base-300 ${date === today ? "bg-primary/10" : ""}`}
              data-day={index}
              role="button"
              tabindex="0"
              aria-label={`Create all-day event on ${date}`}
              onclick={() => createAllDay(date)}
              onkeydown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault()
                  createAllDay(date)
                }
              }}
            ></div>
          {/each}
          {#each allDay as block (`${block.event.id}:${block.event.start}`)}
            <div
              class="absolute top-1.5 z-1 cursor-pointer overflow-hidden rounded-field px-2 py-0.5 text-xs"
              data-event-id={block.event.id}
              data-event-start={block.event.start}
              role="button"
              tabindex="0"
              style={`left:calc(var(--gutter) + ${block.startDay} * ${dayWidth} + 4px);width:calc(${dayWidth} * ${block.span} - 8px);${fill(block.event.calendarId)};`}
              onclick={() => onSelect(block.event)}
              onkeydown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault()
                  onSelect(block.event)
                }
              }}
            >
              {block.event.title}
            </div>
          {/each}
        </div>

        <div
          class="min-h-64 flex-1 overflow-auto select-none"
          class:touch-none={drag !== null}
          role="grid"
          tabindex="0"
          aria-label={columns === 1 ? "Day" : "Week"}
          data-timed
          bind:this={timedEl}
          onpointerdown={onTimedPointerDown}
          onpointermove={onPointerMove}
          onpointerup={onPointerUp}
          onpointercancel={onPointerCancel}
        >
          <div
            class="relative grid"
            style={`grid-template-columns:${columnsTemplate};height:calc(24 * var(--hour-height))`}
          >
            <div class="relative">
              {#each hours as hour}
                <div
                  class="h-[var(--hour-height)] pr-1.5 text-right text-[0.68rem] text-base-content/50"
                  style="transform:translateY(-0.45em)"
                >
                  {hour === 0 ? "" : formatHour(hour)}
                </div>
              {/each}
            </div>
            {#each dates as date (date)}
              <div class={`border-l border-base-300 ${date === today ? "bg-primary/10" : ""}`}></div>
            {/each}
            <div
              class="hour-lines pointer-events-none absolute top-0 right-0 z-1 h-full"
              style="left:var(--gutter)"
              aria-hidden="true"
            ></div>
            {#each timed as block (`${block.event.id}:${block.event.start}:${block.dayIndex}`)}
              <div
                class="absolute z-2 cursor-grab overflow-hidden rounded-field px-1.5 py-1 text-xs leading-tight active:cursor-grabbing"
                class:opacity-40={preview?.event.id === block.event.id}
                data-event-id={block.event.id}
                data-event-start={block.event.start}
                style={eventStyle(block)}
                role="button"
                tabindex="0"
                onpointerdown={(event) => onEventPointerDown(event, block)}
                onkeydown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault()
                    onSelect(block.event)
                  }
                }}
              >
                <strong class="block font-semibold">{block.event.title}</strong>
                <span class="text-[0.68rem] opacity-75">{formatClock(block.startMin)}</span>
                {#if canEditEventTimes(block.event)}
                  <div
                    class="absolute right-0 bottom-0 left-0 h-2 cursor-ns-resize"
                    data-resize
                    role="separator"
                    aria-label="Resize event"
                    onpointerdown={(event) => onResizePointerDown(event, block.event)}
                  ></div>
                {/if}
              </div>
            {/each}
            {#if preview}
              <div
                class="pointer-events-none absolute z-3 overflow-hidden rounded-field border border-dashed px-1.5 py-1 text-xs leading-tight opacity-90 shadow-md transition-[top,left] duration-75 ease-out"
                style={`${ghostStyle(preview)}${fill(preview.event.calendarId)}`}
                aria-hidden="true"
              >
                <strong class="block font-semibold">{preview.event.title}</strong>
                <span class="text-[0.68rem] opacity-75">
                  {formatClock(preview.startMin)}–{formatClock(preview.endMin)}
                </span>
              </div>
            {/if}
            {#if drag?.kind === "create"}
              {@const style = draftStyle()}
              {#if style}
                <div
                  class="pointer-events-none absolute z-3 rounded-field border border-dashed border-primary bg-primary/40"
                  {style}
                ></div>
              {/if}
            {/if}
            {#if nowDay >= 0}
              <div
                class="now-line pointer-events-none absolute z-4 h-0.5 bg-error"
                data-now-date={nowParts.date}
                data-now-minutes={nowParts.minutes}
                style={`left:calc(var(--gutter) + ${nowDay} * ${dayWidth});width:calc(${dayWidth});top:${(nowParts.minutes / 60) * HOUR_HEIGHT}px;`}
              ></div>
            {/if}
          </div>
        </div>
      </div>
    {/snippet}
  </ContextMenu.Trigger>
  <ContextMenu.Portal>
    <ContextMenu.Content class="z-60" onCloseAutoFocus={(event) => event.preventDefault()}>
      <ul class="menu menu-sm bg-base-100 rounded-box border-base-300 min-w-44 border p-1 shadow-lg">
        {#if menu.kind === "event" && !readOnly}
          <li>
            <ContextMenu.Item
              class="rounded-field data-[highlighted]:bg-base-content/10"
              onSelect={() => {
                if (menu.kind !== "event") return
                const selected = menu.event
                closeMenu()
                onSelect(selected)
              }}
            >
              <PenLine class="h-4 w-4" />
              Edit
            </ContextMenu.Item>
          </li>
          <li>
            <ContextMenu.Item
              class="rounded-field text-error data-[highlighted]:bg-error/10"
              onSelect={() => {
                if (menu.kind !== "event") return
                const target = menu.event
                closeMenu()
                onDelete(target)
              }}
            >
              <Trash class="h-4 w-4" />
              Delete
            </ContextMenu.Item>
          </li>
          {#if moveDestinations.length > 0}
            <li>
              <ContextMenu.Sub>
                <ContextMenu.SubTrigger class="rounded-field data-[highlighted]:bg-base-content/10">
                  <CalendarSync class="h-4 w-4" />
                  {menu.kind === "event" && menu.event.recurrence
                    ? "Move this and following"
                    : "Move to calendar"}
                  <ChevronRight class="ml-auto h-4 w-4" />
                </ContextMenu.SubTrigger>
                <ContextMenu.SubContent class="z-60">
                  <ul class="menu menu-sm bg-base-100 rounded-box border-base-300 min-w-44 border p-1 shadow-lg">
                    {#each moveDestinations as calendar (calendar.id)}
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => {
                            if (menu.kind !== "event") return
                            const target = menu.event
                            closeMenu()
                            onMoveToCalendar?.({ event: target, calendarId: calendar.id })
                          }}
                        >
                          {calendar.displayName}
                        </ContextMenu.Item>
                      </li>
                    {/each}
                  </ul>
                </ContextMenu.SubContent>
              </ContextMenu.Sub>
            </li>
          {/if}
        {:else if menu.kind === "holiday"}
          <li>
            <ContextMenu.Item
              class="rounded-field data-[highlighted]:bg-base-content/10"
              onSelect={() => {
                closeMenu()
                void router.visit(settingsHref("holidays"))
              }}
            >
              <Calendar class="h-4 w-4" />
              Manage holidays
            </ContextMenu.Item>
          </li>
        {:else if menu.kind === "allday"}
          <li>
            <ContextMenu.Item
              class="rounded-field data-[highlighted]:bg-base-content/10"
              onSelect={() => {
                if (menu.kind !== "allday") return
                const day = menu.day
                closeMenu()
                createAllDay(dates[day])
              }}
            >
              <Plus class="h-4 w-4" />
              New all-day event
            </ContextMenu.Item>
          </li>
        {:else if menu.kind === "timed"}
          <li>
            <ContextMenu.Item
              class="rounded-field data-[highlighted]:bg-base-content/10"
              onSelect={() => {
                if (menu.kind !== "timed") return
                const { day, minutes } = menu
                closeMenu()
                onDraft({
                  start: at(day, minutes),
                  end: at(day, minutes + 30),
                  allDay: false,
                })
              }}
            >
              <Plus class="h-4 w-4" />
              New event
            </ContextMenu.Item>
          </li>
        {/if}
      </ul>
    </ContextMenu.Content>
  </ContextMenu.Portal>
</ContextMenu.Root>
