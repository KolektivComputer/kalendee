import type { EventSummary } from "./page-types"
import { daysBetween, instantToZoned } from "./time"

export const HOUR_HEIGHT = 48
export const SNAP_MINUTES = 15
export const DAY_MINUTES = 24 * 60

export type TimedBlock = {
  event: EventSummary
  dayIndex: number
  startMin: number
  endMin: number
  col: number
  cols: number
}

export type AllDayBlock = {
  event: EventSummary
  startDay: number
  span: number
}

export function snapMinutes(value: number): number {
  return snapStartMinutes(value, SNAP_MINUTES)
}

/**
 * Snap a raw minute offset (from the start of the visible day) to the nearest
 * `step` and clamp it inside a single day so a pointer that leaves the grid
 * cannot produce out-of-range times.
 */
export function snapStartMinutes(raw: number, step: number): number {
  const safeStep = step > 0 ? step : SNAP_MINUTES
  const snapped = Math.round(raw / safeStep) * safeStep
  return Math.max(0, Math.min(DAY_MINUTES - safeStep, snapped))
}

/**
 * Resolve a horizontal position (pixels, relative to the first day column) to
 * a day column index.
 *
 * When `previous` is valid the selection sticks to it until the pointer moves
 * more than `hysteresisPx` past the shared edge, which stops columns from
 * flickering at boundaries. A jump across more than one column switches
 * immediately. Without a previous column, exact edge positions fall through
 * `Math.floor` and resolve to the later column.
 */
export function dayColumnFromX(
  x: number,
  dayWidth: number,
  columns: number,
  previous: number = -1,
  hysteresisPx: number = 0,
): number {
  if (!(dayWidth > 0) || columns <= 0) return 0
  const candidate = Math.max(0, Math.min(columns - 1, Math.floor(x / dayWidth)))
  if (hysteresisPx <= 0 || previous < 0 || previous >= columns || candidate === previous) return candidate
  if (Math.abs(candidate - previous) > 1) return candidate
  const left = previous * dayWidth
  const right = left + dayWidth
  if (candidate > previous) return x < right + hysteresisPx ? previous : candidate
  return x > left - hysteresisPx ? previous : candidate
}

export function minutesFromStart(iso: string, startDate: string, timeZone: string): number {
  const zoned = instantToZoned(iso, timeZone)
  return daysBetween(startDate, zoned.date) * DAY_MINUTES + zoned.minutes
}

export function layoutTimed(
  events: EventSummary[],
  startDate: string,
  dayCount: number,
  timeZone: string,
): TimedBlock[] {
  const days: TimedBlock[][] = Array.from({ length: dayCount }, () => [])
  for (const event of events) {
    if (event.allDay) continue
    for (const segment of timedSegments(event, startDate, dayCount, timeZone)) {
      days[segment.dayIndex].push({ ...segment, event, col: 0, cols: 1 })
    }
  }
  for (const day of days) packDay(day)
  return days.flat()
}

export function layoutAllDay(
  events: EventSummary[],
  startDate: string,
  dayCount: number,
  timeZone: string,
): AllDayBlock[] {
  const blocks: AllDayBlock[] = []
  const lastDay = dayCount - 1
  for (const event of events) {
    if (!event.allDay) continue
    const start = instantToZoned(event.start, timeZone)
    const end = instantToZoned(event.end, timeZone)
    let startDay = daysBetween(startDate, start.date)
    let endDay = daysBetween(startDate, end.date)
    if (end.minutes === 0) endDay -= 1
    startDay = Math.max(0, startDay)
    endDay = Math.min(lastDay, endDay)
    if (endDay < startDay) continue
    blocks.push({ event, startDay, span: endDay - startDay + 1 })
  }
  return blocks
}

function timedSegments(
  event: EventSummary,
  startDate: string,
  dayCount: number,
  timeZone: string,
): Array<{ dayIndex: number; startMin: number; endMin: number }> {
  const rangeEnd = dayCount * DAY_MINUTES
  let start = minutesFromStart(event.start, startDate, timeZone)
  let end = minutesFromStart(event.end, startDate, timeZone)
  start = Math.max(0, start)
  end = Math.min(rangeEnd, end)
  if (end <= start) return []
  const segments: Array<{ dayIndex: number; startMin: number; endMin: number }> = []
  let cursor = start
  while (cursor < end) {
    const dayIndex = Math.floor(cursor / DAY_MINUTES)
    const dayEnd = (dayIndex + 1) * DAY_MINUTES
    const segmentEnd = Math.min(end, dayEnd)
    segments.push({
      dayIndex,
      startMin: cursor - dayIndex * DAY_MINUTES,
      endMin: segmentEnd - dayIndex * DAY_MINUTES,
    })
    cursor = segmentEnd
  }
  return segments
}

function packDay(items: TimedBlock[]): void {
  items.sort((a, b) => a.startMin - b.startMin || b.endMin - a.endMin)
  const colEnds: number[] = []
  for (const item of items) {
    let col = colEnds.findIndex((end) => end <= item.startMin)
    if (col === -1) {
      col = colEnds.length
      colEnds.push(item.endMin)
    } else {
      colEnds[col] = item.endMin
    }
    item.col = col
  }
  const cols = Math.max(1, colEnds.length)
  for (const item of items) item.cols = cols
}
