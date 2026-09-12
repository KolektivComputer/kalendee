export function pad(n: number): string {
  return n.toString().padStart(2, "0")
}

export function clientTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"
  } catch {
    return "UTC"
  }
}

export function addDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split("-").map(Number)
  const date = new Date(Date.UTC(year, month - 1, day + days))
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
}

export function daysBetween(start: string, end: string): number {
  const a = Date.parse(`${start}T00:00:00Z`)
  const b = Date.parse(`${end}T00:00:00Z`)
  return Math.round((b - a) / 86_400_000)
}

function formatParts(date: Date, timeZone: string): Record<string, string> {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  })
  const out: Record<string, string> = {}
  for (const part of fmt.formatToParts(date)) {
    if (part.type !== "literal") out[part.type] = part.value
  }
  return out
}

export function instantToZoned(iso: string, timeZone: string): { date: string; minutes: number } {
  const parts = formatParts(new Date(iso), timeZone)
  let hour = Number(parts.hour)
  if (hour === 24) hour = 0
  const minute = Number(parts.minute)
  return { date: `${parts.year}-${parts.month}-${parts.day}`, minutes: hour * 60 + minute }
}

export function zonedToInstant(date: string, minutes: number, timeZone: string): string {
  const hour = Math.floor(minutes / 60)
  const minute = minutes % 60
  const wall = `${date}T${pad(hour)}:${pad(minute)}:00`
  let guess = Date.parse(`${wall}Z`)
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  })
  for (let i = 0; i < 4; i++) {
    const parts = Object.fromEntries(
      fmt
        .formatToParts(new Date(guess))
        .filter((part) => part.type !== "literal")
        .map((part) => [part.type, part.value]),
    )
    let gotHour = Number(parts.hour)
    if (gotHour === 24) gotHour = 0
    const got = `${parts.year}-${parts.month}-${parts.day}T${pad(gotHour)}:${parts.minute}:${parts.second}`
    const delta = Date.parse(`${wall}Z`) - Date.parse(`${got}Z`)
    if (delta === 0) break
    guess += delta
  }
  return new Date(guess).toISOString()
}

export function formatHour(hour: number): string {
  if (hour === 0) return "12 AM"
  if (hour === 12) return "12 PM"
  if (hour < 12) return `${hour} AM`
  return `${hour - 12} PM`
}

export function formatClock(minutes: number): string {
  const hour = Math.floor(minutes / 60) % 24
  const minute = minutes % 60
  const suffix = hour < 12 ? "AM" : "PM"
  const h = hour % 12 === 0 ? 12 : hour % 12
  return minute === 0 ? `${h} ${suffix}` : `${h}:${pad(minute)} ${suffix}`
}

const Months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

export function datesBetween(start: string, endExclusive: string): string[] {
  const dates: string[] = []
  let cursor = start
  while (cursor < endExclusive) {
    dates.push(cursor)
    cursor = addDays(cursor, 1)
  }
  return dates
}

export function formatWeekLabel(weekStart: string): string {
  const end = addDays(weekStart, 6)
  const [, sm, sd] = weekStart.split("-").map(Number)
  const [, em, ed] = end.split("-").map(Number)
  if (sm === em) return `${Months[sm - 1]} ${sd}–${ed}`
  return `${Months[sm - 1]} ${sd} – ${Months[em - 1]} ${ed}`
}

export function formatDayHeading(isoDate: string): { weekday: string; day: string } {
  const [year, month, day] = isoDate.split("-").map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  const weekday = new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: "UTC" }).format(date)
  return { weekday, day: String(day) }
}
