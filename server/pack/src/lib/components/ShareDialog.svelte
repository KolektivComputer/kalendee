<script lang="ts">
  import { ActionError, useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { fieldError } from "../errors"
  import type {
    CalendarSharingOut,
    CalendarSummary,
    GetCalendarSharingIn,
    RemoveShareIn,
    RotatePublicLinkIn,
    SetCalendarPublicIn,
    ShareCalendarIn,
    UpdateShareIn,
  } from "../page-types"

  let {
    open = $bindable(false),
    calendar = null,
  }: {
    open: boolean
    calendar: CalendarSummary | null
  } = $props()

  const getSharing = useAction<GetCalendarSharingIn, CalendarSharingOut>("kalendee.getCalendarSharing", {
    reload: false,
  })
  const shareCalendar = useAction<ShareCalendarIn, CalendarSharingOut>("kalendee.shareCalendar", { reload: false })
  const updateShare = useAction<UpdateShareIn, CalendarSharingOut>("kalendee.updateShare", { reload: false })
  const removeShare = useAction<RemoveShareIn, CalendarSharingOut>("kalendee.removeShare", { reload: false })
  const setCalendarPublic = useAction<SetCalendarPublicIn, CalendarSharingOut>("kalendee.setCalendarPublic", {
    reload: false,
  })
  const rotatePublicLink = useAction<RotatePublicLinkIn, CalendarSharingOut>("kalendee.rotatePublicLink", {
    reload: false,
  })

  let sharing = $state<CalendarSharingOut | null>(null)
  let loadedFor = $state("")
  let loadError = $state("")
  let inviteUsername = $state("")
  let invitePermission = $state("read")
  let publicEnabled = $state(false)
  let copied = $state(false)
  let dialog = $state<HTMLDialogElement | undefined>()
  let copyTimer: ReturnType<typeof setTimeout> | undefined

  const busy = $derived(
    shareCalendar.isPending ||
      updateShare.isPending ||
      removeShare.isPending ||
      setCalendarPublic.isPending ||
      rotatePublicLink.isPending,
  )
  const inviteError = $derived(message(shareCalendar.error))
  const friendSuggestions = $derived(
    (sharing?.friends ?? []).filter(
      (friend) => !(sharing?.shares ?? []).some((share) => share.userId === friend.userId),
    ),
  )
  const otherError = $derived(
    message(updateShare.error) ||
      message(removeShare.error) ||
      message(setCalendarPublic.error) ||
      message(rotatePublicLink.error),
  )

  function message(error: unknown): string {
    if (!error) return ""
    if (error instanceof ActionError) {
      const messages = Object.values(error.errors).flat()
      if (messages.length > 0) return messages.join(" ")
      return error.message
    }
    if (error instanceof Error && error.message) return error.message
    return "Something went wrong. Try again."
  }

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  function close() {
    open = false
    loadedFor = ""
    sharing = null
    loadError = ""
    inviteUsername = ""
    invitePermission = "read"
    publicEnabled = false
    copied = false
  }

  $effect(() => {
    if (!open) {
      loadedFor = ""
      sharing = null
      return
    }
    if (!calendar) return
    const id = calendar.id
    if (loadedFor === id) return
    loadedFor = id
    untrack(() => {
      getSharing.reset()
      shareCalendar.reset()
      updateShare.reset()
      removeShare.reset()
      setCalendarPublic.reset()
      rotatePublicLink.reset()
      inviteUsername = ""
      invitePermission = "read"
      copied = false
      loadError = ""
      sharing = null
      void reload(id)
    })
  })

  $effect(() => {
    publicEnabled = sharing?.publicLinkEnabled ?? false
  })

  $effect(() => {
    return () => {
      if (copyTimer) clearTimeout(copyTimer)
    }
  })

  async function reload(calendarId: string) {
    try {
      sharing = await getSharing.mutateAsync({ calendarId })
      loadError = ""
    } catch {
      loadError = "Could not load sharing settings."
    }
  }

  async function invite() {
    if (!calendar || busy) return
    const username = inviteUsername.trim()
    if (username === "") return
    try {
      await shareCalendar.mutateAsync({ calendarId: calendar.id, username, permission: invitePermission })
    } catch {
      return
    }
    inviteUsername = ""
    invitePermission = "read"
    await reload(calendar.id)
  }

  async function inviteFriend(username: string) {
    if (!calendar || busy) return
    try {
      await shareCalendar.mutateAsync({ calendarId: calendar.id, username, permission: invitePermission })
    } catch {
      return
    }
    await reload(calendar.id)
  }

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  async function changePermission(userId: string, permission: string) {
    if (!calendar) return
    await updateShare.mutateAsync({ calendarId: calendar.id, userId, permission }).catch(() => undefined)
    await reload(calendar.id)
  }

  async function remove(userId: string) {
    if (!calendar) return
    await removeShare.mutateAsync({ calendarId: calendar.id, userId }).catch(() => undefined)
    await reload(calendar.id)
  }

  async function togglePublic(enabled: boolean) {
    if (!calendar) return
    await setCalendarPublic.mutateAsync({ calendarId: calendar.id, enabled }).catch(() => undefined)
    await reload(calendar.id)
    publicEnabled = sharing?.publicLinkEnabled ?? false
  }

  async function rotate() {
    if (!calendar) return
    if (!window.confirm("Rotate the public link? The current link will stop working.")) return
    await rotatePublicLink.mutateAsync({ calendarId: calendar.id }).catch(() => undefined)
    await reload(calendar.id)
  }

  function publicUrl(token: string): string {
    if (typeof window === "undefined") return `/c/${token}`
    return `${window.location.origin}/c/${token}`
  }

  async function copy() {
    const token = sharing?.publicLinkToken
    if (!token) return
    try {
      await navigator.clipboard.writeText(publicUrl(token))
      copied = true
      if (copyTimer) clearTimeout(copyTimer)
      copyTimer = setTimeout(() => (copied = false), 2000)
    } catch {
      copied = false
    }
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-xl">
    <h3 class="text-lg font-bold">Share {calendar?.displayName ?? "calendar"}</h3>
    <p class="py-2 text-base-content/70">Invite people by username or email, or publish a read-only link.</p>

    {#if loadError}
      <div role="alert" class="alert alert-error mb-2">{loadError}</div>
    {/if}

    {#if sharing}
      <div class="flex flex-col gap-5">
        <form
          class="flex flex-col gap-2"
          onsubmit={(event) => {
            event.preventDefault()
            void invite()
          }}
        >
          <label class="label py-0" for="share-username">Username or email</label>
          <div class="flex flex-col gap-2 sm:flex-row">
            <input
              id="share-username"
              class="input w-full"
              bind:value={inviteUsername}
              placeholder="friend or friend@example.com"
              disabled={busy}
            />
            <select class="select sm:w-28" bind:value={invitePermission} disabled={busy} aria-label="Permission">
              <option value="read">Read</option>
              <option value="write">Write</option>
            </select>
            <button type="submit" class="btn btn-primary" disabled={busy || inviteUsername.trim() === ""}>
              {shareCalendar.isPending ? "Inviting…" : "Invite"}
            </button>
          </div>
          {#if fieldError(shareCalendar.error, "username")}
            <p class="text-error text-sm">{fieldError(shareCalendar.error, "username")}</p>
          {/if}
          {#if fieldError(shareCalendar.error, "email")}
            <p class="text-error text-sm">{fieldError(shareCalendar.error, "email")}</p>
          {/if}
          {#if inviteError && !fieldError(shareCalendar.error, "username") && !fieldError(shareCalendar.error, "email")}
            <p class="text-error text-sm">{inviteError}</p>
          {/if}
        </form>

        {#if friendSuggestions.length > 0}
          <div class="flex flex-col gap-2">
            <h4 class="text-sm font-semibold">Friends</h4>
            <p class="text-xs text-base-content/60">
              Invite a friend directly with the permission selected above.
            </p>
            <ul class="flex flex-wrap gap-2">
              {#each friendSuggestions as friend (friend.userId)}
                <li>
                  <button
                    type="button"
                    class="btn btn-sm gap-2"
                    disabled={busy}
                    title={`Invite ${friend.displayName} with ${invitePermission} access`}
                    onclick={() => void inviteFriend(friend.username)}
                  >
                    {#if friend.avatarUrl}
                      <img class="h-5 w-5 rounded-full object-cover" src={friend.avatarUrl} alt="" />
                    {:else}
                      <span
                        class="flex h-5 w-5 items-center justify-center rounded-full bg-base-content/20 text-[0.55rem] font-semibold leading-none"
                        aria-hidden="true"
                      >
                        {initials(friend.displayName)}
                      </span>
                    {/if}
                    <span class="max-w-36 truncate">{friend.displayName}</span>
                  </button>
                </li>
              {/each}
            </ul>
          </div>
        {/if}

        <div class="flex flex-col gap-2">
          <h4 class="text-sm font-semibold">People with access</h4>
          {#if sharing.shares.length === 0}
            <p class="text-sm text-base-content/60">No one else has access yet.</p>
          {:else}
            <ul class="flex flex-col">
              {#each sharing.shares as share (share.userId)}
                <li class="flex items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                  <div class="min-w-0 flex-1">
                    <div class="truncate">{share.displayName}</div>
                    <div class="truncate text-xs text-base-content/60">{share.username}</div>
                  </div>
                  <select
                    class="select select-sm"
                    value={share.permission}
                    disabled={busy}
                    aria-label={`Permission for ${share.displayName}`}
                    onchange={(event) => void changePermission(share.userId, event.currentTarget.value)}
                  >
                    <option value="read">Read</option>
                    <option value="write">Write</option>
                  </select>
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm text-error"
                    disabled={busy}
                    onclick={() => void remove(share.userId)}
                  >
                    Remove
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
        </div>

        <div class="flex flex-col gap-3 rounded-box border border-base-300 p-3">
          <label class="label cursor-pointer justify-start gap-3">
            <input
              type="checkbox"
              class="toggle toggle-sm"
              bind:checked={publicEnabled}
              disabled={busy}
              onchange={(event) => void togglePublic(event.currentTarget.checked)}
            />
            <span class="label-text">Public read-only link</span>
          </label>
          {#if sharing.publicLinkEnabled}
            {#if sharing.publicLinkToken}
              <div class="join w-full">
                <input
                  class="input join-item w-full"
                  readonly
                  value={publicUrl(sharing.publicLinkToken)}
                  aria-label="Public link"
                />
                <button type="button" class="btn join-item" onclick={() => void copy()}>
                  {copied ? "Copied" : "Copy"}
                </button>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  class="btn btn-sm"
                  disabled={busy}
                  onclick={() => void rotate()}
                >
                  {rotatePublicLink.isPending ? "Rotating…" : "Rotate link"}
                </button>
                <span class="text-sm text-base-content/60">
                  {sharing.followerCount}
                  {sharing.followerCount === 1 ? "follower" : "followers"}
                </span>
              </div>
            {:else}
              <p class="text-sm text-base-content/60">Preparing public link…</p>
            {/if}
          {:else}
            <p class="text-sm text-base-content/60">Anyone with the link can view this calendar without an account.</p>
          {/if}
        </div>

        {#if otherError}
          <p class="text-error text-sm">{otherError}</p>
        {/if}
      </div>
    {:else if !loadError}
      <div class="flex justify-center py-6">
        <span class="loading loading-spinner"></span>
      </div>
    {/if}

    <div class="modal-action">
      <button type="button" class="btn btn-ghost" onclick={close}>Done</button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
