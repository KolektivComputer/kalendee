<script lang="ts">
  import { Head, Link, page, useAction } from "@kolektiv/keel-svelte"
  import Copy from "@lucide/svelte/icons/copy"
  import { cssColor } from "../../../lib/colors"
  import { actionMessage } from "../../../lib/errors"
  import { organizationRoleBadge, organizationRoleLabel, sortOrganizationMembers } from "../../../lib/organizations"
  import type {
    DeletedOut,
    OrganizationMembershipOut,
    OrganizationProfilePage,
    RespondOrganizationInvitationIn,
  } from "../../../lib/page-types"

  const ctx = page<OrganizationProfilePage>()
  const acceptInvitation = useAction<RespondOrganizationInvitationIn, OrganizationMembershipOut>(
    "kalendee.acceptOrganizationInvitation",
  )
  const declineInvitation = useAction<RespondOrganizationInvitationIn, DeletedOut>(
    "kalendee.declineOrganizationInvitation",
  )

  let copied = $state(false)
  let copyTimer: ReturnType<typeof setTimeout> | undefined
  let responding = $state<"accept" | "decline" | null>(null)

  const members = $derived(sortOrganizationMembers(ctx.data.members))
  const invitation = $derived(ctx.data.pendingInvitation)
  const canManage = $derived(
    ctx.data.canManageSettings || ctx.data.viewerRole === "owner" || ctx.data.viewerRole === "admin",
  )

  $effect(() => {
    return () => {
      if (copyTimer) clearTimeout(copyTimer)
    }
  })

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function visibilityLabel(visibility: string): string {
    return visibility === "public" ? "Public" : "Private"
  }

  function countLabel(count: number, singular: string, plural: string): string {
    return `${count} ${count === 1 ? singular : plural}`
  }

  function orgUrl(): string {
    const path = `/o/${ctx.data.org.slug}`
    if (typeof window === "undefined") return path
    return `${window.location.origin}${path}`
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(orgUrl())
      copied = true
      if (copyTimer) clearTimeout(copyTimer)
      copyTimer = setTimeout(() => (copied = false), 2000)
    } catch {
      copied = false
    }
  }

  async function respond(accept: boolean) {
    const pending = invitation
    if (!pending || responding !== null) return
    responding = accept ? "accept" : "decline"
    const input: RespondOrganizationInvitationIn = { invitationId: pending.id, token: null }
    try {
      if (accept) {
        await acceptInvitation.mutateAsync(input)
      } else {
        await declineInvitation.mutateAsync(input)
      }
    } catch {
      // The action reloads the page on success; failures surface below.
    } finally {
      responding = null
    }
  }
</script>

<Head />

{#snippet entityAvatar(name: string, url: string | null, sizeClass: string)}
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

<div class="mx-auto flex w-full max-w-3xl flex-col gap-8 p-4 sm:p-6">
  {#if invitation}
    <div role="alert" class="flex flex-wrap items-center gap-3 rounded-box border border-primary/40 bg-base-200 p-4">
      <div class="min-w-0 flex-1">
        <p class="font-medium">You're invited to join {ctx.data.org.displayName}</p>
        <p class="settings-hint">Join as {organizationRoleLabel(invitation.role).toLowerCase()}.</p>
      </div>
      {#if ctx.data.viewer}
        <div class="flex shrink-0 items-center gap-2">
          <button type="button" class="btn btn-sm" disabled={responding !== null} onclick={() => void respond(false)}>
            {responding === "decline" ? "Declining…" : "Decline"}
          </button>
          <button
            type="button"
            class="btn btn-primary btn-sm"
            disabled={responding !== null}
            onclick={() => void respond(true)}
          >
            {responding === "accept" ? "Joining…" : "Accept invitation"}
          </button>
        </div>
      {:else}
        <Link href="/login" class="btn btn-primary btn-sm shrink-0">Sign in to respond</Link>
      {/if}
    </div>
    {#if acceptInvitation.error || declineInvitation.error}
      <p class="text-error text-sm">
        {actionMessage(acceptInvitation.error) || actionMessage(declineInvitation.error)}
      </p>
    {/if}
  {/if}

  <header class="flex flex-wrap items-start gap-4">
    <span
      class="flex h-16 w-16 shrink-0 items-center justify-center rounded-box bg-base-content/20 text-xl font-semibold"
      aria-hidden="true"
    >
      {initials(ctx.data.org.displayName)}
    </span>
    <div class="min-w-0 flex-1">
      <div class="flex flex-wrap items-center gap-2">
        <h1 class="text-2xl font-semibold">{ctx.data.org.displayName}</h1>
        <span
          class="badge badge-sm"
          class:badge-success={ctx.data.org.visibility === "public"}
          class:badge-ghost={ctx.data.org.visibility !== "public"}
        >
          {visibilityLabel(ctx.data.org.visibility)}
        </span>
        {#if ctx.data.viewerRole}
          <span class="badge badge-sm {organizationRoleBadge(ctx.data.viewerRole)}">
            You: {organizationRoleLabel(ctx.data.viewerRole)}
          </span>
        {/if}
      </div>
      <p class="text-sm text-base-content/60">@{ctx.data.org.slug}</p>
      {#if ctx.data.org.description}
        <p class="mt-2 text-base-content/80">{ctx.data.org.description}</p>
      {/if}
      <p class="mt-1 text-sm text-base-content/50">
        {countLabel(ctx.data.org.memberCount, "member", "members")}
      </p>
    </div>
    <div class="flex shrink-0 flex-wrap items-center gap-2">
      <button type="button" class="btn btn-ghost btn-sm gap-1.5" onclick={() => void copyLink()}>
        <Copy class="h-4 w-4" />
        {copied ? "Copied" : "Copy link"}
      </button>
      {#if canManage}
        <Link href={`/o/${ctx.data.org.slug}/settings`} class="btn btn-sm">Settings</Link>
      {/if}
    </div>
  </header>

  <section class="flex flex-col gap-3" aria-labelledby="org-members">
    <div class="flex items-center justify-between gap-2">
      <h2 id="org-members" class="text-lg font-semibold">Members</h2>
      {#if members.length > 0}
        <span class="settings-hint">{countLabel(members.length, "member", "members")}</span>
      {/if}
    </div>
    <div class="overflow-hidden rounded-box border border-base-300 bg-base-200">
      {#if members.length === 0}
        <p class="p-4 text-sm text-base-content/60">
          {ctx.data.org.visibility === "public"
            ? "No members yet."
            : "Members of private organizations are only visible to members."}
        </p>
      {:else}
        <ul class="flex flex-col">
          {#each members as member (member.userId)}
            <li class="flex flex-wrap items-center gap-3 border-b border-base-300 p-3 last:border-b-0">
              {@render entityAvatar(member.displayName, member.avatarUrl, "h-8 w-8 text-xs")}
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="truncate font-medium">{member.displayName}</span>
                  {#if member.isSelf}
                    <span class="badge badge-ghost badge-sm">You</span>
                  {/if}
                  <span class="badge badge-sm {organizationRoleBadge(member.role)}">
                    {organizationRoleLabel(member.role)}
                  </span>
                </div>
                <div class="truncate text-xs text-base-content/50">@{member.username}</div>
              </div>
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="org-calendars">
    <div class="flex items-center justify-between gap-2">
      <h2 id="org-calendars" class="text-lg font-semibold">Public calendars</h2>
      <span class="settings-hint">{countLabel(ctx.data.calendars.length, "calendar", "calendars")}</span>
    </div>
    {#if ctx.data.calendars.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        No public calendars yet.
      </div>
    {:else}
      <ul class="flex flex-col gap-2">
        {#each ctx.data.calendars as calendar (calendar.id)}
          <li>
            {#if calendar.publicLinkToken}
              <Link
                href={`/c/${calendar.publicLinkToken}`}
                class="flex items-center gap-3 rounded-box border border-base-300 bg-base-200 p-3 transition-colors hover:bg-base-300"
              >
                <span class="h-3 w-3 shrink-0 rounded-full" style={`background:${cssColor(calendar.color)}`}></span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate font-medium">{calendar.displayName}</span>
                  {#if calendar.description}
                    <span class="block truncate text-xs text-base-content/50">{calendar.description}</span>
                  {/if}
                </span>
              </Link>
            {:else}
              <div class="flex items-center gap-3 rounded-box border border-base-300 bg-base-200 p-3">
                <span class="h-3 w-3 shrink-0 rounded-full" style={`background:${cssColor(calendar.color)}`}></span>
                <span class="min-w-0 flex-1 truncate font-medium">{calendar.displayName}</span>
              </div>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>
</div>
