<script lang="ts">
  import { Head, Link, page } from "@kolektiv/keel-svelte"
  import CalendarApp from "../../../lib/components/CalendarApp.svelte"
  import { viewerOrganizations } from "../../../lib/organizations"
  import type { HomePage } from "../../../lib/page-types"

  const ctx = page<HomePage>()
  const organizations = $derived(viewerOrganizations(ctx.shared?.organizations))
</script>

<Head />

{#if ctx.data.viewer}
  <div class="h-full min-h-0">
    <CalendarApp data={ctx.data} {organizations} />
  </div>
{:else}
  <div class="hero min-h-full">
    <div class="hero-content flex-col gap-10 lg:flex-row">
      <div class="max-w-xl">
        <h1 class="text-5xl font-semibold tracking-tight">See the week. Keep the calendar yours.</h1>
        <p class="py-6 text-base-content/70">
          Kalendee is a self-hosted calendar for people who want a first-party week view, not a rented slot in
          someone else's cloud.
        </p>
        <div class="flex flex-wrap gap-2">
          <Link href="/login" class="btn btn-primary">Sign in</Link>
          {#if ctx.data.registrationOpen}
            <Link href="/register" class="btn btn-ghost">Create account</Link>
          {/if}
        </div>
      </div>
      <div class="grid min-h-72 w-full max-w-lg grid-cols-7 overflow-hidden rounded-box border border-base-300 bg-base-200" aria-hidden="true">
        {#each ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as day, index}
          <div class="border-l border-base-300 p-2 text-xs text-base-content/40 first:border-l-0">
            {day}
            <strong class="mt-1 block text-base text-base-content/70">{7 + index}</strong>
            {#if index === 1}
              <div class="bg-primary/40 mt-8 h-8 rounded-field"></div>
            {/if}
            {#if index === 3}
              <div class="bg-secondary/40 mt-16 h-8 rounded-field"></div>
            {/if}
          </div>
        {/each}
      </div>
    </div>
  </div>
{/if}
