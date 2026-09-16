<script lang="ts">
  import { Link, useAction } from "@kolektiv/keel-svelte"
  import ArrowRightLeft from "@lucide/svelte/icons/arrow-right-left"
  import Bell from "@lucide/svelte/icons/bell"
  import BellOff from "@lucide/svelte/icons/bell-off"
  import CalendarPlus from "@lucide/svelte/icons/calendar-plus"
  import ChevronDown from "@lucide/svelte/icons/chevron-down"
  import ChevronRight from "@lucide/svelte/icons/chevron-right"
  import Clock from "@lucide/svelte/icons/clock"
  import Eye from "@lucide/svelte/icons/eye"
  import EyeOff from "@lucide/svelte/icons/eye-off"
  import Inbox from "@lucide/svelte/icons/inbox"
  import PenLine from "@lucide/svelte/icons/pen-line"
  import Share2 from "@lucide/svelte/icons/share-2"
  import Trash from "@lucide/svelte/icons/trash"
  import UserPlus from "@lucide/svelte/icons/user-plus"
  import X from "@lucide/svelte/icons/x"
  import { ContextMenu } from "bits-ui"
  import { onMount } from "svelte"
  import { CALENDAR_PALETTE, cssColor, nextCalendarColor } from "../colors"
  import { actionMessage, fieldError } from "../errors"
  import { organizationRoleLabel, type ViewerOrganization } from "../organizations"
  import type {
    CalendarSummary,
    CreateCalendarIn,
    FriendRequestOut,
    FriendRequestSummary,
    FriendSummary,
    FriendsOut,
    RemoveFriendIn,
    RespondFriendRequestIn,
    SearchUsersIn,
    SendFriendRequestIn,
    TeamSummary,
    TransferCalendarIn,
    UpdateCalendarIn,
    UserSearchOut,
    UserSearchResult,
  } from "../page-types"
  import { readCollapsed, writeCollapsed } from "../sidebar"
  import {
    canDropCalendar,
    hasTransferDestination,
    organizationTarget,
    personalTarget,
    rebucketCalendar,
    targetFromElementData,
    teamTarget,
    transferDestinations,
    transferInputFor,
    transferTargetKey,
    type TransferTarget,
  } from "../transfer"
  import AvailabilityDialog from "./AvailabilityDialog.svelte"
  import ShareDialog from "./ShareDialog.svelte"
  import TimeRequestsDialog from "./TimeRequestsDialog.svelte"

  let {
    calendars,
    selectedId = $bindable(""),
    timeZone,
    showHolidays,
    friends = [],
    friendRequests = [],
    organizations = [],
    teams = [],
    createPending = false,
    updatePending = false,
    deletePending = false,
    createError = null,
    updateError = null,
    hiddenPendingId = "",
    hiddenError = "",
    readOnly = false,
    onCreate,
    onUpdate,
    onDelete,
    onHidden,
    onShowHolidays,
    onUnfollow,
  }: {
    calendars: CalendarSummary[]
    selectedId: string
    timeZone: string
    showHolidays: boolean
    friends?: FriendSummary[]
    friendRequests?: FriendRequestSummary[]
    organizations?: ViewerOrganization[]
    teams?: TeamSummary[]
    createPending?: boolean
    updatePending?: boolean
    deletePending?: boolean
    createError?: unknown
    updateError?: unknown
    hiddenPendingId?: string
    hiddenError?: string
    readOnly?: boolean
    onCreate: (input: CreateCalendarIn) => Promise<unknown>
    onUpdate: (input: UpdateCalendarIn) => Promise<unknown>
    onDelete: (id: string) => Promise<unknown>
    onHidden: (id: string, hidden: boolean) => Promise<unknown>
    onShowHolidays: (show: boolean) => Promise<unknown>
    onUnfollow: (id: string) => Promise<unknown>
  } = $props()

  const acceptFriendRequest = useAction<RespondFriendRequestIn, FriendsOut>("kalendee.acceptFriendRequest")
  const declineFriendRequest = useAction<RespondFriendRequestIn, FriendsOut>("kalendee.declineFriendRequest")
  const removeFriend = useAction<RemoveFriendIn, FriendsOut>("kalendee.removeFriend")
  const sendFriendRequest = useAction<SendFriendRequestIn, FriendRequestOut>("kalendee.sendFriendRequest")
  const searchUsers = useAction<SearchUsersIn, UserSearchOut>("kalendee.searchUsers", { reload: false })
  const transferCalendar = useAction<TransferCalendarIn, CalendarSummary>("kalendee.transferCalendar")

  interface TeamGroup {
    id: string
    name: string
    calendars: CalendarSummary[]
  }

  interface OrganizationGroup {
    id: string
    slug: string | null
    name: string
    calendars: CalendarSummary[]
    teams: TeamGroup[]
    other: CalendarSummary[]
    groupedByTeam: boolean
  }

  let calendarList = $state<CalendarSummary[]>([])

  $effect(() => {
    calendarList = calendars
  })

  const personalCalendars = $derived(calendarList.filter((calendar) => !calendar.organizationId))
  const orgGroups = $derived.by(() => {
    const groups = new Map<string, OrganizationGroup>()
    for (const calendar of calendarList) {
      const organizationId = calendar.organizationId
      if (!organizationId) continue
      const existing = groups.get(organizationId)
      if (existing) {
        existing.calendars.push(calendar)
        continue
      }
      const known = organizations.find((organization) => organization.id === organizationId)
      groups.set(organizationId, {
        id: organizationId,
        slug: calendar.organizationSlug ?? known?.slug ?? null,
        name: calendar.organizationName ?? known?.displayName ?? "Organization",
        calendars: [calendar],
        teams: [],
        other: [],
        groupedByTeam: false,
      })
    }
    const result = [...groups.values()]
    if (readOnly) return result
    for (const group of result) {
      const visibleTeams = teams.filter((team) => team.organizationId === group.id)
      if (visibleTeams.length === 0) continue
      const buckets = new Map<string, TeamGroup>()
      for (const team of visibleTeams) {
        buckets.set(team.id, { id: team.id, name: team.name, calendars: [] })
      }
      group.groupedByTeam = true
      group.teams = [...buckets.values()]
      for (const calendar of group.calendars) {
        const bucket = calendar.teamId ? buckets.get(calendar.teamId) : undefined
        if (bucket) {
          bucket.calendars.push(calendar)
        } else {
          group.other.push(calendar)
        }
      }
    }
    return result
  })
  const organizationsWithoutCalendars = $derived(
    readOnly ? [] : organizations.filter((organization) => !orgGroups.some((group) => group.id === organization.id)),
  )

  let createOpen = $state(false)
  let editOpen = $state(false)
  let deleteOpen = $state(false)
  let shareOpen = $state(false)
  let friendsOpen = $state(false)
  let availabilityOpen = $state(false)
  let requestsOpen = $state(false)
  let menuCalendarId = $state("")
  let displayName = $state("")
  let description = $state("")
  let calendarTimeZone = $state("UTC")
  let color = $state(CALENDAR_PALETTE[0])
  let editing = $state<CalendarSummary | null>(null)
  let sharing = $state<CalendarSummary | null>(null)
  let availabilityCalendar = $state<CalendarSummary | null>(null)
  let requestsCalendar = $state<CalendarSummary | null>(null)
  let pendingCounts = $state<Record<string, number>>({})
  let createDialog = $state<HTMLDialogElement | undefined>()
  let editDialog = $state<HTMLDialogElement | undefined>()
  let deleteDialog = $state<HTMLDialogElement | undefined>()
  let friendDialog = $state<HTMLDialogElement | undefined>()
  let friendQuery = $state("")
  let friendResults = $state<UserSearchResult[]>([])
  let friendSearched = $state(false)
  let friendSearchError = $state("")
  let pendingRequestId = $state("")
  let pendingRemovalId = $state("")
  let collapsed = $state<Record<string, boolean>>({})

  const TRANSFER_HOLD_MS = 500
  const TRANSFER_SLOP_PX = 8
  const TRANSFER_HINT_KEY = "kalendee.transferHintShown"
  const TRANSFER_HINT_MS = 5000

  type TransferDrag = {
    pointerId: number
    calendar: CalendarSummary
    originX: number
    originY: number
    startedAt: number
    phase: "charging" | "dragging"
    row: HTMLElement
  }

  let transferDrag = $state.raw<TransferDrag | null>(null)
  let transferHover = $state("")
  let transferGhost = $state.raw({ x: 0, y: 0 })
  let transferRaf = 0
  let transferHint = $state.raw<{ x: number; y: number } | null>(null)
  let transferHintTimer: ReturnType<typeof setTimeout> | undefined
  let suppressCalendarClick = false

  onMount(() => {
    collapsed = readCollapsed()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && transferDrag) cancelTransfer()
    }
    const onScroll = () => {
      hideTransferHint()
      if (transferDrag) cancelTransfer()
    }
    window.addEventListener("keydown", onKeyDown)
    window.addEventListener("scroll", onScroll, { capture: true, passive: true })
    return () => {
      window.removeEventListener("keydown", onKeyDown)
      window.removeEventListener("scroll", onScroll, { capture: true })
      cancelTransfer()
      hideTransferHint()
    }
  })

  $effect(() => {
    const drag = transferDrag
    if (!drag || drag.phase !== "dragging") return
    const blockScroll = (event: TouchEvent) => event.preventDefault()
    drag.row.addEventListener("touchmove", blockScroll, { passive: false })
    return () => drag.row.removeEventListener("touchmove", blockScroll)
  })

  function isCollapsed(key: string): boolean {
    return collapsed[key] === true
  }

  function toggleCollapsed(key: string) {
    collapsed = { ...collapsed, [key]: !collapsed[key] }
    writeCollapsed(collapsed)
  }

  const friendsError = $derived(
    actionMessage(acceptFriendRequest.error) ||
      actionMessage(declineFriendRequest.error) ||
      actionMessage(removeFriend.error),
  )
  const friendDialogError = $derived(
    actionMessage(sendFriendRequest.error) || friendSearchError || actionMessage(searchUsers.error),
  )

  $effect(() => {
    if (!createDialog) return
    if (createOpen && !createDialog.open) createDialog.showModal()
    if (!createOpen && createDialog.open) createDialog.close()
  })
  $effect(() => {
    if (!editDialog) return
    if (editOpen && !editDialog.open) editDialog.showModal()
    if (!editOpen && editDialog.open) editDialog.close()
  })
  $effect(() => {
    if (!deleteDialog) return
    if (deleteOpen && !deleteDialog.open) deleteDialog.showModal()
    if (!deleteOpen && deleteDialog.open) deleteDialog.close()
  })
  $effect(() => {
    if (!friendDialog) return
    if (friendsOpen && !friendDialog.open) friendDialog.showModal()
    if (!friendsOpen && friendDialog.open) friendDialog.close()
  })

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function openFriends() {
    friendQuery = ""
    friendResults = []
    friendSearched = false
    friendSearchError = ""
    searchUsers.reset()
    sendFriendRequest.reset()
    friendsOpen = true
  }

  async function runSearch() {
    const query = friendQuery.trim()
    if (query === "") return
    friendSearchError = ""
    try {
      friendResults = (await searchUsers.mutateAsync({ query })).results
      friendSearched = true
    } catch {
      friendResults = []
      friendSearched = false
      friendSearchError = "Could not search users. Try again."
    }
  }

  async function addFriend(result: UserSearchResult) {
    try {
      await sendFriendRequest.mutateAsync({ username: result.username })
    } catch {
      return
    }
    await runSearch()
  }

  async function respondToRequest(request: FriendRequestSummary, accept: boolean) {
    if (pendingRequestId !== "") return
    pendingRequestId = request.id
    try {
      const action = accept ? acceptFriendRequest : declineFriendRequest
      await action.mutateAsync({ id: request.id })
    } catch {
      // The error renders below the friends list.
    } finally {
      pendingRequestId = ""
    }
  }

  async function removeFriendWithConfirm(friend: FriendSummary) {
    if (pendingRemovalId !== "") return
    if (!window.confirm(`Remove ${friend.displayName} from your friends?`)) return
    pendingRemovalId = friend.userId
    try {
      await removeFriend.mutateAsync({ userId: friend.userId })
    } catch {
      // The error renders below the friends list.
    } finally {
      pendingRemovalId = ""
    }
  }

  function openCreate() {
    displayName = ""
    description = ""
    calendarTimeZone = timeZone
    color = nextCalendarColor(calendars.map((calendar) => calendar.color))
    createOpen = true
  }

  function closeCreate() {
    createOpen = false
    displayName = ""
    description = ""
    calendarTimeZone = timeZone
    color = CALENDAR_PALETTE[0]
  }

  function openEdit(calendar: CalendarSummary) {
    menuCalendarId = ""
    editing = calendar
    displayName = calendar.displayName
    description = calendar.description ?? ""
    calendarTimeZone = calendar.timeZone
    color = calendar.color
    editOpen = true
  }

  function closeEdit() {
    editOpen = false
    editing = null
  }

  function openDelete(calendar: CalendarSummary) {
    menuCalendarId = ""
    editing = calendar
    deleteOpen = true
  }

  function closeDelete() {
    deleteOpen = false
    editing = null
  }

  function openShare(calendar: CalendarSummary) {
    menuCalendarId = ""
    sharing = calendar
    shareOpen = true
  }

  function openAvailability(calendar: CalendarSummary) {
    menuCalendarId = ""
    availabilityCalendar = calendar
    availabilityOpen = true
  }

  function openRequests(calendar: CalendarSummary) {
    menuCalendarId = ""
    requestsCalendar = calendar
    requestsOpen = true
  }

  function closeFriends() {
    friendsOpen = false
    friendQuery = ""
    friendResults = []
    friendSearched = false
    friendSearchError = ""
  }

  function setPendingCount(calendarId: string, count: number) {
    pendingCounts = { ...pendingCounts, [calendarId]: count }
  }

  function unfollow(calendar: CalendarSummary) {
    void onUnfollow(calendar.id).catch(() => undefined)
  }

  function portal(node: HTMLElement) {
    document.body.appendChild(node)
    return {
      destroy() {
        node.remove()
      },
    }
  }

  function dragTargetValid(target: TransferTarget): boolean {
    const drag = transferDrag
    return drag?.phase === "dragging" && canDropCalendar(drag.calendar, target, teams, readOnly)
  }

  function dragTargetHover(target: TransferTarget): boolean {
    return transferHover !== "" && transferHover === transferTargetKey(target) && dragTargetValid(target)
  }

  function transferTargetAt(clientX: number, clientY: number): TransferTarget | null {
    if (typeof document === "undefined") return null
    const element = document.elementFromPoint(clientX, clientY)
    const holder = element?.closest<HTMLElement>("[data-transfer-kind]") ?? null
    return holder ? targetFromElementData(holder.dataset) : null
  }

  function transferHintAlreadyShown(): boolean {
    if (typeof sessionStorage === "undefined") return false
    try {
      return sessionStorage.getItem(TRANSFER_HINT_KEY) === "1"
    } catch {
      return false
    }
  }

  function rememberTransferHint() {
    if (typeof sessionStorage === "undefined") return
    try {
      sessionStorage.setItem(TRANSFER_HINT_KEY, "1")
    } catch {
      // Storage can be unavailable; the hint may reappear in that case.
    }
  }

  function hideTransferHint() {
    if (transferHintTimer !== undefined) {
      clearTimeout(transferHintTimer)
      transferHintTimer = undefined
    }
    transferHint = null
  }

  function showTransferHint(event: PointerEvent, calendar: CalendarSummary) {
    if (transferDrag || transferHintAlreadyShown()) return
    if (!hasTransferDestination(calendar, organizations, teams, readOnly)) return
    rememberTransferHint()
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    transferHint = { x: rect.right + 8, y: rect.top + rect.height / 2 }
    if (transferHintTimer !== undefined) clearTimeout(transferHintTimer)
    transferHintTimer = setTimeout(() => {
      transferHintTimer = undefined
      transferHint = null
    }, TRANSFER_HINT_MS)
  }

  function startTransferPress(event: PointerEvent, calendar: CalendarSummary) {
    hideTransferHint()
    suppressCalendarClick = false
    if (transferDrag || transferCalendar.isPending || event.button !== 0 || !event.isPrimary) return
    if (!hasTransferDestination(calendar, organizations, teams, readOnly)) return
    const origin = event.target
    if (origin instanceof HTMLElement && origin.closest("button, a, input, select, textarea")) return
    const row = event.currentTarget as HTMLElement
    transferCalendar.reset()
    transferDrag = {
      pointerId: event.pointerId,
      calendar,
      originX: event.clientX,
      originY: event.clientY,
      startedAt: performance.now(),
      phase: "charging",
      row,
    }
    transferGhost = { x: event.clientX, y: event.clientY }
    row.style.setProperty("--charge", "0")
    row.setPointerCapture(event.pointerId)
    transferRaf = requestAnimationFrame(chargeTransfer)
  }

  function chargeTransfer(timestamp: number) {
    const drag = transferDrag
    if (!drag || drag.phase !== "charging") return
    const progress = Math.min(1, (timestamp - drag.startedAt) / TRANSFER_HOLD_MS)
    drag.row.style.setProperty("--charge", progress.toFixed(3))
    if (progress >= 1) {
      transferDrag = { ...drag, phase: "dragging" }
      transferHover = ""
      return
    }
    transferRaf = requestAnimationFrame(chargeTransfer)
  }

  function moveTransfer(event: PointerEvent) {
    const drag = transferDrag
    if (!drag || event.pointerId !== drag.pointerId) return
    if (drag.phase === "charging") {
      const distance = Math.hypot(event.clientX - drag.originX, event.clientY - drag.originY)
      if (distance > TRANSFER_SLOP_PX) cancelTransfer()
      return
    }
    transferGhost = { x: event.clientX, y: event.clientY }
    const target = transferTargetAt(event.clientX, event.clientY)
    transferHover = target && canDropCalendar(drag.calendar, target, teams, readOnly) ? transferTargetKey(target) : ""
  }

  function endTransfer(event: PointerEvent) {
    const drag = transferDrag
    if (!drag || event.pointerId !== drag.pointerId) return
    const dragging = drag.phase === "dragging"
    let target: TransferTarget | null = null
    if (dragging) {
      const candidate = transferTargetAt(event.clientX, event.clientY)
      if (candidate && canDropCalendar(drag.calendar, candidate, teams, readOnly)) target = candidate
    }
    cancelTransfer()
    if (!dragging) return
    suppressCalendarClick = true
    setTimeout(() => {
      suppressCalendarClick = false
    }, 0)
    if (target) void transferTo(drag.calendar, target)
  }

  function cancelTransfer() {
    if (transferRaf !== 0) {
      cancelAnimationFrame(transferRaf)
      transferRaf = 0
    }
    const drag = transferDrag
    transferDrag = null
    transferHover = ""
    suppressCalendarClick = false
    if (drag) {
      drag.row.style.removeProperty("--charge")
      if (drag.row.hasPointerCapture(drag.pointerId)) drag.row.releasePointerCapture(drag.pointerId)
    }
  }

  function cancelTransferFor(pointerId: number) {
    if (transferDrag?.pointerId === pointerId) cancelTransfer()
  }

  function selectCalendar(calendar: CalendarSummary) {
    if (suppressCalendarClick) {
      suppressCalendarClick = false
      return
    }
    selectedId = calendar.id
  }

  async function transferTo(calendar: CalendarSummary, target: TransferTarget) {
    if (transferCalendar.isPending) return
    transferCalendar.reset()
    const input = transferInputFor(calendar.id, target)
    try {
      const updated = await transferCalendar.mutateAsync(input)
      calendarList = calendarList.map((entry) =>
        entry.id === calendar.id ? rebucketCalendar(entry, updated, input, organizations, teams) : entry,
      )
    } catch {
      // transferCalendar.error renders below the calendar list.
    }
  }
</script>

{#snippet personAvatar(name: string, url: string | null, sizeClass: string)}
  {#if url}
    <img class={`${sizeClass} shrink-0 rounded-full object-cover`} src={url} alt="" />
  {:else}
    <span
      class={`${sizeClass} flex shrink-0 items-center justify-center rounded-full bg-base-content/20 font-semibold leading-none`}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  {/if}
{/snippet}

{#snippet calendarItem(calendar: CalendarSummary)}
  {@const canManage = !readOnly && calendar.permission === "owner"}
  {@const canUnfollow = !readOnly && calendar.permission === "follow"}
  <li>
          <ContextMenu.Root
            open={menuCalendarId === calendar.id}
            onOpenChange={(open) => {
              menuCalendarId = open ? calendar.id : ""
            }}
          >
            <ContextMenu.Trigger disabled={(!canManage && !canUnfollow) || transferDrag !== null}>
              {#snippet child({ props })}
                <div
                  {...props}
                  class="relative flex items-center gap-2"
                  class:menu-active={calendar.id === selectedId}
                  class:opacity-45={calendar.hidden}
                  class:opacity-40={transferDrag?.phase === "dragging" && transferDrag.calendar.id === calendar.id}
                  class:transfer-charge={transferDrag?.calendar.id === calendar.id}
                  class:touch-none={transferDrag?.phase === "dragging" && transferDrag.calendar.id === calendar.id}
                  class:cursor-grabbing={transferDrag?.phase === "dragging" && transferDrag.calendar.id === calendar.id}
                  onclick={() => selectCalendar(calendar)}
                  onpointerenter={(event) => showTransferHint(event, calendar)}
                  onpointerleave={hideTransferHint}
                  onpointerdown={(event) => {
                    props.onpointerdown?.(event)
                    startTransferPress(event, calendar)
                  }}
                  onpointermove={(event) => {
                    props.onpointermove?.(event)
                    moveTransfer(event)
                  }}
                  onpointerup={(event) => {
                    props.onpointerup?.(event)
                    endTransfer(event)
                  }}
                  onpointercancel={(event) => {
                    props.onpointercancel?.(event)
                    cancelTransferFor(event.pointerId)
                  }}
                  onlostpointercapture={(event) => cancelTransferFor(event.pointerId)}
                  oncontextmenu={(event) => {
                    if (transferDrag) {
                      event.preventDefault()
                      return
                    }
                    props.oncontextmenu?.(event)
                  }}
                >
                  <span class="h-2.5 w-2.5 shrink-0 rounded-full" style={`background:${cssColor(calendar.color)}`}></span>
                  <span class="min-w-0 flex-1">
                    <span class="block truncate">{calendar.displayName}</span>
                    {#if calendar.permission !== "owner"}
                      {@const sharedLabel =
                        calendar.permission === "follow"
                          ? `Following ${calendar.ownerName}`
                          : `Shared by ${calendar.ownerName}`}
                      {@const stateLabel =
                        calendar.permission === "write"
                          ? "Editable"
                          : calendar.permission === "read"
                            ? "Read-only"
                            : "Following"}
                      <span
                        class="mt-0.5 flex items-center gap-1 text-[0.68rem] opacity-60"
                        title={`${sharedLabel} · ${stateLabel}`}
                      >
                        {@render personAvatar(calendar.ownerName, calendar.ownerAvatarUrl, "h-4 w-4 text-[0.55rem]")}
                        <span class="truncate">{sharedLabel}</span>
                        <span class="shrink-0" aria-hidden="true">·</span>
                        <span class="flex shrink-0 items-center gap-0.5">
                          {#if calendar.permission === "write"}
                            <PenLine class="h-3.5 w-3.5" />
                          {:else if calendar.permission === "read"}
                            <Eye class="h-3.5 w-3.5" />
                          {:else}
                            <Bell class="h-3.5 w-3.5" />
                          {/if}
                          {stateLabel}
                        </span>
                      </span>
                    {/if}
                  </span>
                  <button
                    type="button"
                    class="btn btn-ghost btn-square btn-xs shrink-0"
                    aria-pressed={!calendar.hidden}
                    aria-label={calendar.hidden ? `Show ${calendar.displayName}` : `Hide ${calendar.displayName}`}
                    title={readOnly ? "Read-only view" : calendar.hidden ? "Show" : "Hide"}
                    disabled={readOnly || hiddenPendingId === calendar.id}
                    onclick={(event) => {
                      event.stopPropagation()
                      void onHidden(calendar.id, !calendar.hidden).catch(() => undefined)
                    }}
                  >
                    {#if hiddenPendingId === calendar.id}
                      <span class="loading loading-spinner loading-xs"></span>
                    {:else if calendar.hidden}
                      <EyeOff class="h-4 w-4" />
                    {:else}
                      <Eye class="h-4 w-4" />
                    {/if}
                  </button>
                </div>
              {/snippet}
            </ContextMenu.Trigger>
            {#if canManage || canUnfollow}
              <ContextMenu.Portal>
                <ContextMenu.Content class="z-60" onCloseAutoFocus={(event) => event.preventDefault()}>
                  <ul
                    class="menu menu-sm bg-base-100 rounded-box border-base-300 min-w-44 border p-1 shadow-lg"
                  >
                    {#if canManage}
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => openEdit(calendar)}
                        >
                          <PenLine class="h-4 w-4" />
                          Edit
                        </ContextMenu.Item>
                      </li>
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => openShare(calendar)}
                        >
                          <Share2 class="h-4 w-4" />
                          Share…
                        </ContextMenu.Item>
                      </li>
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => openAvailability(calendar)}
                        >
                          <Clock class="h-4 w-4" />
                          Office hours…
                        </ContextMenu.Item>
                      </li>
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => openRequests(calendar)}
                        >
                          <Inbox class="h-4 w-4" />
                          <span class="flex-1">Time requests…</span>
                          {#if (pendingCounts[calendar.id] ?? 0) > 0}
                            <span class="badge badge-primary badge-xs">{pendingCounts[calendar.id]}</span>
                          {/if}
                        </ContextMenu.Item>
                      </li>
                      {@const destinations = transferDestinations(calendar, organizations, teams, readOnly)}
                      {#if destinations.length > 0}
                        <li>
                          <ContextMenu.Sub>
                            <ContextMenu.SubTrigger
                              class="rounded-field data-[highlighted]:bg-base-content/10"
                              disabled={transferCalendar.isPending}
                            >
                              <ArrowRightLeft class="h-4 w-4" />
                              Move to…
                              <ChevronRight class="ml-auto h-4 w-4" />
                            </ContextMenu.SubTrigger>
                            <ContextMenu.SubContent class="z-60">
                              <ul
                                class="menu menu-sm bg-base-100 rounded-box border-base-300 min-w-44 border p-1 shadow-lg"
                              >
                                {#each destinations as destination (destination.key)}
                                  <li>
                                    <ContextMenu.Item
                                      class={`rounded-field data-[highlighted]:bg-base-content/10 ${destination.nested ? "pl-7" : ""}`}
                                      disabled={destination.disabled || transferCalendar.isPending}
                                      onSelect={() => {
                                        menuCalendarId = ""
                                        void transferTo(calendar, destination.target)
                                      }}
                                    >
                                      {destination.label}
                                    </ContextMenu.Item>
                                  </li>
                                {/each}
                              </ul>
                            </ContextMenu.SubContent>
                          </ContextMenu.Sub>
                        </li>
                      {/if}
                      <li>
                        <ContextMenu.Item
                          class="rounded-field text-error data-[highlighted]:bg-error/10"
                          onSelect={() => openDelete(calendar)}
                        >
                          <Trash class="h-4 w-4" />
                          Delete
                        </ContextMenu.Item>
                      </li>
                    {:else}
                      <li>
                        <ContextMenu.Item
                          class="rounded-field data-[highlighted]:bg-base-content/10"
                          onSelect={() => unfollow(calendar)}
                        >
                          <BellOff class="h-4 w-4" />
                          Unfollow
                        </ContextMenu.Item>
                      </li>
                    {/if}
                  </ul>
                </ContextMenu.Content>
              </ContextMenu.Portal>
            {/if}
          </ContextMenu.Root>
        </li>
{/snippet}

<div class="flex flex-col gap-3 p-3" class:select-none={transferDrag !== null}>
  <div class="flex items-center justify-between gap-2">
    <h2 class="text-xs font-semibold tracking-wide text-base-content/50 uppercase">Calendars</h2>
    {#if !readOnly}
      <button
        type="button"
        class="btn btn-ghost btn-xs btn-square tooltip tooltip-left"
        data-tip="Add Calendar"
        aria-label="Add Calendar"
        onclick={openCreate}
      >
        <CalendarPlus class="h-4 w-4" />
      </button>
    {/if}
  </div>
  {#if calendars.length === 0}
    <p class="text-sm text-base-content/60">Create a calendar to start adding events.</p>
  {:else}
    {#if personalCalendars.length > 0 || (transferDrag?.phase === "dragging" && transferDrag.calendar.organizationId !== null)}
      {@const personalDrop = personalTarget()}
      <div
        class="flex flex-col gap-1"
        data-transfer-kind="personal"
        class:transfer-target-valid={dragTargetValid(personalDrop)}
        class:transfer-target-hover={dragTargetHover(personalDrop)}
      >
        <button
          type="button"
          class="flex w-full items-center gap-1 text-left"
          aria-expanded={!isCollapsed("personal")}
          onclick={() => toggleCollapsed("personal")}
        >
          {#if isCollapsed("personal")}
            <ChevronRight class="h-3.5 w-3.5 shrink-0" />
          {:else}
            <ChevronDown class="h-3.5 w-3.5 shrink-0" />
          {/if}
          <h3 class="truncate text-xs font-medium text-base-content/60">Personal</h3>
        </button>
        {#if !isCollapsed("personal")}
          <ul class="menu w-full p-0">
            {#each personalCalendars as calendar (calendar.id)}
              {@render calendarItem(calendar)}
            {/each}
          </ul>
        {/if}
      </div>
    {/if}
    {#each orgGroups as group (group.id)}
      {@const orgKey = `org:${group.id}`}
      {@const orgDrop = organizationTarget(group.id)}
      <div
        class="flex flex-col gap-1"
        data-transfer-kind="org"
        data-transfer-org={group.id}
        class:transfer-target-valid={dragTargetValid(orgDrop)}
        class:transfer-target-hover={dragTargetHover(orgDrop)}
      >
        <div class="flex items-center justify-between gap-2">
          <button
            type="button"
            class="flex min-w-0 items-center gap-1 text-left"
            aria-expanded={!isCollapsed(orgKey)}
            onclick={() => toggleCollapsed(orgKey)}
          >
            {#if isCollapsed(orgKey)}
              <ChevronRight class="h-3.5 w-3.5 shrink-0" />
            {:else}
              <ChevronDown class="h-3.5 w-3.5 shrink-0" />
            {/if}
            <h3 class="truncate text-xs font-medium text-base-content/60" title={group.name}>{group.name}</h3>
          </button>
          {#if group.slug}
            <Link href={`/o/${group.slug}`} class="link link-hover shrink-0 text-xs">Open</Link>
          {/if}
        </div>
        {#if !isCollapsed(orgKey)}
          {#if !group.groupedByTeam}
            <ul class="menu w-full p-0">
              {#each group.calendars as calendar (calendar.id)}
                {@render calendarItem(calendar)}
              {/each}
            </ul>
          {:else}
            {#each group.teams as team (team.id)}
              {@const teamKey = `team:${team.id}`}
              {@const teamDrop = teamTarget(group.id, team.id)}
              <div
                class="flex flex-col gap-1 pl-2"
                data-transfer-kind="team"
                data-transfer-org={group.id}
                data-transfer-team={team.id}
                class:transfer-target-valid={dragTargetValid(teamDrop)}
                class:transfer-target-hover={dragTargetHover(teamDrop)}
              >
                <button
                  type="button"
                  class="flex min-w-0 items-center gap-1 text-left"
                  aria-expanded={!isCollapsed(teamKey)}
                  onclick={() => toggleCollapsed(teamKey)}
                >
                  {#if isCollapsed(teamKey)}
                    <ChevronRight class="h-3 w-3 shrink-0" />
                  {:else}
                    <ChevronDown class="h-3 w-3 shrink-0" />
                  {/if}
                  <h4 class="truncate text-[0.7rem] text-base-content/50" title={team.name}>{team.name}</h4>
                </button>
                {#if !isCollapsed(teamKey)}
                  <ul class="menu w-full p-0">
                    {#each team.calendars as calendar (calendar.id)}
                      {@render calendarItem(calendar)}
                    {/each}
                  </ul>
                {/if}
              </div>
            {/each}
            {#if group.other.length > 0}
              <div class="flex flex-col gap-1 pl-2">
                <h4 class="px-1 py-1 text-[0.7rem] text-base-content/50">Other calendars</h4>
                <ul class="menu w-full p-0">
                  {#each group.other as calendar (calendar.id)}
                    {@render calendarItem(calendar)}
                  {/each}
                </ul>
              </div>
            {/if}
          {/if}
        {/if}
      </div>
    {/each}
  {/if}

  {#if transferCalendar.isPending}
    <p class="text-xs text-base-content/60" role="status">Moving calendar…</p>
  {:else if transferCalendar.error}
    <p class="text-error text-xs" role="alert">{actionMessage(transferCalendar.error)}</p>
  {/if}

  {#if hiddenError}
    <p class="text-error text-xs" role="alert">{hiddenError}</p>
  {/if}

  {#if organizationsWithoutCalendars.length > 0}
    <div class="flex flex-col gap-1 border-t border-base-300 pt-3">
      <h2 class="text-xs font-semibold tracking-wide text-base-content/50 uppercase">Organizations</h2>
      <ul class="menu w-full p-0">
        {#each organizationsWithoutCalendars as organization (organization.id)}
          {@const orgDrop = organizationTarget(organization.id)}
          <li
            class="rounded-box"
            data-transfer-kind="org"
            data-transfer-org={organization.id}
            class:transfer-target-valid={dragTargetValid(orgDrop)}
            class:transfer-target-hover={dragTargetHover(orgDrop)}
          >
            <Link href={`/o/${organization.slug}`} class="flex items-center gap-2">
              <span
                class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-base-content/20 text-[0.55rem] font-semibold leading-none"
                aria-hidden="true"
              >
                {initials(organization.displayName)}
              </span>
              <span class="min-w-0 flex-1 truncate">{organization.displayName}</span>
              <span class="shrink-0 text-[0.68rem] opacity-60">{organizationRoleLabel(organization.role)}</span>
            </Link>
          </li>
        {/each}
      </ul>
    </div>
  {/if}

  <div class="flex flex-col gap-1 border-t border-base-300 pt-3">
    <div class="flex items-center justify-between gap-2">
      <h2 class="text-xs font-semibold tracking-wide text-base-content/50 uppercase">Friends</h2>
      <button
        type="button"
        class="btn btn-ghost btn-xs btn-square tooltip tooltip-left"
        data-tip="Add Friend"
        aria-label="Add Friend"
        onclick={openFriends}
      >
        <UserPlus class="h-4 w-4" />
      </button>
    </div>

    {#if friendRequests.length > 0}
      <ul class="flex flex-col">
        {#each friendRequests as request (request.id)}
          {@const responding = pendingRequestId === request.id}
          <li class="flex items-center gap-2 py-1.5">
            {@render personAvatar(request.user.displayName, request.user.avatarUrl, "h-6 w-6 text-[0.6rem]")}
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{request.user.displayName}</span>
              <span class="block truncate text-[0.68rem] opacity-60">{request.user.username}</span>
            </span>
            <span class="flex shrink-0 items-center gap-1">
              <button
                type="button"
                class="btn btn-ghost btn-xs"
                disabled={responding}
                onclick={() => void respondToRequest(request, false)}
              >
                Decline
              </button>
              <button
                type="button"
                class="btn btn-primary btn-xs"
                disabled={responding}
                onclick={() => void respondToRequest(request, true)}
              >
                {responding ? "…" : "Accept"}
              </button>
            </span>
          </li>
        {/each}
      </ul>
    {/if}

    {#if friends.length > 0}
      <ul class="flex flex-col">
        {#each friends as friend (friend.userId)}
          {@const removing = pendingRemovalId === friend.userId}
          <li class="flex items-center gap-2 py-1.5">
            {@render personAvatar(friend.displayName, friend.avatarUrl, "h-6 w-6 text-[0.6rem]")}
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{friend.displayName}</span>
              <span class="block truncate text-[0.68rem] opacity-60">{friend.username}</span>
            </span>
            <button
              type="button"
              class="btn btn-ghost btn-square btn-xs shrink-0"
              aria-label={`Remove ${friend.displayName}`}
              title="Remove friend"
              disabled={removing}
              onclick={() => void removeFriendWithConfirm(friend)}
            >
              {#if removing}
                <span class="loading loading-spinner loading-xs"></span>
              {:else}
                <X class="h-3.5 w-3.5" />
              {/if}
            </button>
          </li>
        {/each}
      </ul>
    {:else if friendRequests.length === 0}
      <p class="text-sm text-base-content/60">No friends yet.</p>
    {/if}

    {#if friendsError}
      <p class="text-error text-xs">{friendsError}</p>
    {/if}
  </div>

  <label class="label cursor-pointer justify-start gap-3">
    <input
      type="checkbox"
      class="toggle toggle-sm"
      checked={showHolidays}
      disabled={readOnly}
      onchange={(event) => void onShowHolidays(event.currentTarget.checked).catch(() => undefined)}
    />
    <span class="label-text">Show holidays</span>
  </label>
</div>

{#if transferDrag?.phase === "dragging"}
  <div
    class="pointer-events-none fixed top-0 left-0 z-80 flex items-center gap-2 rounded-box border border-base-300 bg-base-100 px-2 py-1 text-xs shadow-lg"
    style={`transform: translate3d(${transferGhost.x + 12}px, ${transferGhost.y + 12}px, 0)`}
    aria-hidden="true"
    use:portal
  >
    <span
      class="h-2.5 w-2.5 shrink-0 rounded-full"
      style={`background:${cssColor(transferDrag.calendar.color)}`}
    ></span>
    <span class="max-w-40 truncate">{transferDrag.calendar.displayName}</span>
  </div>
{/if}

{#if transferHint}
  <div
    class="pointer-events-none fixed z-50 rounded-field border border-base-300 bg-base-200 px-2 py-1 text-xs whitespace-nowrap text-base-content/70 shadow-sm"
    style={`left:${transferHint.x}px; top:${transferHint.y}px; transform: translateY(-50%)`}
    role="tooltip"
    use:portal
  >
    Click & hold to move
  </div>
{/if}

<dialog class="modal" bind:this={createDialog} onclose={closeCreate}>
  <div class="modal-box">
    <h3 class="text-lg font-bold">New calendar</h3>
    <p class="py-2 text-base-content/70">Events you add will live on this calendar.</p>
    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        void onCreate({
          displayName,
          description: description.trim() === "" ? null : description,
          timeZone: calendarTimeZone,
          color,
        })
          .then(() => {
            closeCreate()
          })
          .catch(() => undefined)
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Name</legend>
        <input id="cal-name" class="input w-full" bind:value={displayName} required />
        {#if fieldError(createError, "displayName")}
          <p class="label text-error">{fieldError(createError, "displayName")}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Description</legend>
        <input id="cal-desc" class="input w-full" bind:value={description} />
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Color</legend>
        <div class="flex flex-wrap items-center gap-2">
          {#each CALENDAR_PALETTE as swatch (swatch)}
            <button
              type="button"
              class="h-6 w-6 rounded-full"
              class:outline={color === swatch}
              class:outline-offset-1={color === swatch}
              style={`background:${cssColor(swatch)}`}
              aria-label={`Use color ${swatch}`}
              onclick={() => (color = swatch)}
            ></button>
          {/each}
        </div>
        {#if fieldError(createError, "color")}
          <p class="label text-error">{fieldError(createError, "color")}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Time zone</legend>
        <input id="cal-tz" class="input w-full" bind:value={calendarTimeZone} required />
        {#if fieldError(createError, "timeZone")}
          <p class="label text-error">{fieldError(createError, "timeZone")}</p>
        {/if}
      </fieldset>
      <div class="modal-action">
        <button type="button" class="btn btn-ghost" onclick={closeCreate}>Cancel</button>
        <button type="submit" class="btn btn-primary" disabled={createPending}>
          {createPending ? "Creating…" : "Create"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<dialog class="modal" bind:this={editDialog} onclose={closeEdit}>
  <div class="modal-box">
    <h3 class="text-lg font-bold">Edit calendar</h3>
    <p class="py-2 text-base-content/70">Rename it, change its color, or change its time zone.</p>
    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        if (!editing) return
        void onUpdate({
          id: editing.id,
          displayName,
          description: description.trim() === "" ? null : description,
          timeZone: calendarTimeZone,
          color,
        })
          .then(() => {
            closeEdit()
          })
          .catch(() => undefined)
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Name</legend>
        <input id="edit-cal-name" class="input w-full" bind:value={displayName} required />
        {#if fieldError(updateError, "displayName")}
          <p class="label text-error">{fieldError(updateError, "displayName")}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Description</legend>
        <input id="edit-cal-desc" class="input w-full" bind:value={description} />
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Color</legend>
        <div class="flex flex-wrap items-center gap-2">
          {#each CALENDAR_PALETTE as swatch (swatch)}
            <button
              type="button"
              class="h-6 w-6 rounded-full"
              class:outline={color === swatch}
              class:outline-offset-1={color === swatch}
              style={`background:${cssColor(swatch)}`}
              aria-label={`Use color ${swatch}`}
              onclick={() => (color = swatch)}
            ></button>
          {/each}
        </div>
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Time zone</legend>
        <input id="edit-cal-tz" class="input w-full" bind:value={calendarTimeZone} required />
      </fieldset>
      <div class="modal-action">
        <button type="button" class="btn btn-ghost" onclick={closeEdit}>Cancel</button>
        <button type="submit" class="btn btn-primary" disabled={updatePending}>
          {updatePending ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<dialog class="modal" bind:this={deleteDialog} onclose={closeDelete}>
  <div class="modal-box">
    <h3 class="text-lg font-bold">Delete {editing?.displayName}?</h3>
    <p class="py-4 text-base-content/70">Events on this calendar will be deleted. This cannot be undone.</p>
    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={closeDelete}>Cancel</button>
      <button
        type="button"
        class="btn btn-error"
        disabled={deletePending}
        onclick={() =>
          editing &&
          void onDelete(editing.id)
            .then(() => {
              closeDelete()
            })
            .catch(() => undefined)}
      >
        {deletePending ? "Deleting…" : "Delete"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<dialog class="modal" bind:this={friendDialog} onclose={closeFriends}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">Add friend</h3>
    <p class="py-2 text-base-content/70">Search for someone by username or name.</p>
    <form
      class="flex gap-2"
      onsubmit={(event) => {
        event.preventDefault()
        void runSearch()
      }}
    >
      <input
        class="input w-full"
        bind:value={friendQuery}
        placeholder="Username or name"
        aria-label="Search users"
        disabled={searchUsers.isPending}
      />
      <button type="submit" class="btn btn-primary" disabled={searchUsers.isPending || friendQuery.trim() === ""}>
        {searchUsers.isPending ? "Searching…" : "Search"}
      </button>
    </form>

    {#if friendDialogError}
      <p class="mt-2 text-error text-sm">{friendDialogError}</p>
    {/if}

    {#if friendResults.length > 0}
      <ul class="mt-3 flex flex-col">
        {#each friendResults as result (result.userId)}
          <li class="flex items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
            {@render personAvatar(result.displayName, result.avatarUrl, "h-6 w-6 text-[0.6rem]")}
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{result.displayName}</span>
              <span class="block truncate text-[0.68rem] opacity-60">{result.username}</span>
            </span>
            {#if result.relationship === "friends"}
              <span class="badge badge-ghost badge-sm shrink-0">Friends</span>
            {:else if result.relationship === "pending_out"}
              <span class="badge badge-ghost badge-sm shrink-0">Requested</span>
            {:else}
              <button
                type="button"
                class="btn btn-primary btn-sm shrink-0"
                disabled={sendFriendRequest.isPending}
                onclick={() => void addFriend(result)}
              >
                {result.relationship === "pending_in" ? "Accept" : "Add"}
              </button>
            {/if}
          </li>
        {/each}
      </ul>
    {:else if friendSearched && !searchUsers.isPending}
      <p class="py-3 text-sm text-base-content/60">No users found.</p>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={closeFriends}>Done</button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<ShareDialog bind:open={shareOpen} calendar={sharing} />
<AvailabilityDialog bind:open={availabilityOpen} calendar={availabilityCalendar} />
<TimeRequestsDialog
  bind:open={requestsOpen}
  calendar={requestsCalendar}
  onPendingCount={setPendingCount}
/>
