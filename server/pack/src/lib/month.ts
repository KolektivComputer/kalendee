import type { EventSummary } from "./page-types"
import { instantToZoned } from "./time"

export function weeksOf(dates: string[]): string[][] {
  const weeks: string[][] = []
  for (let i = 0; i < dates.length; i += 7) {
    weeks.push(dates.slice(i, i + 7))
  }
  return weeks
}

export function eventsOnDate(events: EventSummary[], date: string, timeZone: string): EventSummary[] {
  return events.filter((event) => {
    const start = instantToZoned(event.start, timeZone)
    const end = instantToZoned(event.end, timeZone)
    if (event.allDay) {
      let endDate = end.date
      if (end.minutes === 0) {
        const [year, month, day] = endDate.split("-").map(Number)
        const previous = new Date(Date.UTC(year, month - 1, day - 1))
        endDate = `${previous.getUTCFullYear()}-${String(previous.getUTCMonth() + 1).padStart(2, "0")}-${String(previous.getUTCDate()).padStart(2, "0")}`
      }
      return start.date <= date && date <= endDate
    }
    return start.date === date
  })
}
