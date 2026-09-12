<script lang="ts">
  import X from "@lucide/svelte/icons/x"
  import type { AvailabilityWindowIn } from "../page-types"

  let {
    windows = $bindable<AvailabilityWindowIn[]>([]),
    disabled = false,
  }: {
    windows: AvailabilityWindowIn[]
    disabled?: boolean
  } = $props()

  const dayNames = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
  const shortNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
  const maxWindows = 50
  const defaultStart = 9 * 60
  const defaultEnd = 17 * 60
  const lastMinute = 23 * 60 + 59

  let validationError = $state("")

  const days = $derived(
    dayNames.map((label, weekday) => ({
      label,
      weekday,
      items: windows
        .map((window, index) => ({ window, index }))
        .filter((item) => item.window.weekday === weekday),
    })),
  )

  function minutesToTime(minutes: number): string {
    const value = Math.max(0, Math.min(minutes, lastMinute))
    const hour = Math.floor(value / 60)
    const minute = value % 60
    return `${hour.toString().padStart(2, "0")}:${minute.toString().padStart(2, "0")}`
  }

  function timeToMinutes(value: string): number | null {
    const match = /^(\d{1,2}):(\d{2})$/.exec(value)
    if (!match) return null
    const hour = Number(match[1])
    const minute = Number(match[2])
    if (hour > 23 || minute > 59) return null
    return hour * 60 + minute
  }

  function normalize(items: AvailabilityWindowIn[]): AvailabilityWindowIn[] {
    return [...items].sort(
      (a, b) => a.weekday - b.weekday || a.startMinute - b.startMinute || a.endMinute - b.endMinute,
    )
  }

  function setTime(index: number, weekday: number, edge: "startMinute" | "endMinute", value: string) {
    const minutes = timeToMinutes(value)
    if (minutes === null) return
    const current = windows[index]
    if (!current) return
    const next = { ...current, [edge]: minutes }
    if (next.startMinute >= next.endMinute) {
      validationError = `${shortNames[weekday]}: start time must be before the end time.`
      return
    }
    const overlap = windows.some(
      (window, other) =>
        other !== index &&
        window.weekday === weekday &&
        window.startMinute < next.endMinute &&
        window.endMinute > next.startMinute,
    )
    if (overlap) {
      validationError = `${shortNames[weekday]}: office hours must not overlap.`
      return
    }
    validationError = ""
    windows = normalize(windows.map((window, other) => (other === index ? next : window)))
  }

  function addWindow(weekday: number) {
    if (windows.length >= maxWindows) {
      validationError = `Up to ${maxWindows} windows are allowed.`
      return
    }
    const lastEnd = windows
      .filter((window) => window.weekday === weekday)
      .reduce((max, window) => Math.max(max, window.endMinute), 0)
    let start = lastEnd > 0 ? lastEnd : defaultStart
    let end = start + 60
    if (end > lastMinute) {
      start = defaultStart
      end = defaultEnd
    }
    validationError = ""
    windows = normalize([...windows, { weekday, startMinute: start, endMinute: end }])
  }

  function removeWindow(index: number) {
    validationError = ""
    windows = normalize(windows.filter((window, other) => other !== index))
  }
</script>

<div class="flex flex-col gap-2">
  <div class="flex items-center justify-between gap-2">
    <span class="text-sm font-semibold">Office hours</span>
    <span class="text-xs text-base-content/60">{windows.length}/{maxWindows} windows</span>
  </div>
  <p class="text-xs text-base-content/60">
    Times use the calendar's time zone. Requests can only land inside these windows.
  </p>

  <div class="flex flex-col gap-2">
    {#each days as day (day.weekday)}
      <div class="rounded-box border border-base-300 p-2">
        <div class="flex items-center justify-between gap-2">
          <span class="text-sm font-medium">{day.label}</span>
          <button
            type="button"
            class="btn btn-ghost btn-xs"
            disabled={disabled || windows.length >= maxWindows}
            onclick={() => addWindow(day.weekday)}
          >
            Add hours
          </button>
        </div>
        {#if day.items.length === 0}
          <p class="mt-1 text-xs text-base-content/50">Unavailable</p>
        {:else}
          <ul class="mt-1 flex flex-col gap-1">
            {#each day.items as item (item.index)}
              <li class="flex items-center gap-2">
                <input
                  class="input input-sm w-28"
                  type="time"
                  step="900"
                  value={minutesToTime(item.window.startMinute)}
                  disabled={disabled}
                  aria-label={`${day.label} start time`}
                  onchange={(event) => setTime(item.index, day.weekday, "startMinute", event.currentTarget.value)}
                />
                <span class="text-base-content/50" aria-hidden="true">–</span>
                <input
                  class="input input-sm w-28"
                  type="time"
                  step="900"
                  value={minutesToTime(item.window.endMinute)}
                  disabled={disabled}
                  aria-label={`${day.label} end time`}
                  onchange={(event) => setTime(item.index, day.weekday, "endMinute", event.currentTarget.value)}
                />
                <button
                  type="button"
                  class="btn btn-ghost btn-square btn-xs ml-auto"
                  disabled={disabled}
                  aria-label={`Remove ${day.label} hours`}
                  title="Remove"
                  onclick={() => removeWindow(item.index)}
                >
                  <X class="h-3.5 w-3.5" />
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    {/each}
  </div>

  {#if validationError}
    <p class="text-error text-sm">{validationError}</p>
  {/if}
</div>
