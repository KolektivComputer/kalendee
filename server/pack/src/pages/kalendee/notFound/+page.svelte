<script lang="ts">
  import { Head, Link, page } from "@kolektiv/keel-svelte"
  import type { NotFoundPage } from "../../../lib/page-types"

  const ctx = page<NotFoundPage>()
</script>

<Head />

<div class="hero min-h-full">
  <div class="hero-content text-center">
    <div>
      <h1 class="text-3xl font-semibold">{ctx.data.title}</h1>
      <p class="py-4 text-base-content/70">No page at <code>{ctx.data.path}</code>.</p>
      <Link href="/" class="link">Back to Kalendee</Link>
    </div>
  </div>
</div>
