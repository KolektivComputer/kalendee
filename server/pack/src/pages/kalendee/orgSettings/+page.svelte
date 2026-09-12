<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import ChevronDown from "@lucide/svelte/icons/chevron-down"
  import ChevronLeft from "@lucide/svelte/icons/chevron-left"
  import ChevronRight from "@lucide/svelte/icons/chevron-right"
  import { cssColor } from "../../../lib/colors"
  import { actionMessage, fieldError } from "../../../lib/errors"
  import { organizationRoleBadge, organizationRoleLabel, sortOrganizationMembers } from "../../../lib/organizations"
  import type {
    AddOrganizationTeamMemberIn,
    CalendarOptionSummary,
    CreateOrganizationTeamIn,
    DeleteOrganizationIn,
    DeleteOrganizationTeamIn,
    DeletedOut,
    GrantCalendarToTeamIn,
    InviteToOrganizationIn,
    OrganizationInvitationIn,
    OrganizationInvitationOut,
    OrganizationInvitationSummary,
    OrganizationInvitationsIn,
    OrganizationInvitationsOut,
    OrganizationMemberSummary,
    OrganizationMembersIn,
    OrganizationMembersOut,
    OrganizationSettingsPage,
    OrganizationSummary,
    OrganizationTeamCalendarSummary,
    OrganizationTeamMemberSummary,
    OrganizationTeamSummary,
    RemoveOrganizationMemberIn,
    RemoveOrganizationTeamMemberIn,
    RevokeCalendarFromTeamIn,
    SetOrganizationMemberRoleIn,
    SetOrganizationTeamMemberRoleIn,
    UpdateOrganizationIn,
    UpdateOrganizationTeamIn,
  } from "../../../lib/page-types"

  const ctx = page<OrganizationSettingsPage>()
  const updateOrganization = useAction<UpdateOrganizationIn, OrganizationSummary>("kalendee.updateOrganization", {
    reload: false,
  })
  const inviteToOrganization = useAction<InviteToOrganizationIn, OrganizationInvitationOut>(
    "kalendee.inviteToOrganization",
    { reload: false },
  )
  const revokeInvitation = useAction<OrganizationInvitationIn, OrganizationInvitationOut>(
    "kalendee.revokeOrganizationInvitation",
    { reload: false },
  )
  const listInvitations = useAction<OrganizationInvitationsIn, OrganizationInvitationsOut>(
    "kalendee.organizationInvitations",
    { reload: false },
  )
  const listMembers = useAction<OrganizationMembersIn, OrganizationMembersOut>("kalendee.organizationMembers", {
    reload: false,
  })
  const setMemberRole = useAction<SetOrganizationMemberRoleIn, OrganizationMemberSummary>(
    "kalendee.setOrganizationMemberRole",
    { reload: false },
  )
  const removeMember = useAction<RemoveOrganizationMemberIn, DeletedOut>("kalendee.removeOrganizationMember", {
    reload: false,
  })
  const deleteOrganization = useAction<DeleteOrganizationIn, DeletedOut>("kalendee.deleteOrganization", {
    reload: false,
  })
  const createTeam = useAction<CreateOrganizationTeamIn, OrganizationTeamSummary>(
    "kalendee.createOrganizationTeam",
    { reload: false },
  )
  const updateTeam = useAction<UpdateOrganizationTeamIn, OrganizationTeamSummary>(
    "kalendee.updateOrganizationTeam",
    { reload: false },
  )
  const deleteTeam = useAction<DeleteOrganizationTeamIn, DeletedOut>("kalendee.deleteOrganizationTeam", {
    reload: false,
  })
  const addTeamMember = useAction<AddOrganizationTeamMemberIn, OrganizationTeamMemberSummary>(
    "kalendee.addOrganizationTeamMember",
    { reload: false },
  )
  const removeTeamMember = useAction<RemoveOrganizationTeamMemberIn, DeletedOut>(
    "kalendee.removeOrganizationTeamMember",
    { reload: false },
  )
  const setTeamMemberRole = useAction<SetOrganizationTeamMemberRoleIn, OrganizationTeamMemberSummary>(
    "kalendee.setOrganizationTeamMemberRole",
    { reload: false },
  )
  const grantCalendar = useAction<GrantCalendarToTeamIn, OrganizationTeamCalendarSummary>(
    "kalendee.grantCalendarToTeam",
    { reload: false },
  )
  const revokeCalendar = useAction<RevokeCalendarFromTeamIn, DeletedOut>("kalendee.revokeCalendarFromTeam", {
    reload: false,
  })

  let org = $state(ctx.data.org)
  let members = $state(sortOrganizationMembers(ctx.data.members))
  let invitations = $state(
    ctx.data.invitations.filter((invitation) => invitation.status === "pending"),
  )
  let teams = $state(ctx.data.teams)
  let manageableCalendars = $state(ctx.data.manageableCalendars)

  let displayName = $state(ctx.data.org.displayName)
  let description = $state(ctx.data.org.description ?? "")
  let visibility = $state(ctx.data.org.visibility)
  let profileLocalError = $state("")
  let profileSaved = $state(false)

  let inviteIdentifier = $state("")
  let inviteRole = $state<"member" | "admin" | "owner">("member")
  let inviteLocalError = $state("")
  let inviteNotice = $state("")

  let memberError = $state("")
  let invitationError = $state("")

  let deleteOpen = $state(false)
  let deleteDialog = $state<HTMLDialogElement | undefined>()
  let deleteError = $state("")
  let deleting = $state(false)

  let expandedTeamId = $state("")
  let createTeamOpen = $state(false)
  let createTeamDialog = $state<HTMLDialogElement | undefined>()
  let newTeamName = $state("")
  let newTeamSlug = $state("")
  let newTeamDescription = $state("")
  let teamError = $state("")
  let editingTeamId = $state("")
  let editTeamName = $state("")
  let editTeamDescription = $state("")
  let selectedTeamMember = $state<Record<string, string>>({})
  let selectedTeamCalendar = $state<Record<string, string>>({})
  let selectedGrantPermission = $state<Record<string, string>>({})
  let deleteTeamOpen = $state(false)
  let deleteTeamDialog = $state<HTMLDialogElement | undefined>()
  let deletingTeam = $state<OrganizationTeamSummary | null>(null)
  let deleteTeamError = $state("")
  let deleteTeamPending = $state(false)

  const canManageMembers = $derived(ctx.data.canManageMembers)
  const canManageOwners = $derived(ctx.data.canManageOwners)
  const canManageTeams = $derived(ctx.data.viewerRole === "owner" || ctx.data.viewerRole === "admin")
  const ownerCount = $derived(members.filter((member) => member.role === "owner").length)
  const roleOptions = $derived(
    canManageOwners
      ? [
          { value: "member", label: "Member" },
          { value: "admin", label: "Admin" },
          { value: "owner", label: "Owner" },
        ]
      : [{ value: "member", label: "Member" }],
  )

  $effect(() => {
    org = ctx.data.org
    members = sortOrganizationMembers(ctx.data.members)
    invitations = ctx.data.invitations.filter((invitation) => invitation.status === "pending")
    teams = ctx.data.teams
    manageableCalendars = ctx.data.manageableCalendars
    displayName = ctx.data.org.displayName
    description = ctx.data.org.description ?? ""
    visibility = ctx.data.org.visibility
  })

  $effect(() => {
    if (!deleteDialog) return
    if (deleteOpen && !deleteDialog.open) deleteDialog.showModal()
    if (!deleteOpen && deleteDialog.open) deleteDialog.close()
  })

  $effect(() => {
    if (!createTeamDialog) return
    if (createTeamOpen && !createTeamDialog.open) createTeamDialog.showModal()
    if (!createTeamOpen && createTeamDialog.open) createTeamDialog.close()
  })

  $effect(() => {
    if (!deleteTeamDialog) return
    if (deleteTeamOpen && !deleteTeamDialog.open) deleteTeamDialog.showModal()
    if (!deleteTeamOpen && deleteTeamDialog.open) deleteTeamDialog.close()
  })

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function formatDate(value: string): string {
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ""
    return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" }).format(date)
  }

  function canChangeRole(member: OrganizationMemberSummary): boolean {
    if (!canManageMembers || !canManageOwners || member.isSelf) return false
    if (member.role === "owner" && ownerCount <= 1) return false
    return true
  }

  function canRemoveMember(member: OrganizationMemberSummary): boolean {
    if (!canManageMembers || member.isSelf) return false
    if (!canManageOwners && member.role !== "member") return false
    if (member.role === "owner" && ownerCount <= 1) return false
    return true
  }

  function removeTitle(member: OrganizationMemberSummary): string {
    if (member.isSelf) return "You cannot remove yourself"
    if (!canManageOwners && member.role !== "member") return "Only an owner can remove admins and owners"
    if (member.role === "owner" && ownerCount <= 1) return "An organization needs at least one owner"
    return `Remove ${member.displayName}`
  }

  async function saveProfile() {
    if (!canManageMembers || updateOrganization.isPending) return
    const cleanName = displayName.trim()
    if (cleanName === "") {
      profileLocalError = "Enter a name."
      return
    }
    profileLocalError = ""
    profileSaved = false
    try {
      const updated = await updateOrganization.mutateAsync({
        organizationId: org.id,
        displayName: cleanName,
        description: description.trim(),
        visibility: canManageOwners ? visibility : null,
      })
      org = updated
      displayName = updated.displayName
      description = updated.description ?? ""
      visibility = updated.visibility
      profileSaved = true
    } catch {
      // Errors render from the action state.
    }
  }

  async function refreshMembers() {
    try {
      members = sortOrganizationMembers((await listMembers.mutateAsync({ organizationId: org.id })).members)
    } catch {
      // Keep the current list on failure.
    }
  }

  async function refreshInvitations() {
    try {
      invitations = (await listInvitations.mutateAsync({ organizationId: org.id })).invitations.filter(
        (invitation) => invitation.status === "pending",
      )
    } catch {
      // Keep the current list on failure.
    }
  }

  async function invite() {
    if (!canManageMembers || inviteToOrganization.isPending) return
    const identifier = inviteIdentifier.trim()
    if (identifier === "") {
      inviteLocalError = "Enter a username or email address."
      return
    }
    inviteLocalError = ""
    inviteNotice = ""
    try {
      await inviteToOrganization.mutateAsync({ organizationId: org.id, identifier, role: inviteRole })
      inviteIdentifier = ""
      inviteNotice = "Invitation sent."
      await refreshInvitations()
    } catch {
      // Errors render from the action state.
    }
  }

  async function changeRole(member: OrganizationMemberSummary, role: string) {
    if (role === member.role || !canChangeRole(member) || setMemberRole.isPending) return
    memberError = ""
    try {
      const updated = await setMemberRole.mutateAsync({ organizationId: org.id, userId: member.userId, role })
      members = sortOrganizationMembers(
        members.map((entry) => (entry.userId === updated.userId ? updated : entry)),
      )
    } catch (error) {
      memberError = actionMessage(error)
      await refreshMembers()
    }
  }

  async function remove(member: OrganizationMemberSummary) {
    if (!canRemoveMember(member) || removeMember.isPending) return
    if (!window.confirm(`Remove ${member.displayName} from ${org.displayName}?`)) return
    memberError = ""
    try {
      await removeMember.mutateAsync({ organizationId: org.id, userId: member.userId })
      members = members.filter((entry) => entry.userId !== member.userId)
    } catch (error) {
      memberError = actionMessage(error)
      await refreshMembers()
    }
  }

  function teamRoleLabel(role: string): string {
    return role === "maintainer" ? "Maintainer" : "Member"
  }

  function sortTeamMembers(list: OrganizationTeamMemberSummary[]): OrganizationTeamMemberSummary[] {
    return [...list].sort((a, b) => a.username.localeCompare(b.username))
  }

  function replaceTeam(updated: OrganizationTeamSummary) {
    teams = teams.map((team) => (team.id === updated.id ? updated : team))
  }

  function availableTeamMembers(team: OrganizationTeamSummary): OrganizationMemberSummary[] {
    const inTeam = new Set(team.members.map((member) => member.userId))
    return members.filter((member) => !inTeam.has(member.userId))
  }

  function availableTeamCalendars(team: OrganizationTeamSummary): CalendarOptionSummary[] {
    const granted = new Set(team.grants.map((grant) => grant.calendarId))
    return manageableCalendars.filter((calendar) => !granted.has(calendar.id))
  }

  function openCreateTeam() {
    newTeamName = ""
    newTeamSlug = ""
    newTeamDescription = ""
    teamError = ""
    createTeam.reset()
    createTeamOpen = true
  }

  async function addTeam() {
    if (!canManageTeams || createTeam.isPending) return
    const name = newTeamName.trim()
    const slug = newTeamSlug.trim()
    if (name === "" || slug === "") {
      teamError = "Enter a name and a slug."
      return
    }
    teamError = ""
    try {
      const created = await createTeam.mutateAsync({
        organizationId: org.id,
        slug,
        name,
        description: newTeamDescription.trim() === "" ? null : newTeamDescription,
      })
      teams = [...teams, created]
      expandedTeamId = created.id
      createTeamOpen = false
    } catch {
      // Errors render from the action state.
    }
  }

  function startEditingTeam(team: OrganizationTeamSummary) {
    editingTeamId = team.id
    editTeamName = team.name
    editTeamDescription = team.description ?? ""
  }

  async function saveTeam() {
    const team = teams.find((entry) => entry.id === editingTeamId)
    if (!team || updateTeam.isPending) return
    const name = editTeamName.trim()
    if (name === "") return
    teamError = ""
    try {
      const updated = await updateTeam.mutateAsync({
        teamId: team.id,
        name,
        description: editTeamDescription.trim(),
      })
      replaceTeam(updated)
      editingTeamId = ""
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function addTeamMemberTo(team: OrganizationTeamSummary) {
    const userId = selectedTeamMember[team.id] ?? ""
    if (userId === "" || addTeamMember.isPending) return
    teamError = ""
    try {
      const member = await addTeamMember.mutateAsync({ teamId: team.id, userId })
      selectedTeamMember = { ...selectedTeamMember, [team.id]: "" }
      replaceTeam({
        ...team,
        memberCount: team.memberCount + 1,
        members: sortTeamMembers([...team.members, member]),
      })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function changeTeamMemberRole(
    team: OrganizationTeamSummary,
    member: OrganizationTeamMemberSummary,
    role: string,
  ) {
    if (role === member.role || setTeamMemberRole.isPending) return
    teamError = ""
    try {
      const updated = await setTeamMemberRole.mutateAsync({ teamId: team.id, userId: member.userId, role })
      replaceTeam({
        ...team,
        members: team.members.map((entry) => (entry.userId === updated.userId ? updated : entry)),
      })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function removeTeamMemberFrom(team: OrganizationTeamSummary, member: OrganizationTeamMemberSummary) {
    if (removeTeamMember.isPending) return
    if (!window.confirm(`Remove ${member.displayName} from ${team.name}?`)) return
    teamError = ""
    try {
      await removeTeamMember.mutateAsync({ teamId: team.id, userId: member.userId })
      replaceTeam({
        ...team,
        memberCount: Math.max(0, team.memberCount - 1),
        members: team.members.filter((entry) => entry.userId !== member.userId),
      })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function grantCalendarTo(team: OrganizationTeamSummary) {
    const calendarId = selectedTeamCalendar[team.id] ?? ""
    const permission = selectedGrantPermission[team.id] ?? "read"
    if (calendarId === "" || grantCalendar.isPending) return
    teamError = ""
    try {
      const grant = await grantCalendar.mutateAsync({ calendarId, teamId: team.id, permission })
      selectedTeamCalendar = { ...selectedTeamCalendar, [team.id]: "" }
      replaceTeam({
        ...team,
        grants: [...team.grants, grant].sort((a, b) => a.displayName.localeCompare(b.displayName)),
      })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function changeGrantPermission(
    team: OrganizationTeamSummary,
    grant: OrganizationTeamCalendarSummary,
    permission: string,
  ) {
    if (permission === grant.permission || grantCalendar.isPending) return
    teamError = ""
    try {
      const updated = await grantCalendar.mutateAsync({ calendarId: grant.calendarId, teamId: team.id, permission })
      replaceTeam({
        ...team,
        grants: team.grants.map((entry) => (entry.calendarId === updated.calendarId ? updated : entry)),
      })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  async function revokeGrant(team: OrganizationTeamSummary, grant: OrganizationTeamCalendarSummary) {
    if (revokeCalendar.isPending) return
    if (!window.confirm(`Revoke ${grant.displayName} from ${team.name}?`)) return
    teamError = ""
    try {
      await revokeCalendar.mutateAsync({ calendarId: grant.calendarId, teamId: team.id })
      replaceTeam({ ...team, grants: team.grants.filter((entry) => entry.calendarId !== grant.calendarId) })
    } catch (error) {
      teamError = actionMessage(error)
    }
  }

  function openDeleteTeam(team: OrganizationTeamSummary) {
    deletingTeam = team
    deleteTeamError = ""
    deleteTeamOpen = true
  }

  async function confirmDeleteTeam() {
    const team = deletingTeam
    if (!team || deleteTeamPending) return
    deleteTeamPending = true
    deleteTeamError = ""
    try {
      await deleteTeam.mutateAsync({ teamId: team.id })
      teams = teams.filter((entry) => entry.id !== team.id)
      if (expandedTeamId === team.id) expandedTeamId = ""
      deleteTeamOpen = false
      deletingTeam = null
    } catch (error) {
      deleteTeamError = actionMessage(error)
    } finally {
      deleteTeamPending = false
    }
  }

  async function revoke(invitation: OrganizationInvitationSummary) {
    if (revokeInvitation.isPending) return
    invitationError = ""
    try {
      await revokeInvitation.mutateAsync({ invitationId: invitation.id, organizationId: org.id })
      invitations = invitations.filter((entry) => entry.id !== invitation.id)
    } catch (error) {
      invitationError = actionMessage(error)
    }
  }

  async function confirmDelete() {
    if (deleting) return
    deleting = true
    deleteError = ""
    try {
      await deleteOrganization.mutateAsync({ organizationId: org.id })
      await router.visit("/directory")
    } catch (error) {
      deleteError = actionMessage(error)
    } finally {
      deleting = false
    }
  }
</script>

<Head />

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

<div class="mx-auto flex w-full max-w-3xl flex-col gap-8 p-4 sm:p-6">
  <header class="flex flex-col gap-1">
    <Link href={`/o/${org.slug}`} class="link link-hover inline-flex items-center gap-1 text-sm">
      <ChevronLeft class="h-4 w-4" />
      Back to {org.displayName}
    </Link>
    <h1 class="text-2xl font-semibold">Organization settings</h1>
    <div class="flex flex-wrap items-center gap-2">
      <span class="text-sm text-base-content/60">@{org.slug}</span>
      <span class="badge badge-sm {organizationRoleBadge(ctx.data.viewerRole)}">
        Your role: {organizationRoleLabel(ctx.data.viewerRole)}
      </span>
    </div>
  </header>

  <section class="flex flex-col gap-3" aria-labelledby="settings-profile">
    <h2 id="settings-profile" class="text-lg font-semibold">Profile</h2>
    <div class="rounded-box border border-base-300 bg-base-200 p-4">
      {#if !canManageMembers}
        <div class="flex flex-col gap-2">
          <p class="font-medium">{org.displayName}</p>
          {#if org.description}
            <p class="text-sm text-base-content/70">{org.description}</p>
          {/if}
          <p class="text-sm text-base-content/60">
            {org.visibility === "public"
              ? "Public — listed in the directory."
              : "Private — visible to members and invitees only."}
          </p>
          <p class="settings-hint">Only owners and admins can change these settings.</p>
        </div>
      {:else}
        <form
          class="flex flex-col gap-4"
          onsubmit={(event) => {
            event.preventDefault()
            void saveProfile()
          }}
        >
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Name</legend>
            <input
              class="input w-full"
              bind:value={displayName}
              required
              maxlength="80"
              disabled={updateOrganization.isPending}
            />
            {#if fieldError(updateOrganization.error, "displayName")}
              <p class="label text-error">{fieldError(updateOrganization.error, "displayName")}</p>
            {/if}
          </fieldset>
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Description</legend>
            <textarea
              class="textarea w-full"
              rows="3"
              bind:value={description}
              disabled={updateOrganization.isPending}
            ></textarea>
          </fieldset>
          <fieldset class="fieldset">
            <legend class="fieldset-legend">Visibility</legend>
            <label class="label cursor-pointer justify-start gap-3">
              <input
                type="radio"
                class="radio radio-sm"
                name="settings-visibility"
                value="private"
                checked={visibility === "private"}
                disabled={updateOrganization.isPending || !canManageOwners}
                onchange={() => (visibility = "private")}
              />
              <span class="label-text">Private — only members and invitees can see the page</span>
            </label>
            <label class="label cursor-pointer justify-start gap-3">
              <input
                type="radio"
                class="radio radio-sm"
                name="settings-visibility"
                value="public"
                checked={visibility === "public"}
                disabled={updateOrganization.isPending || !canManageOwners}
                onchange={() => (visibility = "public")}
              />
              <span class="label-text">Public — listed in the directory</span>
            </label>
            {#if !canManageOwners}
              <p class="label">Only an owner can change visibility.</p>
            {/if}
          </fieldset>

          {#if profileLocalError}
            <p class="text-error text-sm">{profileLocalError}</p>
          {:else if updateOrganization.error}
            <p class="text-error text-sm">{actionMessage(updateOrganization.error)}</p>
          {/if}
          {#if profileSaved && !updateOrganization.error}
            <p class="text-sm text-success">Saved.</p>
          {/if}

          <div class="flex justify-end">
            <button type="submit" class="btn btn-primary btn-sm" disabled={updateOrganization.isPending}>
              {updateOrganization.isPending ? "Saving…" : "Save changes"}
            </button>
          </div>
        </form>
      {/if}
    </div>
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="settings-members">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 id="settings-members" class="text-lg font-semibold">Members</h2>
      <span class="settings-hint">{members.length} {members.length === 1 ? "member" : "members"}</span>
    </div>

    {#if canManageMembers}
      <form
        class="flex flex-col gap-2 rounded-box border border-base-300 bg-base-200 p-4 sm:flex-row sm:items-start"
        onsubmit={(event) => {
          event.preventDefault()
          void invite()
        }}
      >
        <div class="flex flex-1 flex-col gap-1">
          <input
            class="input w-full"
            bind:value={inviteIdentifier}
            placeholder="Username or email"
            aria-label="Username or email"
            autocomplete="off"
            disabled={inviteToOrganization.isPending}
          />
          {#if fieldError(inviteToOrganization.error, "identifier")}
            <p class="text-error text-sm">{fieldError(inviteToOrganization.error, "identifier")}</p>
          {/if}
        </div>
        <select
          class="select w-full sm:w-32"
          bind:value={inviteRole}
          aria-label="Role"
          disabled={inviteToOrganization.isPending}
        >
          {#each roleOptions as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
        <button
          type="submit"
          class="btn btn-primary"
          disabled={inviteToOrganization.isPending || inviteIdentifier.trim() === ""}
        >
          {inviteToOrganization.isPending ? "Inviting…" : "Invite"}
        </button>
      </form>
      {#if inviteLocalError}
        <p class="text-error text-sm">{inviteLocalError}</p>
      {:else if inviteToOrganization.error && !fieldError(inviteToOrganization.error, "identifier")}
        <p class="text-error text-sm">{actionMessage(inviteToOrganization.error)}</p>
      {/if}
      {#if inviteNotice}
        <p class="text-sm text-success">{inviteNotice}</p>
      {/if}
    {/if}

    <div class="overflow-hidden rounded-box border border-base-300 bg-base-200">
      {#if members.length === 0}
        <p class="p-4 text-sm text-base-content/60">No members yet.</p>
      {:else}
        <ul class="flex flex-col">
          {#each members as member (member.userId)}
            <li class="flex flex-wrap items-center gap-3 border-b border-base-300 p-3 last:border-b-0">
              {@render personAvatar(member.displayName, member.avatarUrl, "h-8 w-8 text-xs")}
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
              {#if canManageMembers}
                <div class="flex shrink-0 items-center gap-2">
                  {#if canManageOwners}
                    <select
                      class="select select-sm w-28"
                      value={member.role}
                      aria-label={`Role for ${member.displayName}`}
                      disabled={setMemberRole.isPending || !canChangeRole(member)}
                      onchange={(event) => void changeRole(member, event.currentTarget.value)}
                    >
                      {#each roleOptions as option (option.value)}
                        <option value={option.value}>{option.label}</option>
                      {/each}
                    </select>
                  {/if}
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm text-error"
                    disabled={!canRemoveMember(member) || removeMember.isPending}
                    title={removeTitle(member)}
                    onclick={() => void remove(member)}
                  >
                    Remove
                  </button>
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    </div>
    {#if memberError}
      <p class="text-error text-sm">{memberError}</p>
    {/if}
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="settings-teams">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 id="settings-teams" class="text-lg font-semibold">Teams</h2>
      <div class="flex items-center gap-2">
        <span class="settings-hint">{teams.length} {teams.length === 1 ? "team" : "teams"}</span>
        {#if canManageTeams}
          <button type="button" class="btn btn-primary btn-sm" onclick={openCreateTeam}>New team</button>
        {/if}
      </div>
    </div>

    {#if teams.length === 0}
      <p class="rounded-box border border-base-300 bg-base-200 p-4 text-sm text-base-content/60">
        No teams yet. Teams group members so calendars can be shared with everyone in them.
      </p>
    {:else}
      <ul class="flex flex-col gap-2">
        {#each teams as team (team.id)}
          {@const expanded = expandedTeamId === team.id}
          <li class="overflow-hidden rounded-box border border-base-300 bg-base-200">
            <div class="flex flex-wrap items-center gap-3 p-3">
              <button
                type="button"
                class="flex min-w-0 flex-1 items-center gap-2 text-left"
                aria-expanded={expanded}
                onclick={() => (expandedTeamId = expanded ? "" : team.id)}
              >
                {#if expanded}
                  <ChevronDown class="h-4 w-4 shrink-0" />
                {:else}
                  <ChevronRight class="h-4 w-4 shrink-0" />
                {/if}
                <span class="min-w-0">
                  <span class="flex flex-wrap items-center gap-2">
                    <span class="truncate font-medium">{team.name}</span>
                    {#if team.isDefault}
                      <span class="badge badge-ghost badge-sm">Default</span>
                    {/if}
                    {#if team.viewerRole}
                      <span
                        class="badge badge-sm {team.viewerRole === "maintainer" ? "badge-primary" : "badge-ghost"}"
                      >
                        {teamRoleLabel(team.viewerRole)}
                      </span>
                    {/if}
                  </span>
                  <span class="block truncate text-xs text-base-content/50">
                    @{team.slug} · {team.memberCount}
                    {team.memberCount === 1 ? "member" : "members"}
                  </span>
                </span>
              </button>
              {#if team.canDelete}
                <button
                  type="button"
                  class="btn btn-ghost btn-sm text-error"
                  onclick={() => openDeleteTeam(team)}
                >
                  Delete
                </button>
              {/if}
            </div>

            {#if expanded}
              <div class="flex flex-col gap-4 border-t border-base-300 p-3">
                {#if team.description}
                  <p class="text-sm text-base-content/70">{team.description}</p>
                {/if}

                {#if team.canDelete}
                  {#if editingTeamId === team.id}
                    <form
                      class="flex flex-col gap-2 rounded-field border border-base-300 p-3"
                      onsubmit={(event) => {
                        event.preventDefault()
                        void saveTeam()
                      }}
                    >
                      <fieldset class="fieldset">
                        <legend class="fieldset-legend">Name</legend>
                        <input
                          class="input input-sm w-full"
                          bind:value={editTeamName}
                          required
                          maxlength="80"
                          disabled={updateTeam.isPending}
                        />
                        {#if fieldError(updateTeam.error, "name")}
                          <p class="label text-error">{fieldError(updateTeam.error, "name")}</p>
                        {/if}
                      </fieldset>
                      <fieldset class="fieldset">
                        <legend class="fieldset-legend">Description</legend>
                        <textarea
                          class="textarea textarea-sm w-full"
                          rows="2"
                          bind:value={editTeamDescription}
                          disabled={updateTeam.isPending}
                        ></textarea>
                      </fieldset>
                      <div class="flex justify-end gap-2">
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          disabled={updateTeam.isPending}
                          onclick={() => (editingTeamId = "")}
                        >
                          Cancel
                        </button>
                        <button type="submit" class="btn btn-primary btn-sm" disabled={updateTeam.isPending}>
                          {updateTeam.isPending ? "Saving…" : "Save"}
                        </button>
                      </div>
                    </form>
                  {:else}
                    <div>
                      <button
                        type="button"
                        class="btn btn-ghost btn-xs"
                        onclick={() => startEditingTeam(team)}
                      >
                        Edit details
                      </button>
                    </div>
                  {/if}
                {/if}

                <div class="flex flex-col gap-2">
                  <h4 class="text-sm font-semibold">Members</h4>
                  {#if team.members.length === 0}
                    <p class="text-sm text-base-content/60">No one is in this team yet.</p>
                  {:else}
                    <ul class="flex flex-col">
                      {#each team.members as member (member.userId)}
                        <li class="flex flex-wrap items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                          {@render personAvatar(member.displayName, member.avatarUrl, "h-7 w-7 text-xs")}
                          <span class="min-w-0 flex-1">
                            <span class="flex flex-wrap items-center gap-2">
                              <span class="truncate">{member.displayName}</span>
                              {#if member.isSelf}
                                <span class="badge badge-ghost badge-sm">You</span>
                              {/if}
                            </span>
                            <span class="block truncate text-xs text-base-content/50">@{member.username}</span>
                          </span>
                          {#if team.canManageMembers}
                            <select
                              class="select select-sm w-32"
                              value={member.role}
                              aria-label={`Role for ${member.displayName}`}
                              disabled={setTeamMemberRole.isPending}
                              onchange={(event) =>
                                void changeTeamMemberRole(team, member, event.currentTarget.value)}
                            >
                              <option value="member">Member</option>
                              <option value="maintainer">Maintainer</option>
                            </select>
                            <button
                              type="button"
                              class="btn btn-ghost btn-sm text-error"
                              disabled={removeTeamMember.isPending}
                              onclick={() => void removeTeamMemberFrom(team, member)}
                            >
                              Remove
                            </button>
                          {/if}
                        </li>
                      {/each}
                    </ul>
                  {/if}
                  {#if team.canManageMembers && availableTeamMembers(team).length > 0}
                    <form
                      class="flex flex-wrap items-center gap-2"
                      onsubmit={(event) => {
                        event.preventDefault()
                        void addTeamMemberTo(team)
                      }}
                    >
                      <select
                        class="select select-sm flex-1 sm:max-w-64"
                        value={selectedTeamMember[team.id] ?? ""}
                        aria-label={`Add a member to ${team.name}`}
                        disabled={addTeamMember.isPending}
                        onchange={(event) =>
                          (selectedTeamMember = { ...selectedTeamMember, [team.id]: event.currentTarget.value })}
                      >
                        <option value="">Choose a member…</option>
                        {#each availableTeamMembers(team) as member (member.userId)}
                          <option value={member.userId}>{member.displayName} (@{member.username})</option>
                        {/each}
                      </select>
                      <button
                        type="submit"
                        class="btn btn-sm"
                        disabled={addTeamMember.isPending || !(selectedTeamMember[team.id] ?? "")}
                      >
                        Add
                      </button>
                    </form>
                  {/if}
                </div>

                <div class="flex flex-col gap-2">
                  <h4 class="text-sm font-semibold">Calendar access</h4>
                  {#if team.grants.length === 0}
                    <p class="text-sm text-base-content/60">This team cannot see any calendars yet.</p>
                  {:else}
                    <ul class="flex flex-col">
                      {#each team.grants as grant (grant.calendarId)}
                        <li class="flex flex-wrap items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                          <span class="h-2.5 w-2.5 shrink-0 rounded-full" style={`background:${cssColor(grant.color)}`}
                          ></span>
                          <span class="min-w-0 flex-1 truncate">{grant.displayName}</span>
                          {#if team.canManageGrants}
                            <select
                              class="select select-sm w-28"
                              value={grant.permission}
                              aria-label={`Permission for ${grant.displayName}`}
                              disabled={grantCalendar.isPending}
                              onchange={(event) =>
                                void changeGrantPermission(team, grant, event.currentTarget.value)}
                            >
                              <option value="read">Read</option>
                              <option value="write">Write</option>
                            </select>
                            <button
                              type="button"
                              class="btn btn-ghost btn-sm text-error"
                              disabled={revokeCalendar.isPending}
                              onclick={() => void revokeGrant(team, grant)}
                            >
                              Revoke
                            </button>
                          {:else}
                            <span class="badge badge-ghost badge-sm">
                              {grant.permission === "write" ? "Write" : "Read"}
                            </span>
                          {/if}
                        </li>
                      {/each}
                    </ul>
                  {/if}
                  {#if team.canManageGrants && availableTeamCalendars(team).length > 0}
                    <form
                      class="flex flex-wrap items-center gap-2"
                      onsubmit={(event) => {
                        event.preventDefault()
                        void grantCalendarTo(team)
                      }}
                    >
                      <select
                        class="select select-sm flex-1 sm:max-w-64"
                        value={selectedTeamCalendar[team.id] ?? ""}
                        aria-label={`Add a calendar to ${team.name}`}
                        disabled={grantCalendar.isPending}
                        onchange={(event) =>
                          (selectedTeamCalendar = { ...selectedTeamCalendar, [team.id]: event.currentTarget.value })}
                      >
                        <option value="">Choose a calendar…</option>
                        {#each availableTeamCalendars(team) as calendar (calendar.id)}
                          <option value={calendar.id}>{calendar.displayName}</option>
                        {/each}
                      </select>
                      <select
                        class="select select-sm w-28"
                        value={selectedGrantPermission[team.id] ?? "read"}
                        aria-label="Permission"
                        disabled={grantCalendar.isPending}
                        onchange={(event) =>
                          (selectedGrantPermission = {
                            ...selectedGrantPermission,
                            [team.id]: event.currentTarget.value,
                          })}
                      >
                        <option value="read">Read</option>
                        <option value="write">Write</option>
                      </select>
                      <button
                        type="submit"
                        class="btn btn-sm"
                        disabled={grantCalendar.isPending || !(selectedTeamCalendar[team.id] ?? "")}
                      >
                        Grant
                      </button>
                    </form>
                  {/if}
                </div>
              </div>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
    {#if teamError}
      <p class="text-error text-sm">{teamError}</p>
    {/if}
  </section>

  {#if canManageMembers}
    <section class="flex flex-col gap-3" aria-labelledby="settings-invitations">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 id="settings-invitations" class="text-lg font-semibold">Pending invitations</h2>
        <span class="settings-hint">{invitations.length}</span>
      </div>
      <div class="overflow-hidden rounded-box border border-base-300 bg-base-200">
        {#if invitations.length === 0}
          <p class="p-4 text-sm text-base-content/60">No pending invitations.</p>
        {:else}
          <ul class="flex flex-col">
            {#each invitations as invitation (invitation.id)}
              <li class="flex flex-wrap items-center gap-3 border-b border-base-300 p-3 last:border-b-0">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="truncate font-medium">
                      {invitation.displayName ?? invitation.username ?? invitation.email ?? "Invitation"}
                    </span>
                    <span class="badge badge-ghost badge-sm">{organizationRoleLabel(invitation.role)}</span>
                  </div>
                  <div class="truncate text-xs text-base-content/50">
                    {#if invitation.username}
                      @{invitation.username}
                    {:else}
                      {invitation.email}
                    {/if}
                    · Expires {formatDate(invitation.expiresAt)}
                  </div>
                </div>
                <button
                  type="button"
                  class="btn btn-ghost btn-sm text-error"
                  disabled={revokeInvitation.isPending}
                  onclick={() => void revoke(invitation)}
                >
                  Revoke
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
      {#if invitationError}
        <p class="text-error text-sm">{invitationError}</p>
      {/if}
    </section>
  {/if}

  {#if canManageOwners}
    <section class="flex flex-col gap-3" aria-labelledby="settings-danger">
      <h2 id="settings-danger" class="text-lg font-semibold text-error">Danger zone</h2>
      <div
        class="flex flex-wrap items-center justify-between gap-3 rounded-box border border-error/40 bg-base-200 p-4"
      >
        <div class="min-w-0">
          <p class="font-medium">Delete this organization</p>
          <p class="settings-hint">
            Members and invitations are removed, and the organization page stops working. This cannot be undone.
          </p>
        </div>
        <button
          type="button"
          class="btn btn-error btn-sm"
          onclick={() => {
            deleteError = ""
            deleteOpen = true
          }}
        >
          Delete organization
        </button>
      </div>
    </section>
  {/if}
</div>

<dialog class="modal" bind:this={deleteDialog} onclose={() => (deleteOpen = false)}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">Delete {org.displayName}?</h3>
    <p class="py-2 text-base-content/70">
      Members and invitations are removed, and the organization page stops working. This cannot be undone.
    </p>
    {#if deleteError}
      <p class="text-error text-sm">{deleteError}</p>
    {/if}
    <div class="modal-action">
      <button type="button" class="btn btn-ghost" disabled={deleting} onclick={() => (deleteOpen = false)}>
        Cancel
      </button>
      <button type="button" class="btn btn-error" disabled={deleting} onclick={() => void confirmDelete()}>
        {deleting ? "Deleting…" : "Delete organization"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<dialog class="modal" bind:this={createTeamDialog} onclose={() => (createTeamOpen = false)}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">New team</h3>
    <p class="py-2 text-base-content/70">
      Teams group members so calendars can be shared with everyone in them.
    </p>
    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        void addTeam()
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Name</legend>
        <input
          class="input w-full"
          bind:value={newTeamName}
          required
          maxlength="80"
          placeholder="Design"
          disabled={createTeam.isPending}
        />
        {#if fieldError(createTeam.error, "name")}
          <p class="label text-error">{fieldError(createTeam.error, "name")}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Slug</legend>
        <input
          class="input w-full"
          bind:value={newTeamSlug}
          required
          placeholder="design"
          autocomplete="off"
          disabled={createTeam.isPending}
        />
        <p class="label">Lowercase letters, digits, dots, underscores, and hyphens.</p>
        {#if fieldError(createTeam.error, "slug")}
          <p class="label text-error">{fieldError(createTeam.error, "slug")}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Description</legend>
        <textarea class="textarea w-full" rows="2" bind:value={newTeamDescription} disabled={createTeam.isPending}
        ></textarea>
        {#if fieldError(createTeam.error, "description")}
          <p class="label text-error">{fieldError(createTeam.error, "description")}</p>
        {/if}
      </fieldset>
      {#if teamError}
        <p class="text-error text-sm">{teamError}</p>
      {:else if createTeam.error && !fieldError(createTeam.error, "slug") && !fieldError(createTeam.error, "name")}
        <p class="text-error text-sm">{actionMessage(createTeam.error)}</p>
      {/if}
      <div class="modal-action">
        <button type="button" class="btn btn-ghost" disabled={createTeam.isPending} onclick={() => (createTeamOpen = false)}>
          Cancel
        </button>
        <button type="submit" class="btn btn-primary" disabled={createTeam.isPending}>
          {createTeam.isPending ? "Creating…" : "Create team"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<dialog class="modal" bind:this={deleteTeamDialog} onclose={() => (deleteTeamOpen = false)}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">Delete {deletingTeam?.name}?</h3>
    <p class="py-2 text-base-content/70">
      The team is removed and its members lose access granted through it. This cannot be undone.
    </p>
    {#if deleteTeamError}
      <p class="text-error text-sm">{deleteTeamError}</p>
    {/if}
    <div class="modal-action">
      <button
        type="button"
        class="btn btn-ghost"
        disabled={deleteTeamPending}
        onclick={() => (deleteTeamOpen = false)}
      >
        Cancel
      </button>
      <button type="button" class="btn btn-error" disabled={deleteTeamPending} onclick={() => void confirmDeleteTeam()}>
        {deleteTeamPending ? "Deleting…" : "Delete team"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
