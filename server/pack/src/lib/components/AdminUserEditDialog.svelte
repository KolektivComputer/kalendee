<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage, fieldError } from "../errors"
  import type { AdminUpdateUserIn, AdminUserSummary } from "../page-types"

  let {
    open = $bindable(false),
    user = null,
    viewerId = "",
    viewerSuperadmin = false,
  }: {
    open: boolean
    user: AdminUserSummary | null
    viewerId: string
    viewerSuperadmin: boolean
  } = $props()

  const updateUser = useAction<AdminUpdateUserIn, AdminUserSummary>("kalendee.adminUpdateUser")

  let dialog = $state<HTMLDialogElement | undefined>()
  let loadedFor = $state("")
  let displayName = $state("")
  let email = $state("")
  let password = $state("")
  let admin = $state(false)

  const isSelf = $derived(user !== null && user.id === viewerId)
  const adminLocked = $derived(
    user !== null && (isSelf || (!viewerSuperadmin && (user.admin || user.superadmin))),
  )
  const genericError = $derived(
    Boolean(updateUser.error) &&
      !fieldError(updateUser.error, "displayName") &&
      !fieldError(updateUser.error, "email") &&
      !fieldError(updateUser.error, "password"),
  )

  $effect(() => {
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  })

  $effect(() => {
    if (!open) {
      loadedFor = ""
      return
    }
    if (!user) return
    if (loadedFor === user.id) return
    loadedFor = user.id
    untrack(() => {
      updateUser.reset()
      displayName = user.displayName
      email = user.email ?? ""
      password = ""
      admin = user.admin
    })
  })

  async function save() {
    const target = user
    if (!target || updateUser.isPending) return
    try {
      await updateUser.mutateAsync({
        userId: target.id,
        displayName: displayName.trim() || null,
        email: email.trim() || null,
        password: password.trim() || null,
        admin,
      })
      open = false
    } catch {
      // The action error renders below.
    }
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={() => (open = false)}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">Edit {user?.displayName ?? "user"}</h3>
    <p class="py-2 text-base-content/70">@{user?.username ?? ""}</p>

    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        void save()
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Display name</legend>
        <input
          id="admin-user-name"
          class="input w-full"
          bind:value={displayName}
          required
          maxlength="80"
          disabled={updateUser.isPending}
        />
        {#if fieldError(updateUser.error, "displayName")}
          <p class="label text-error">{fieldError(updateUser.error, "displayName")}</p>
        {/if}
      </fieldset>

      <fieldset class="fieldset">
        <legend class="fieldset-legend">Email</legend>
        <input
          id="admin-user-email"
          class="input w-full"
          type="email"
          bind:value={email}
          disabled={updateUser.isPending}
        />
        <p class="label">Leave blank to keep the current email.</p>
        {#if fieldError(updateUser.error, "email")}
          <p class="label text-error">{fieldError(updateUser.error, "email")}</p>
        {/if}
      </fieldset>

      <fieldset class="fieldset">
        <legend class="fieldset-legend">New password</legend>
        <input
          id="admin-user-password"
          class="input w-full"
          type="password"
          bind:value={password}
          autocomplete="new-password"
          placeholder="Leave blank to keep the current password"
          disabled={updateUser.isPending}
        />
        {#if fieldError(updateUser.error, "password")}
          <p class="label text-error">{fieldError(updateUser.error, "password")}</p>
        {/if}
      </fieldset>

      <label class="label cursor-pointer justify-start gap-3">
        <input
          type="checkbox"
          class="toggle toggle-sm"
          bind:checked={admin}
          disabled={adminLocked || updateUser.isPending}
        />
        <span class="label-text">Admin</span>
      </label>
      {#if adminLocked}
        <p class="settings-hint">
          {isSelf
            ? "You cannot change your own admin access."
            : "Only a superadmin can change this user's admin access."}
        </p>
      {/if}

      {#if genericError}
        <p class="text-error text-sm">{actionMessage(updateUser.error)}</p>
      {/if}

      <div class="modal-action">
        <button type="button" class="btn btn-ghost" disabled={updateUser.isPending} onclick={() => (open = false)}>
          Cancel
        </button>
        <button type="submit" class="btn btn-primary" disabled={updateUser.isPending}>
          {updateUser.isPending ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
