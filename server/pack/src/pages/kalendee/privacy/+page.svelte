<script lang="ts">
  import { Head, Link, page } from "@kolektiv/keel-svelte"
  import type { PrivacyPage } from "../../../lib/page-types"

  page<PrivacyPage>()
</script>

<Head />

<article class="mx-auto max-w-2xl px-6 py-12">
  <p class="mb-2 text-xs font-semibold tracking-[0.14em] text-base-content/45 uppercase">Kolektiv · Kalendee</p>
  <h1 class="mb-6 text-3xl font-semibold tracking-tight">Privacy Policy</h1>
  <div class="flex flex-col gap-4 text-sm leading-relaxed text-base-content/80">
    <p>
      Kalendee is a local-first calendar. Events you create on a device stay on that device until you choose to sign in
      and turn on account sync.
    </p>
    <h2 class="mt-2 text-base font-semibold text-base-content">What we store</h2>
    <p>
      If you create an account, this server stores your username, email, display name, calendars, events,
      connected-account metadata, and which devices have checked in. Passwords are hashed. Operators of a self-hosted
      instance are the controller for that data.
    </p>
    <h2 class="mt-2 text-base font-semibold text-base-content">Google Calendar</h2>
    <p>
      Connecting Google Calendar is optional. When you do, Kalendee reads calendars and events and mirrors them into
      your account as regular calendars. Nothing is written back to Google in M1.
    </p>
    <h2 class="mt-2 text-base font-semibold text-base-content">Subscriptions</h2>
    <p>
      ICS and webcal URLs you paste are fetched by this server. Do not subscribe to feeds that contain secrets you would
      not want stored with your calendars.
    </p>
  </div>
  <p class="mt-10 text-xs text-base-content/40">Last updated 14 September 2026. <Link href="/terms" class="link">Terms</Link></p>
</article>
