<script lang="ts">
  import { useAction } from "@kolektiv/keel-svelte"
  import { untrack } from "svelte"
  import { actionMessage, fieldError } from "../errors"
  import type { AdminCreateGroupIn, AdminUpdateGroupIn, GroupSummary } from "../page-types"

  let {
    open = $bindable(false),
    group = null,
  }: {
    open: boolean
    group: GroupSummary | null
  } = $props()

  const createGroup = useAction<AdminCreateGroupIn, GroupSummary>("kalendee.adminCreateGroup")
  const updateGroup = useAction<AdminUpdateGroupIn, GroupSummary>("kalendee.adminUpdateGroup")

  let dialog = $state<HTMLDialogElement | undefined>()
  let loadedFor = $state("")
  let name = $state("")
  let unlimited = $state(true)
  let quotaMiB = $state("")
  let initialQuotaMiB = $state("")
  let initialUnlimited = $state(true)
  let localError = $state("")

  const pending = $derived(createGroup.isPending || updateGroup.isPending)
  const nameError = $derived(fieldError(createGroup.error, "name") ?? fieldError(updateGroup.error, "name"))
  const quotaError = $derived(
    fieldError(createGroup.error, "storageQuotaBytes") ?? fieldError(updateGroup.error, "storageQuotaBytes"),
  )
  const genericError = $derived(
    (Boolean(createGroup.error) || Boolean(updateGroup.error)) &&
      !nameError &&
      !quotaError &&
      localError === "",
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
    const key = group?.id ?? "new"
    if (loadedFor === key) return
    loadedFor = key
    untrack(() => {
      createGroup.reset()
      updateGroup.reset()
      localError = ""
      name = group?.name ?? ""
      unlimited = group ? group.storageQuotaBytes === null : true
      initialUnlimited = unlimited
      initialQuotaMiB = group?.storageQuotaBytes != null ? formatQuotaInput(group.storageQuotaBytes) : ""
      quotaMiB = initialQuotaMiB
    })
  })

  function formatQuotaInput(bytes: number): string {
    const mib = bytes / (1024 * 1024)
    return Number.isInteger(mib) ? String(mib) : mib.toFixed(2).replace(/\.?0+$/, "")
  }

  function parseQuota(): number | null {
    if (quotaMiB.trim() === "") return null
    const value = Number(quotaMiB)
    if (!Number.isFinite(value) || value < 0) return null
    return Math.round(value * 1024 * 1024)
  }

  async function save() {
    if (pending) return
    const cleanName = name.trim()
    if (!group && cleanName === "") {
      localError = "Enter a group name."
      return
    }
    const quota = unlimited ? null : parseQuota()
    if (!unlimited && quota === null) {
      localError = "Enter a quota in MiB, or mark the group as unlimited."
      return
    }
    localError = ""
    const quotaChanged = unlimited !== initialUnlimited || (!unlimited && quotaMiB !== initialQuotaMiB)
    try {
      if (group) {
        await updateGroup.mutateAsync({
          groupId: group.id,
          name: group.isSystem ? null : cleanName,
          storageQuotaBytes: quotaChanged && !unlimited ? quota : null,
          clearQuota: quotaChanged && unlimited,
        })
      } else {
        await createGroup.mutateAsync({ name: cleanName, storageQuotaBytes: quota })
      }
      close()
    } catch {
      // The action error renders below.
    }
  }

  function close() {
    open = false
    loadedFor = ""
    name = ""
    unlimited = true
    quotaMiB = ""
    initialQuotaMiB = ""
    initialUnlimited = true
    localError = ""
  }
</script>

<dialog class="modal" bind:this={dialog} onclose={close}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">{group ? `Edit ${group.name}` : "New group"}</h3>
    <p class="py-2 text-base-content/70">
      {group ? "Rename the group or change its storage quota." : "Members share the group's storage quota."}
    </p>

    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        void save()
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Name</legend>
        <input
          id="admin-group-name"
          class="input w-full"
          bind:value={name}
          required
          maxlength="64"
          disabled={pending || group?.isSystem === true}
        />
        {#if group?.isSystem}
          <p class="label">System groups cannot be renamed.</p>
        {/if}
        {#if nameError}
          <p class="label text-error">{nameError}</p>
        {/if}
      </fieldset>

      <fieldset class="fieldset">
        <legend class="fieldset-legend">Storage quota</legend>
        <div class="flex items-center gap-3">
          <input
            id="admin-group-quota"
            class="input w-full"
            type="text"
            inputmode="decimal"
            bind:value={quotaMiB}
            placeholder="Unlimited"
            disabled={pending || unlimited}
          />
          <span class="whitespace-nowrap text-sm text-base-content/60">MiB</span>
        </div>
        <label class="label cursor-pointer justify-start gap-3">
          <input type="checkbox" class="toggle toggle-sm" bind:checked={unlimited} disabled={pending} />
          <span class="label-text">Unlimited</span>
        </label>
        {#if quotaError}
          <p class="label text-error">{quotaError}</p>
        {/if}
      </fieldset>

      {#if localError}
        <p class="text-error text-sm">{localError}</p>
      {/if}
      {#if genericError}
        <p class="text-error text-sm">{actionMessage(createGroup.error) || actionMessage(updateGroup.error)}</p>
      {/if}

      <div class="modal-action">
        <button type="button" class="btn btn-ghost" disabled={pending} onclick={close}>Cancel</button>
        <button type="submit" class="btn btn-primary" disabled={pending}>
          {pending ? "Saving…" : group ? "Save" : "Create"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
