export type ReminderUnit = "minutes" | "hours" | "days"

export interface ReminderRow {
  id: number
  value: number
  unit: ReminderUnit
}

export interface ReminderSelection {
  useDefaults: boolean
  offsetsSeconds: number[]
}

export const BROWSER_NOTIFICATIONS_KEY = "kalendee.browserNotifications"
export const FIRED_REMINDERS_KEY = "kalendee.firedReminders"
export const MAX_REMINDERS = 10
export const MAX_OFFSET_SECONDS = 31_536_000

const UnitSeconds: Record<ReminderUnit, number> = {
  minutes: 60,
  hours: 3_600,
  days: 86_400,
}

const LargestFirst: ReminderUnit[] = ["days", "hours", "minutes"]

let rowSequence = 0

export function nextReminderRowId(): number {
  rowSequence += 1
  return rowSequence
}

export function unitSeconds(unit: ReminderUnit): number {
  return UnitSeconds[unit]
}

export function partsToSeconds(value: number, unit: ReminderUnit): number {
  if (!Number.isFinite(value)) return 0
  return Math.round(value * UnitSeconds[unit])
}

export function offsetToParts(seconds: number): { value: number; unit: ReminderUnit } {
  if (Number.isFinite(seconds) && seconds > 0) {
    for (const unit of LargestFirst) {
      const factor = UnitSeconds[unit]
      if (seconds % factor === 0) return { value: seconds / factor, unit }
    }
  }
  return { value: Math.max(1, Math.round(seconds / 60)), unit: "minutes" }
}

export function rowsFromOffsets(offsets: number[]): ReminderRow[] {
  return offsets
    .filter((seconds) => Number.isFinite(seconds) && seconds > 0)
    .map((seconds) => {
      const parts = offsetToParts(seconds)
      return { id: nextReminderRowId(), value: parts.value, unit: parts.unit }
    })
}

export function offsetsFromRows(rows: ReminderRow[]): number[] {
  const seen = new Set<number>()
  const offsets: number[] = []
  for (const row of rows) {
    const value = Number(row.value)
    if (!Number.isFinite(value) || value <= 0) continue
    const seconds = partsToSeconds(value, row.unit)
    if (seconds <= 0 || seconds > MAX_OFFSET_SECONDS || seen.has(seconds)) continue
    seen.add(seconds)
    offsets.push(seconds)
  }
  return offsets.sort((a, b) => a - b)
}

export function reminderRowsError(rows: ReminderRow[]): string {
  if (rows.length > MAX_REMINDERS) return `Use at most ${MAX_REMINDERS} reminders.`
  for (const row of rows) {
    const value = Number(row.value)
    if (!Number.isFinite(value) || value <= 0) {
      return "Reminder amounts must be greater than zero."
    }
    if (partsToSeconds(value, row.unit) > MAX_OFFSET_SECONDS) {
      return "Reminders can be at most one year before the event."
    }
  }
  return ""
}

export function formatOffset(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return "when it starts"
  const { value, unit } = offsetToParts(seconds)
  const label = value === 1 ? unit.slice(0, -1) : unit
  return `${value} ${label} before`
}

export function formatReminderWhen(start: string, allDay: boolean, timeZone: string): string {
  const date = new Date(start)
  if (Number.isNaN(date.getTime())) return ""
  const dateLabel = new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "short",
    month: "short",
    day: "numeric",
  }).format(date)
  if (allDay) return `all day ${dateLabel}`
  const timeLabel = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour: "numeric",
    minute: "2-digit",
  }).format(date)
  return `${dateLabel} · ${timeLabel}`
}
