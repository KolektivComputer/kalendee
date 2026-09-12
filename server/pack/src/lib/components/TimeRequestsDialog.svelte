<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage } from "../errors"
  import type {
    CalendarRequestsIn,
    CalendarRequestsOut,
    CalendarSummary,
    RequestTimeSlotOut,
    RespondTimeSlotIn,
    TimeSlotRequestSummary,
  } from "../page-types"

  let {
    open = $bindable(false),
    calendar = null,
    onPendingCount,
  }: {
    open: boolean
    calendar: CalendarSummary | null
    onPendingCount?: (calendarId: string, count: number) => void
  } = $props()

  const getRequests = useAction<CalendarRequestsIn, CalendarRequestsOut>("kalendee.calendarRequests", {
    reload: false,
  })
  const respond = useAction<RespondTimeSlotIn, RequestTimeSlotOut>("kalendee.respondTimeSlot")

  let requests = $state<TimeSlotRequestSummary[]>([])
  let loadedFor = $state("")
  let loadError = $state("")
  let pendingId = $state("")
  let dialog = $state<HTMLDialogElement | undefined>()

  const pendingCount = $derived(requests.filter((request) => request.status === "pending").length)
  const respondError = $derived(actionMessage(respond.error))

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  $effect(() => {
    if (!open) {
      loadedFor = ""
      requests = []
      loadError = ""
      pendingId = ""
      return
    }
    if (!calendar) return
    const id = calendar.id
    if (loadedFor === id) return
    loadedFor = id
    untrack(() => {
      getRequests.reset()
      respond.reset()
      requests = []
      loadError = ""
      pendingId = ""
      void reload(id)
    })
  })

  $effect(() => {
    if (!calendar) return
    onPendingCount?.(calendar.id, pendingCount)
  })

  async function reload(calendarId: string) {
    try {
      requests = (await getRequests.mutateAsync({ calendarId })).requests
      loadError = ""
    } catch {
      requests = []
      loadError = "Could not load time requests."
    }
  }

  async function respondTo(request: TimeSlotRequestSummary, accept: boolean) {
    if (!calendar || pendingId !== "") return
    pendingId = request.id
    try {
      await respond.mutateAsync({ id: request.id, accept, message: null })
      await reload(calendar.id)
    } catch {
      // The action error state renders below the list.
    } finally {
      pendingId = ""
    }
  }

  function close() {
    open = false
    loadedFor = ""
    requests = []
    loadError = ""
    pendingId = ""
  }

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function timeText(date: Date, timeZone: string): string {
    return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZone }).format(date)
  }

  function formatSlot(request: TimeSlotRequestSummary): string {
    const timeZone = calendar?.timeZone || "UTC"
    const start = new Date(request.start)
    const day = new Intl.DateTimeFormat("en-US", {
      weekday: "short",
      month: "short",
      day: "numeric",
      timeZone,
    }).format(start)
    return `${day}, ${timeText(start, timeZone)}–${timeText(new Date(request.end), timeZone)}`
  }

  function statusLabel(status: string): string {
    if (status === "accepted") return "Accepted"
    if (status === "declined") return "Declined"
    return "Pending"
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-xl">
    <h3 class="text-lg font-bold">Time requests</h3>
    <p class="py-2 text-base-content/70">
      Requests for {calendar?.displayName ?? "this calendar"} · shown in {calendar?.timeZone ?? "the calendar's time zone"}
    </p>

    {#if loadError}
      <div role="alert" class="alert alert-error mb-2">{loadError}</div>
    {/if}

    {#if getRequests.isPending && requests.length === 0}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {:else if requests.length === 0 && !loadError}
      <p class="py-3 text-sm text-base-content/60">No time requests yet.</p>
    {:else}
      <ul class="flex flex-col">
        {#each requests as request (request.id)}
          {@const handled = request.status !== "pending"}
          <li class="flex flex-col gap-2 border-b border-base-300 py-3 last:border-b-0" class:opacity-60={handled}>
            <div class="flex items-center gap-2">
              {#if request.requesterAvatarUrl}
                <img class="h-7 w-7 shrink-0 rounded-full object-cover" src={request.requesterAvatarUrl} alt="" />
              {:else}
                <span
                  class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-base-content/20 text-[0.6rem] font-semibold leading-none"
                  aria-hidden="true"
                >
                  {initials(request.requesterName || request.requesterUsername)}
                </span>
              {/if}
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm">{request.requesterName || request.requesterUsername}</span>
                <span class="block truncate text-xs text-base-content/60">{formatSlot(request)}</span>
              </span>
              <span
                class="badge badge-sm shrink-0"
                class:badge-warning={request.status === "pending"}
                class:badge-success={request.status === "accepted"}
                class:badge-ghost={request.status === "declined"}
              >
                {statusLabel(request.status)}
              </span>
            </div>
            {#if request.message}
              <p class="whitespace-pre-line text-sm text-base-content/80">{request.message}</p>
            {/if}
            {#if !handled}
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="btn btn-primary btn-sm"
                  disabled={pendingId !== ""}
                  onclick={() => void respondTo(request, true)}
                >
                  {pendingId === request.id ? "Accepting…" : "Accept"}
                </button>
                <button
                  type="button"
                  class="btn btn-ghost btn-sm"
                  disabled={pendingId !== ""}
                  onclick={() => void respondTo(request, false)}
                >
                  {pendingId === request.id ? "Declining…" : "Decline"}
                </button>
              </div>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}

    {#if respondError}
      <p class="mt-2 text-error text-sm">{respondError}</p>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={close}>Close</button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
