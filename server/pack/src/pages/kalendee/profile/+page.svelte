<script lang="ts">
  import { Head, Link, page } from "@kolektiv/keel-svelte"
  import { cssColor } from "../../../lib/colors"
  import { organizationRoleBadge, organizationRoleLabel } from "../../../lib/organizations"
  import type { PublicProfilePage } from "../../../lib/page-types"

  const ctx = page<PublicProfilePage>()

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
</script>

<Head />

<div class="mx-auto flex w-full max-w-3xl flex-col gap-8 p-4 sm:p-6">
  <header class="flex flex-wrap items-center gap-4">
    {#if ctx.data.avatarUrl}
      <img class="h-16 w-16 shrink-0 rounded-full object-cover" src={ctx.data.avatarUrl} alt="" />
    {:else}
      <span
        class="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-base-content/20 text-xl font-semibold"
        aria-hidden="true"
      >
        {initials(ctx.data.displayName)}
      </span>
    {/if}
    <div class="min-w-0 flex-1">
      <div class="flex flex-wrap items-center gap-2">
        <h1 class="text-2xl font-semibold">{ctx.data.displayName}</h1>
        {#if ctx.data.isSelf}
          <span class="badge badge-primary badge-sm">This is you</span>
        {/if}
      </div>
      <p class="text-sm text-base-content/60">@{ctx.data.username}</p>
      {#if ctx.data.isSelf}
        <p class="mt-1 text-sm text-base-content/60">
          Publish a calendar from its Share menu to show it here. <Link href="/settings" class="link link-hover">Settings</Link>
        </p>
      {/if}
    </div>
  </header>

  <section class="flex flex-col gap-3" aria-labelledby="profile-calendars">
    <div class="flex items-center justify-between gap-2">
      <h2 id="profile-calendars" class="text-lg font-semibold">Public calendars</h2>
      <span class="settings-hint">{countLabel(ctx.data.calendars.length, "calendar", "calendars")}</span>
    </div>
    {#if ctx.data.calendars.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        {ctx.data.isSelf
          ? "You have no public calendars yet."
          : `${ctx.data.displayName} has no public calendars.`}
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
                  {#if calendar.organizationName}
                    <span class="mt-0.5 block text-xs text-base-content/50">{calendar.organizationName}</span>
                  {/if}
                </span>
              </Link>
            {:else}
              <div class="flex items-center gap-3 rounded-box border border-base-300 bg-base-200 p-3">
                <span class="h-3 w-3 shrink-0 rounded-full" style={`background:${cssColor(calendar.color)}`}></span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate font-medium">{calendar.displayName}</span>
                  {#if calendar.organizationName}
                    <span class="mt-0.5 block text-xs text-base-content/50">{calendar.organizationName}</span>
                  {/if}
                </span>
              </div>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class="flex flex-col gap-3" aria-labelledby="profile-orgs">
    <div class="flex items-center justify-between gap-2">
      <h2 id="profile-orgs" class="text-lg font-semibold">Organizations</h2>
      <span class="settings-hint">{countLabel(ctx.data.organizations.length, "organization", "organizations")}</span>
    </div>
    {#if ctx.data.organizations.length === 0}
      <div class="rounded-box border border-base-300 bg-base-200 p-6 text-sm text-base-content/60">
        {ctx.data.isSelf
          ? "You are not a member of any public organizations."
          : `${ctx.data.displayName} is not a member of any public organizations.`}
      </div>
    {:else}
      <ul class="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {#each ctx.data.organizations as org (org.id)}
          <li>
            <Link
              href={`/o/${org.slug}`}
              class="flex h-full items-start gap-3 rounded-box border border-base-300 bg-base-200 p-4 transition-colors hover:bg-base-300"
            >
              <span
                class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-base-content/20 text-sm font-semibold"
                aria-hidden="true"
              >
                {initials(org.displayName)}
              </span>
              <span class="min-w-0 flex-1">
                <span class="flex flex-wrap items-center gap-2">
                  <span class="truncate font-medium">{org.displayName}</span>
                  {#if org.role}
                    <span class="badge badge-sm shrink-0 {organizationRoleBadge(org.role)}">
                      {organizationRoleLabel(org.role)}
                    </span>
                  {/if}
                </span>
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
</div>
