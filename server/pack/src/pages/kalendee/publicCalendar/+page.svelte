<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import ChevronLeft from "@lucide/svelte/icons/chevron-left"
  import ChevronRight from "@lucide/svelte/icons/chevron-right"
  import Rss from "@lucide/svelte/icons/rss"
  import MonthGrid from "../../../lib/components/MonthGrid.svelte"
  import RequestSlotDialog from "../../../lib/components/RequestSlotDialog.svelte"
  import WeekGrid from "../../../lib/components/WeekGrid.svelte"
  import { cssColor } from "../../../lib/colors"
  import type {
    FollowCalendarIn,
    FollowOut,
    PublicCalendarPage,
    UnfollowCalendarIn,
  } from "../../../lib/page-types"
  import { datesBetween } from "../../../lib/time"

  const ctx = page<PublicCalendarPage>()
  const followCalendar = useAction<FollowCalendarIn, FollowOut>("kalendee.followCalendar")
  const unfollowCalendar = useAction<UnfollowCalendarIn, FollowOut>("kalendee.unfollowCalendar")

  const viewDates = $derived(datesBetween(ctx.data.gridStart, ctx.data.gridEnd))
  const timedDates = $derived(ctx.data.view === "day" ? [ctx.data.date] : viewDates.slice(0, 7))
  const calendars = $derived([ctx.data.calendar])
  const isOwner = $derived(ctx.data.calendar.permission === "owner")
  const canRequest = $derived(
    ctx.data.calendar.requestsEnabled &&
      (ctx.data.viewer != null || ctx.data.calendar.effectiveAccessMode === "public"),
  )

  let requestOpen = $state(false)

  function href(view: string, date: string): string {
    const params = new URLSearchParams({ view, date, tz: ctx.data.timeZone })
    return `/c/${ctx.data.token}?${params.toString()}`
  }

  function openDay(date: string) {
    void router.visit(href("day", date))
  }

  function onFollow() {
    void followCalendar.mutateAsync({ token: ctx.data.token }).catch(() => undefined)
  }

  function onUnfollow() {
    void unfollowCalendar.mutateAsync({ calendarId: ctx.data.calendar.id }).catch(() => undefined)
  }
</script>

<Head />

<div class="flex h-full min-h-0 flex-col overflow-hidden">
  <header class="flex shrink-0 flex-wrap items-start gap-3 border-b border-base-300 px-3 py-3">
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-2">
        <span class="h-3 w-3 shrink-0 rounded-full" style={`background:${cssColor(ctx.data.calendar.color)}`}></span>
        <h1 class="truncate text-xl font-semibold tracking-tight">{ctx.data.calendar.displayName}</h1>
      </div>
      {#if ctx.data.calendar.description}
        <p class="mt-1 text-sm text-base-content/70">{ctx.data.calendar.description}</p>
      {/if}
      {#if ctx.data.calendar.ownerName}
        <p class="mt-1 text-xs text-base-content/60">by {ctx.data.calendar.ownerName}</p>
      {/if}
      {#if followCalendar.error || unfollowCalendar.error}
        <p class="mt-1 text-error text-sm">Could not update follow state. Try again.</p>
      {/if}
    </div>
    <div class="flex flex-wrap items-center gap-2">
      {#if ctx.data.viewer}
        {#if isOwner}
          <span class="badge badge-ghost">Your calendar</span>
        {:else if ctx.data.following}
          <button type="button" class="btn btn-sm" disabled={unfollowCalendar.isPending} onclick={onUnfollow}>
            {unfollowCalendar.isPending ? "Unfollowing…" : "Following — Unfollow"}
          </button>
        {:else}
          <button type="button" class="btn btn-sm btn-primary" disabled={followCalendar.isPending} onclick={onFollow}>
            {followCalendar.isPending ? "Following…" : "Follow"}
          </button>
        {/if}
      {:else}
        <Link href="/login" class="btn btn-sm btn-primary">Sign in to follow</Link>
      {/if}
      <a href={ctx.data.rssPath} class="btn btn-sm btn-ghost gap-1.5" title="RSS feed">
        <Rss class="h-4 w-4" />
        RSS feed
      </a>
    </div>
  </header>

  <div class="flex shrink-0 flex-wrap items-center gap-3 border-b border-base-300 px-3 py-2">
    <div class="join">
      <Link class="btn join-item" href={href(ctx.data.view, ctx.data.previousDate)} aria-label="Previous">
        <ChevronLeft class="h-4 w-4" />
      </Link>
      <Link class="btn join-item" href={href(ctx.data.view, ctx.data.today)}>Today</Link>
      <Link class="btn join-item" href={href(ctx.data.view, ctx.data.nextDate)} aria-label="Next">
        <ChevronRight class="h-4 w-4" />
      </Link>
    </div>
    <h2 class="text-lg font-semibold tracking-tight">{ctx.data.label}</h2>
    <div role="tablist" class="tabs tabs-box ml-auto">
      <Link role="tab" class={ctx.data.view === "month" ? "tab tab-active" : "tab"} href={href("month", ctx.data.date)}>
        Month
      </Link>
      <Link role="tab" class={ctx.data.view === "week" ? "tab tab-active" : "tab"} href={href("week", ctx.data.date)}>
        Week
      </Link>
    </div>
  </div>

  {#if canRequest}
    <div class="flex shrink-0 items-center gap-3 border-b border-base-300 bg-base-200 px-3 py-1.5 text-sm">
      <span class="min-w-0 truncate text-base-content/80">
        {ctx.data.calendar.ownerName} accepts time requests
      </span>
      <button type="button" class="btn btn-primary btn-xs ml-auto" onclick={() => (requestOpen = true)}>
        Request a time…
      </button>
    </div>
  {/if}

  <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
    {#if ctx.data.view === "month"}
      <MonthGrid
        dates={viewDates}
        monthStart={ctx.data.date}
        timeZone={ctx.data.timeZone}
        calendars={calendars}
        events={ctx.data.events}
        today={ctx.data.today}
        readOnly
        onDraft={() => undefined}
        onSelect={() => undefined}
        onDay={openDay}
      />
    {:else}
      <WeekGrid
        dates={timedDates}
        timeZone={ctx.data.timeZone}
        calendars={calendars}
        events={ctx.data.events}
        readOnly
        onDraft={() => undefined}
        onMove={() => undefined}
        onSelect={() => undefined}
        onDelete={() => undefined}
      />
    {/if}
  </div>
</div>

<RequestSlotDialog
  bind:open={requestOpen}
  calendar={ctx.data.calendar}
  publicToken={ctx.data.token}
  anonymous={ctx.data.viewer == null}
/>
