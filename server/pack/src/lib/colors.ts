export const CALENDAR_PALETTE = [
  "primary",
  "secondary",
  "accent",
  "info",
  "success",
  "warning",
  "error",
]

export const HOLIDAY_CALENDAR_ID = "holiday"
export const HOLIDAY_COLOR = "secondary"

function isCustomColor(color: string): boolean {
  return color.startsWith("#") || color.startsWith("oklch(") || color.startsWith("var(")
}

export function cssColor(color: string): string {
  if (isCustomColor(color)) return color
  return `var(--color-${color})`
}

export function cssContent(color: string): string {
  if (isCustomColor(color)) return "var(--color-base-content)"
  return `var(--color-${color}-content)`
}

export function eventFill(color: string): string {
  return `background:${cssColor(color)};color:${cssContent(color)}`
}

export function nextCalendarColor(existing: string[]): string {
  const used = new Set(existing)
  return CALENDAR_PALETTE.find((color) => !used.has(color)) ?? CALENDAR_PALETTE[existing.length % CALENDAR_PALETTE.length]
}

export function isHolidayEvent(event: { calendarId: string }): boolean {
  return event.calendarId === HOLIDAY_CALENDAR_ID
}
