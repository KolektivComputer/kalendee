<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage } from "../errors"
  import type {
    AdminGroupMemberSummary,
    AdminGroupMembersIn,
    AdminGroupMembersOut,
    AdminSetGroupMembersIn,
    AdminUserSummary,
    GroupSummary,
    Viewer,
  } from "../page-types"

  let {
    open = $bindable(false),
    group = null,
    users,
    viewer,
  }: {
    open: boolean
    group: GroupSummary | null
    users: AdminUserSummary[]
    viewer: Viewer
  } = $props()

  const loadMembers = useAction<AdminGroupMembersIn, AdminGroupMembersOut>("kalendee.adminGroupMembers", {
    reload: false,
  })
  const setMembers = useAction<AdminSetGroupMembersIn, AdminGroupMembersOut>("kalendee.adminSetGroupMembers")

  let dialog = $state<HTMLDialogElement | undefined>()
  let loadedFor = $state("")
  let members = $state<AdminGroupMemberSummary[]>([])
  let selected = $state<Record<string, boolean>>({})
  let search = $state("")
  let loadError = $state("")
  let saveError = $state("")
  let saving = $state(false)
  let busyUserId = $state("")

  const groupName = $derived(group?.name ?? "")
  const isDefault = $derived(groupName === "default")
  const isAdminGroup = $derived(groupName === "admin")
  const viewerSuperadmin = $derived(users.some((user) => user.id === viewer.id && user.superadmin))
  const memberIds = $derived(new Set(members.map((member) => member.userId)))
  const searchTerm = $derived(search.trim().toLowerCase())
  const visibleUsers = $derived(
    users.filter(
      (user) =>
        searchTerm === "" ||
        user.username.toLowerCase().includes(searchTerm) ||
        user.displayName.toLowerCase().includes(searchTerm),
    ),
  )
  const visibleMembers = $derived(
    members.filter(
      (member) =>
        searchTerm === "" ||
        member.username.toLowerCase().includes(searchTerm) ||
        member.displayName.toLowerCase().includes(searchTerm),
    ),
  )
  const grantCandidates = $derived(visibleUsers.filter((user) => !memberIds.has(user.id)))

  function isSuperadmin(userId: string): boolean {
    return users.some((user) => user.id === userId && user.superadmin)
  }

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })


  $effect(() => {
    if (!open) {
      loadedFor = ""
      members = []
      selected = {}
      search = ""
      loadError = ""
      saveError = ""
      return
    }
    if (!group) return
    const id = group.id
    if (loadedFor === id) return
    loadedFor = id
    if (group.name === "default") return
    untrack(() => {
      loadMembers.reset()
      setMembers.reset()
      members = []
      selected = {}
      search = ""
      loadError = ""
      saveError = ""
      saving = false
      busyUserId = ""
      void load(id)
    })
  })

  async function load(groupId: string) {
    try {
      const result = await loadMembers.mutateAsync({ groupId })
      members = result.members
      selected = Object.fromEntries(result.members.map((member) => [member.userId, true]))
      loadError = ""
    } catch {
      loadError = "Could not load group members."
    }
  }

  async function apply(userIds: string[], userId = "") {
    const target = group
    if (!target || saving) return
    saving = true
    busyUserId = userId
    saveError = ""
    try {
      const result = await setMembers.mutateAsync({ groupId: target.id, userIds })
      members = result.members
      selected = Object.fromEntries(result.members.map((member) => [member.userId, true]))
    } catch (error) {
      saveError = actionMessage(error)
    } finally {
      saving = false
      busyUserId = ""
    }
  }

  function revokeAdmin(member: AdminGroupMemberSummary) {
    void apply(
      members.filter((entry) => entry.userId !== member.userId).map((entry) => entry.userId),
      member.userId,
    )
  }

  function grantAdmin(user: AdminUserSummary) {
    void apply([...members.map((entry) => entry.userId), user.id], user.id)
  }

  function saveCustom() {
    void apply(users.filter((user) => selected[user.id]).map((user) => user.id))
  }

  function close() {
    open = false
    loadedFor = ""
    members = []
    selected = {}
    search = ""
    loadError = ""
    saveError = ""
    saving = false
    busyUserId = ""
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-lg">
    <h3 class="text-lg font-bold">Members{groupName ? ` of ${groupName}` : ""}</h3>

    {#if isDefault}
      <p class="py-2 text-base-content/70">
        The default group includes every user. Membership is implicit and cannot be edited.
      </p>
      <div class="modal-action">
        <button type="button" class="btn btn-ghost" onclick={close}>Done</button>
      </div>
    {:else}
      <p class="py-2 text-base-content/70">
        {isAdminGroup
          ? "The admin group mirrors the admin list. Granting admin adds a user here; revoking removes their admin access."
          : "Choose which users belong to this group."}
      </p>

      <input
        class="input w-full"
        type="search"
        placeholder="Search users"
        aria-label="Search users"
        bind:value={search}
        disabled={saving}
      />

      {#if loadError}
        <div role="alert" class="alert alert-error mt-3">{loadError}</div>
      {:else if loadedFor === "" || loadMembers.isPending}
        <div class="flex justify-center py-6">
          <span class="loading loading-spinner"></span>
        </div>
      {:else if isAdminGroup}
        <div class="mt-3 flex flex-col gap-4">
          <section class="flex flex-col gap-1">
            <h4 class="text-sm font-semibold">Admins</h4>
            {#if visibleMembers.length === 0}
              <p class="text-sm text-base-content/60">
                {members.length === 0 ? "No admins yet." : "No admins match the search."}
              </p>
            {:else}
              <ul class="flex flex-col">
                {#each visibleMembers as member (member.userId)}
                  <li class="flex items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                    <div class="min-w-0 flex-1">
                      <div class="truncate">
                        {member.displayName}
                        {#if isSuperadmin(member.userId)}
                          <span class="badge badge-primary badge-sm ml-1">Superadmin</span>
                        {/if}
                      </div>
                      <div class="truncate text-xs text-base-content/60">@{member.username}</div>
                    </div>
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-error"
                      disabled={saving || member.userId === viewer.id}
                      title={member.userId === viewer.id ? "You cannot remove your own admin access" : undefined}
                      onclick={() => revokeAdmin(member)}
                    >
                      {saving && busyUserId === member.userId ? "Revoking…" : "Revoke admin"}
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </section>

          <section class="flex flex-col gap-1">
            <h4 class="text-sm font-semibold">Grant admin</h4>
            {#if grantCandidates.length === 0}
              <p class="text-sm text-base-content/60">
                {visibleUsers.length === 0 ? "No users match the search." : "Everyone matching is already an admin."}
              </p>
            {:else}
              <ul class="flex flex-col">
                {#each grantCandidates as user (user.id)}
                  <li class="flex items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                    <div class="min-w-0 flex-1">
                      <div class="truncate">{user.displayName}</div>
                      <div class="truncate text-xs text-base-content/60">@{user.username}</div>
                    </div>
                    <button
                      type="button"
                      class="btn btn-sm"
                      disabled={saving}
                      onclick={() => grantAdmin(user)}
                    >
                      {saving && busyUserId === user.id ? "Granting…" : "Grant admin"}
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </section>

          {#if !viewerSuperadmin}
            <p class="settings-hint">Only a superadmin can revoke admin access.</p>
          {/if}
        </div>
      {:else}
        <ul class="mt-3 flex flex-col">
          {#each visibleUsers as user (user.id)}
            <li>
              <label class="flex cursor-pointer items-center gap-2 border-b border-base-300 py-2 last:border-b-0">
                <input
                  type="checkbox"
                  class="checkbox checkbox-sm"
                  checked={selected[user.id] ?? false}
                  disabled={saving}
                  onchange={(event) => (selected[user.id] = event.currentTarget.checked)}
                />
                <span class="min-w-0 flex-1 truncate">{user.displayName}</span>
                <span class="truncate text-xs text-base-content/60">@{user.username}</span>
              </label>
            </li>
          {/each}
          {#if visibleUsers.length === 0}
            <li class="py-3 text-sm text-base-content/60">No users match the search.</li>
          {/if}
        </ul>
      {/if}

      {#if saveError}
        <p class="mt-3 text-error text-sm">{saveError}</p>
      {/if}

      <div class="modal-action">
        <button type="button" class="btn btn-ghost" disabled={saving} onclick={close}>Close</button>
        {#if !isAdminGroup}
          <button
            type="button"
            class="btn btn-primary"
            disabled={saving || loadError !== "" || loadMembers.isPending}
            onclick={saveCustom}
          >
            {saving ? "Saving…" : "Save members"}
          </button>
        {/if}
      </div>
    {/if}
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
