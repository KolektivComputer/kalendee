<script lang="ts">
  import { Link, useAction } from "@kolektiv/keel-svelte"
  import Bell from "@lucide/svelte/icons/bell"
  import BellOff from "@lucide/svelte/icons/bell-off"
  import CalendarPlus from "@lucide/svelte/icons/calendar-plus"
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
    UpdateCalendarIn,
    UserSearchOut,
    UserSearchResult,
  } from "../page-types"
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
    createPending = false,
    updatePending = false,
    deletePending = false,
    createError = null,
    updateError = null,
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
    createPending?: boolean
    updatePending?: boolean
    deletePending?: boolean
    createError?: unknown
    updateError?: unknown
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

  interface OrganizationGroup {
    id: string
    slug: string | null
    name: string
    calendars: CalendarSummary[]
  }

  const personalCalendars = $derived(calendars.filter((calendar) => !calendar.organizationId))
  const orgGroups = $derived.by(() => {
    const groups = new Map<string, OrganizationGroup>()
    for (const calendar of calendars) {
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
      })
    }
    return [...groups.values()]
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
  let openMenuId = $state("")
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

  function openEdit(calendar: CalendarSummary) {
    openMenuId = ""
    editing = calendar
    displayName = calendar.displayName
    description = calendar.description ?? ""
    calendarTimeZone = calendar.timeZone
    color = calendar.color
    editOpen = true
  }

  function openDelete(calendar: CalendarSummary) {
    openMenuId = ""
    editing = calendar
    deleteOpen = true
  }

  function openShare(calendar: CalendarSummary) {
    openMenuId = ""
    sharing = calendar
    shareOpen = true
  }

  function openAvailability(calendar: CalendarSummary) {
    openMenuId = ""
    availabilityCalendar = calendar
    availabilityOpen = true
  }

  function openRequests(calendar: CalendarSummary) {
    openMenuId = ""
    requestsCalendar = calendar
    requestsOpen = true
  }

  function closeCreate() {
    createOpen = false
    displayName = ""
    description = ""
    calendarTimeZone = timeZone
    color = nextCalendarColor(calendars.map((calendar) => calendar.color))
  }

  function closeEdit() {
    editOpen = false
    editing = null
    displayName = ""
    description = ""
  }

  function closeDelete() {
    deleteOpen = false
    editing = null
  }

  function closeFriends() {
    friendsOpen = false
    friendQuery = ""
    friendResults = []
    friendSearched = false
    friendSearchError = ""
    searchUsers.reset()
    sendFriendRequest.reset()
  }

  function setPendingCount(calendarId: string, count: number) {
    pendingCounts = { ...pendingCounts, [calendarId]: count }
  }

  function unfollow(calendar: CalendarSummary) {
    void onUnfollow(calendar.id).catch(() => undefined)
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
            open={openMenuId === calendar.id}
            onOpenChange={(open) => {
              if (open) {
                openMenuId = calendar.id
              } else if (openMenuId === calendar.id) {
                openMenuId = ""
              }
            }}
          >
            <ContextMenu.Trigger disabled={!canManage && !canUnfollow}>
              {#snippet child({ props })}
                <div
                  {...props}
                  class="flex items-center gap-2"
                  class:menu-active={calendar.id === selectedId}
                  class:opacity-45={calendar.hidden}
                  onclick={() => (selectedId = calendar.id)}
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
                    title={calendar.hidden ? "Show" : "Hide"}
                    onclick={(event) => {
                      event.stopPropagation()
                      void onHidden(calendar.id, !calendar.hidden).catch(() => undefined)
                    }}
                  >
                    {#if calendar.hidden}
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

<div class="flex flex-col gap-3 p-3">
  <div class="flex items-center justify-between gap-2">
    <h2 class="text-xs font-semibold tracking-wide text-base-content/50 uppercase">Calendars</h2>
    {#if !readOnly}
      <button
        type="button"
        class="btn btn-ghost btn-xs btn-square tooltip tooltip-right"
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
    {#if personalCalendars.length > 0}
      <ul class="menu w-full p-0">
        {#each personalCalendars as calendar (calendar.id)}
          {@render calendarItem(calendar)}
        {/each}
      </ul>
    {/if}
    {#each orgGroups as group (group.id)}
      <div class="flex flex-col gap-1">
        <div class="flex items-center justify-between gap-2">
          <h3 class="truncate text-xs font-medium text-base-content/60" title={group.name}>{group.name}</h3>
          {#if group.slug}
            <Link href={`/o/${group.slug}`} class="link link-hover shrink-0 text-xs">Open</Link>
          {/if}
        </div>
        <ul class="menu w-full p-0">
          {#each group.calendars as calendar (calendar.id)}
            {@render calendarItem(calendar)}
          {/each}
        </ul>
      </div>
    {/each}
  {/if}

  {#if organizationsWithoutCalendars.length > 0}
    <div class="flex flex-col gap-1 border-t border-base-300 pt-3">
      <h2 class="text-xs font-semibold tracking-wide text-base-content/50 uppercase">Organizations</h2>
      <ul class="menu w-full p-0">
        {#each organizationsWithoutCalendars as organization (organization.id)}
          <li>
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
        class="btn btn-ghost btn-xs btn-square tooltip tooltip-right"
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
