<script lang="ts">
  import { Head, Link, page, useAction } from "@kolektiv/keel-svelte"
  import { actionMessage } from "../../../lib/errors"
  import type { PublicRsvpIn, RsvpByTokenIn, RsvpOut, RsvpPage } from "../../../lib/page-types"

  const ctx = page<RsvpPage>()
  const byToken = useAction<RsvpByTokenIn, RsvpOut>("kalendee.rsvpByToken")
  const publicRsvp = useAction<PublicRsvpIn, RsvpOut>("kalendee.publicRsvp")

  let name = $state("")
  let email = $state("")
  let respondedStatus = $state("")

  const pending = $derived(byToken.isPending || publicRsvp.isPending)
  const error = $derived(actionMessage(byToken.error) || actionMessage(publicRsvp.error))
  const usesToken = $derived(Boolean(ctx.data.token))
  const needsName = $derived(!usesToken && ctx.data.requiresName)
  const viewerName = $derived(ctx.data.viewer?.displayName ?? "")
  const canRespond = $derived(usesToken || (needsName ? name.trim() !== "" : viewerName !== ""))
  const responded = $derived(respondedStatus !== "" || ["yes", "no", "maybe"].includes(ctx.data.status ?? ""))
  const currentStatus = $derived(respondedStatus !== "" ? respondedStatus : (ctx.data.status ?? ""))

  function rsvpLabel(status: string): string {
    switch (status) {
      case "yes":
        return "Yes"
      case "no":
        return "No"
      case "maybe":
        return "Maybe"
      case "invited":
        return "Invited"
      default:
        return status
    }
  }

  async function respond(status: string) {
    if (pending || !canRespond) return
    try {
      if (ctx.data.token) {
        const result = await byToken.mutateAsync({ token: ctx.data.token, status })
        respondedStatus = result.status
      } else {
        const result = await publicRsvp.mutateAsync({
          eventId: ctx.data.eventId,
          name: needsName ? name.trim() : viewerName,
          email: needsName ? (email.trim() === "" ? null : email.trim()) : (ctx.data.viewer?.email ?? null),
          status,
        })
        respondedStatus = result.status
      }
    } catch {
      // Errors render through the action state.
    }
  }
</script>

<Head />

<div class="hero min-h-full">
  <div class="hero-content w-full">
    <div class="card bg-base-200 card-border w-full max-w-md">
      <div class="card-body">
        {#if ctx.data.valid}
          <h1 class="card-title text-2xl">{ctx.data.title ?? "Event invitation"}</h1>
          {#if ctx.data.whenText}
            <p class="text-base-content/70">{ctx.data.whenText}</p>
          {/if}
          {#if ctx.data.calendarName}
            <p class="text-sm text-base-content/60">{ctx.data.calendarName}</p>
          {/if}

          {#if needsName}
            <fieldset class="fieldset">
              <legend class="fieldset-legend">Your name</legend>
              <input
                id="rsvp-name"
                class="input w-full"
                bind:value={name}
                required
                placeholder="Your name"
                autocomplete="name"
                disabled={pending}
              />
            </fieldset>
            <fieldset class="fieldset">
              <legend class="fieldset-legend">Email (optional)</legend>
              <input
                id="rsvp-email"
                class="input w-full"
                type="email"
                bind:value={email}
                placeholder="you@example.com"
                autocomplete="email"
                disabled={pending}
              />
            </fieldset>
          {/if}

          <p class="text-sm font-medium">Will you attend?</p>
          <div class="flex flex-wrap gap-2">
            <button type="button" class="btn btn-success" disabled={pending || !canRespond} onclick={() => void respond("yes")}>
              Yes
            </button>
            <button type="button" class="btn btn-error" disabled={pending || !canRespond} onclick={() => void respond("no")}>
              No
            </button>
            <button type="button" class="btn btn-warning" disabled={pending || !canRespond} onclick={() => void respond("maybe")}>
              Maybe
            </button>
          </div>

          {#if pending}
            <p class="text-sm text-base-content/60">Sending…</p>
          {/if}
          {#if responded}
            <p class="text-success">Thanks — your response: {rsvpLabel(currentStatus)}</p>
          {/if}
          {#if error}
            <p class="text-error text-sm">{error}</p>
          {/if}
        {:else}
          <h1 class="card-title text-2xl">Invitation unavailable</h1>
          <p class="text-base-content/70">This invitation link is invalid or has expired.</p>
          <Link href="/" class="btn btn-primary">Go to Kalendee</Link>
        {/if}
      </div>
    </div>
  </div>
</div>
