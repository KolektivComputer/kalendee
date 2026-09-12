<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import { cssColor } from "../../../lib/colors"
  import { actionMessage, fieldError } from "../../../lib/errors"
  import { organizationRoleBadge, organizationRoleLabel } from "../../../lib/organizations"
  import type {
    CreateOrganizationIn,
    OrganizationSummary,
    PublicDirectoryPage,
    UpdateOrganizationIn,
  } from "../../../lib/page-types"

  const ctx = page<PublicDirectoryPage>()
  const createOrganization = useAction<CreateOrganizationIn, OrganizationSummary>("kalendee.createOrganization")
  const updateOrganization = useAction<UpdateOrganizationIn, OrganizationSummary>("kalendee.updateOrganization")

  let createOpen = $state(false)
  let createDialog = $state<HTMLDialogElement | undefined>()
  let slug = $state("")
  let displayName = $state("")
  let description = $state("")
  let visibility = $state("private")
  let localError = $state("")

  const createPending = $derived(createOrganization.isPending || updateOrganization.isPending)
  const slugError = $derived(fieldError(createOrganization.error, "slug"))

  $effect(() => {
    if (!createDialog) return
    if (createOpen && !createDialog.open) createDialog.showModal()
    if (!createOpen && createDialog.open) createDialog.close()
  })

  function initials(name: string): string {
    const parts = name.trim().split(/\s+/).filter(Boolean)
    if (parts.length === 0) return "?"
    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("")
  }

  function countLabel(count: number, singular: string, plural: string): string {
    return `${count} ${count === 1 ? singular : plural}`
  }

  function openCreate() {
    slug = ""
    displayName = ""
    description = ""
    visibility = "private"
    localError = ""
    createOrganization.reset()
    updateOrganization.reset()
    createOpen = true
  }

  async function createOrg() {
    if (createPending) return
    const cleanSlug = slug.trim().toLowerCase()
    const cleanName = displayName.trim()
    if (cleanSlug === "") {
      localError = "Enter a slug."
      return
    }
    if (cleanName === "") {
      localError = "Enter a name."
      return
    }
    localError = ""
    try {
      const created = await createOrganization.mutateAsync({
        slug: cleanSlug,
        displayName: cleanName,
        description: description.trim() === "" ? null : description.trim(),
      })
      if (visibility === "public") {
        await updateOrganization.mutateAsync({
          organizationId: created.id,
          displayName: null,
          description: null,
          visibility: "public",
        })
      }
      createOpen = false
      void router.visit(`/o/${created.slug}`)
    } catch {
      // Errors render from the action state inside the dialog.
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

<div class="mx-auto flex w-full max-w-4xl flex-col gap-8 p-4 sm:p-6">
  <div class="flex flex-wrap items-start justify-between gap-3">
    <div class="min-w-0">
      <h1 class="text-2xl font-semibold">Directory</h1>
      <p class="settings-hint">Public organizations, people, and calendars on this server.</p>
    </div>
    {#if ctx.data.viewer}
      <button type="button" class="btn btn-primary btn-sm" onclick={openCreate}>New organization</button>
    {/if}
  </div>

  <section class="flex flex-col gap-3" aria-labelledby="directory-orgs">
    <div class="flex items-center justify-between gap-2">
      <h2 id="directory-orgs" class="text-lg font-semibold">Organizations</h2>
      <span class="settings-hint">{countLabel(ctx.data.orgs.length, "organization", "organizations")}</span>
    </div>
    {#if ctx.data.orgs.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        {#if ctx.data.viewer}
          No public organizations yet. Create one with the button above, or switch an existing organization to public
          in its settings.
        {:else}
          No public organizations yet.
        {/if}
      </div>
    {:else}
      <ul class="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {#each ctx.data.orgs as org (org.id)}
          <li>
            <Link
              href={`/o/${org.slug}`}
              class="flex h-full items-start gap-3 rounded-box border border-base-300 bg-base-200 p-4 transition-colors hover:bg-base-300"
            >
              {@render entityAvatar(org.displayName, org.avatarUrl, "h-10 w-10 text-sm")}
              <span class="min-w-0 flex-1">
                <span class="flex flex-wrap items-center gap-2">
                  <span class="truncate font-medium">{org.displayName}</span>
                  {#if org.viewerRole}
                    <span class="badge badge-sm shrink-0 {organizationRoleBadge(org.viewerRole)}">
                      {organizationRoleLabel(org.viewerRole)}
                    </span>
                  {/if}
                </span>
                <span class="block truncate text-xs text-base-content/50">@{org.slug}</span>
                {#if org.description}
                  <span class="mt-1 line-clamp-2 block text-sm text-base-content/70">{org.description}</span>
                {/if}
                <span class="mt-1 block text-xs text-base-content/50">
                  {countLabel(org.memberCount, "member", "members")}
                </span>
              </span>
            </Link>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="directory-people">
    <div class="flex items-center justify-between gap-2">
      <h2 id="directory-people" class="text-lg font-semibold">People</h2>
      <span class="settings-hint">{countLabel(ctx.data.users.length, "person", "people")}</span>
    </div>
    {#if ctx.data.users.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        No public profiles yet. People with public calendars will show up here.
      </div>
    {:else}
      <ul class="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {#each ctx.data.users as user (user.userId)}
          <li>
            <Link
              href={`/u/${user.username}`}
              class="flex h-full items-center gap-3 rounded-box border border-base-300 bg-base-200 p-4 transition-colors hover:bg-base-300"
            >
              {@render entityAvatar(user.displayName, user.avatarUrl, "h-10 w-10 text-sm")}
              <span class="min-w-0 flex-1">
                <span class="block truncate font-medium">{user.displayName}</span>
                <span class="block truncate text-xs text-base-content/50">@{user.username}</span>
              </span>
            </Link>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="directory-calendars">
    <div class="flex items-center justify-between gap-2">
      <h2 id="directory-calendars" class="text-lg font-semibold">Public calendars</h2>
      <span class="settings-hint">{countLabel(ctx.data.calendars.length, "calendar", "calendars")}</span>
    </div>
    {#if ctx.data.calendars.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        No public calendars yet. Calendars appear here when someone shares a public link.
      </div>
    {:else}
      <ul class="flex flex-col gap-2">
        {#each ctx.data.calendars as calendar (calendar.id)}
          <li>
            <Link
              href={`/c/${calendar.token}`}
              class="flex items-center gap-3 rounded-box border border-base-300 bg-base-200 p-3 transition-colors hover:bg-base-300"
            >
              <span class="h-3 w-3 shrink-0 rounded-full" style={`background:${cssColor(calendar.color)}`}></span>
              <span class="min-w-0 flex-1">
                <span class="block truncate font-medium">{calendar.displayName}</span>
                <span class="block truncate text-xs text-base-content/50">
                  {calendar.organizationName ?? calendar.ownerName}
                  {#if calendar.organizationName && calendar.ownerUsername}
                    · @{calendar.ownerUsername}
                  {/if}
                </span>
              </span>
            </Link>
          </li>
        {/each}
      </ul>
    {/if}
  </section>
</div>

<dialog class="modal" bind:this={createDialog} onclose={() => (createOpen = false)}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">New organization</h3>
    <p class="py-2 text-base-content/70">
      Organizations group calendars and members under a shared page. You become the owner.
    </p>
    <form
      class="flex flex-col gap-4"
      onsubmit={(event) => {
        event.preventDefault()
        void createOrg()
      }}
    >
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Slug</legend>
        <input
          id="org-slug"
          class="input w-full"
          bind:value={slug}
          required
          maxlength="32"
          placeholder="acme"
          autocomplete="off"
          disabled={createPending}
        />
        <p class="label">The page lives at /o/&lt;slug&gt;. Lowercase letters, digits, dots, dashes, and underscores.</p>
        {#if slugError}
          <p class="label text-error">{slugError}</p>
        {/if}
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Name</legend>
        <input
          id="org-name"
          class="input w-full"
          bind:value={displayName}
          required
          maxlength="80"
          disabled={createPending}
        />
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Description</legend>
        <textarea class="textarea w-full" rows="3" bind:value={description} disabled={createPending}></textarea>
      </fieldset>
      <fieldset class="fieldset">
        <legend class="fieldset-legend">Visibility</legend>
        <label class="label cursor-pointer justify-start gap-3">
          <input
            type="radio"
            class="radio radio-sm"
            name="org-visibility"
            value="private"
            checked={visibility === "private"}
            disabled={createPending}
            onchange={() => (visibility = "private")}
          />
          <span class="label-text">Private — only members and invitees can see the page</span>
        </label>
        <label class="label cursor-pointer justify-start gap-3">
          <input
            type="radio"
            class="radio radio-sm"
            name="org-visibility"
            value="public"
            checked={visibility === "public"}
            disabled={createPending}
            onchange={() => (visibility = "public")}
          />
          <span class="label-text">Public — listed in the directory</span>
        </label>
      </fieldset>

      {#if localError}
        <p class="text-error text-sm">{localError}</p>
      {:else if createOrganization.error}
        <p class="text-error text-sm">{actionMessage(createOrganization.error)}</p>
      {:else if updateOrganization.error}
        <p class="text-error text-sm">{actionMessage(updateOrganization.error)}</p>
      {/if}

      <div class="modal-action">
        <button type="button" class="btn btn-ghost" disabled={createPending} onclick={() => (createOpen = false)}>
          Cancel
        </button>
        <button type="submit" class="btn btn-primary" disabled={createPending}>
          {createPending ? "Creating…" : "Create organization"}
        </button>
      </div>
    </form>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>
