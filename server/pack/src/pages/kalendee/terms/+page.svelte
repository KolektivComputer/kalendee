<script lang="ts">
  import { Head, Link, page } from "@kolektiv/keel-svelte"
  import type { TermsPage } from "../../../lib/page-types"

  page<TermsPage>()
</script>

<Head />

<article class="mx-auto max-w-2xl px-6 py-12">
  <p class="mb-2 text-xs font-semibold tracking-[0.14em] text-base-content/45 uppercase">Kolektiv · Kalendee</p>
  <h1 class="mb-6 text-3xl font-semibold tracking-tight">Terms of Service</h1>
  <div class="flex flex-col gap-4 text-sm leading-relaxed text-base-content/80">
    <p>
      Kalendee is free software from Kolektiv Computer. Self-hosted servers are governed by the license in the
      repository. You may use Kalendee without an account; signing in is optional so web, desktop, and Android can share
      one week.
    </p>
    <h2 class="mt-2 text-base font-semibold text-base-content">Acceptable use</h2>
    <p>
      Do not use Kalendee to attack other systems, scrape private calendars you are not entitled to, or store material
      that is illegal where you live. Operators may suspend accounts that abuse an instance.
    </p>
    <h2 class="mt-2 text-base font-semibold text-base-content">No warranty</h2>
    <p>
      The software is provided as-is. Reminders, Google mirrors, and ICS imports can miss events. Do not rely on
      Kalendee as the only copy of something you cannot lose.
    </p>
  </div>
  <p class="mt-10 text-xs text-base-content/40">Last updated 14 September 2026. <Link href="/privacy" class="link">Privacy</Link></p>
</article>
