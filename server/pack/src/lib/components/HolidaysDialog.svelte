<script lang="ts">
  import X from "@lucide/svelte/icons/x"
  import { fieldError } from "../errors"
  import type {
    CreateCustomHolidayIn,
    CustomHolidaySummary,
    HolidayCatalogItem,
  } from "../page-types"

  let {
    catalog,
    subscribedIds,
    customHolidays,
    showHolidays,
    subscribePending = false,
    createPending = false,
    showPending = false,
    subscribeError = null,
    createError = null,
    onShowHolidays,
    onSaveSubscriptions,
    onCreateCustom,
    onDeleteCustom,
  }: {
    catalog: HolidayCatalogItem[]
    subscribedIds: string[]
    customHolidays: CustomHolidaySummary[]
    showHolidays: boolean
    subscribePending?: boolean
    createPending?: boolean
    showPending?: boolean
    subscribeError?: unknown
    createError?: unknown
    onShowHolidays: (show: boolean) => Promise<unknown>
    onSaveSubscriptions: (ids: string[]) => Promise<unknown>
    onCreateCustom: (input: CreateCustomHolidayIn) => Promise<unknown>
    onDeleteCustom: (id: string) => Promise<unknown>
  } = $props()

  let selected = $state<string[]>([])
  let customTitle = $state("")
  let customDate = $state("")

  $effect(() => {
    selected = [...subscribedIds]
  })

  const regions = $derived(
    [...new Set(catalog.map((item) => item.region))].map((region) => ({
      region,
      items: catalog.filter((item) => item.region === region),
    })),
  )

  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

  function toggle(id: string, checked: boolean) {
    if (checked) {
      if (!selected.includes(id)) selected = [...selected, id]
    } else {
      selected = selected.filter((item) => item !== id)
    }
  }
</script>

<form
  class="settings-stack"
  onsubmit={(event) => {
    event.preventDefault()
    void onSaveSubscriptions(selected).catch(() => undefined)
  }}
>
  <div class="settings-card">
    <label class="settings-row cursor-pointer">
      <span>Show holidays on the calendar</span>
      <input
        type="checkbox"
        class="toggle"
        checked={showHolidays}
        disabled={showPending}
        onchange={(event) => void onShowHolidays(event.currentTarget.checked).catch(() => undefined)}
      />
    </label>
  </div>

  {#each regions as group (group.region)}
    <section class="settings-stack" style="gap: 8px">
      <h2 class="settings-label px-1">{group.region}</h2>
      <ul class="settings-card">
        {#each group.items as item (item.id)}
          <li>
            <label class="settings-row cursor-pointer">
              <span class="min-w-0">{item.name}</span>
              <input
                type="checkbox"
                class="checkbox checkbox-sm"
                checked={selected.includes(item.id)}
                onchange={(event) => toggle(item.id, event.currentTarget.checked)}
              />
            </label>
          </li>
        {/each}
      </ul>
    </section>
  {/each}
  {#if fieldError(subscribeError, "subscribedIds")}
    <p class="text-error text-sm">{fieldError(subscribeError, "subscribedIds")}</p>
  {/if}

  <section class="settings-stack" style="gap: 8px">
    <h2 class="settings-label px-1">Your holidays</h2>
    {#if customHolidays.length === 0}
      <p class="settings-hint px-1">None yet. Add a name and a date; it repeats every year.</p>
    {:else}
      <ul class="settings-card">
        {#each customHolidays as holiday (holiday.id)}
          <li class="settings-row">
            <div class="min-w-0 flex-1">
              <div class="font-medium">{holiday.title}</div>
              <div class="settings-hint">{months[holiday.month - 1]} {holiday.day}</div>
            </div>
            <button
              type="button"
              class="btn btn-ghost btn-square btn-sm"
              aria-label={`Remove ${holiday.title}`}
              onclick={() => void onDeleteCustom(holiday.id).catch(() => undefined)}
            >
              <X class="h-4 w-4" />
            </button>
          </li>
        {/each}
      </ul>
    {/if}
    <div class="settings-card">
      <div class="settings-card-body settings-stack" style="gap: 16px">
        <label class="settings-field">
          <span class="settings-label">Name</span>
          <input id="custom-holiday-name" class="input" bind:value={customTitle} maxlength="80" />
        </label>
        <label class="settings-field">
          <span class="settings-label">Date</span>
          <input id="custom-holiday-date" class="input" type="date" bind:value={customDate} />
        </label>
        {#if fieldError(createError, "title") || fieldError(createError, "day") || fieldError(createError, "month")}
          <p class="text-error text-sm">
            {fieldError(createError, "title") ?? fieldError(createError, "day") ?? fieldError(createError, "month")}
          </p>
        {/if}
        <button
          type="button"
          class="btn btn-ghost self-start"
          disabled={createPending || customTitle.trim() === "" || customDate === ""}
          onclick={() => {
            const parts = customDate.split("-").map(Number)
            const month = parts[1]
            const day = parts[2]
            if (!month || !day) return
            void onCreateCustom({ title: customTitle, month, day })
              .then(() => {
                customTitle = ""
                customDate = ""
              })
              .catch(() => undefined)
          }}
        >
          {createPending ? "Adding…" : "Add holiday"}
        </button>
      </div>
    </div>
  </section>

  <div class="flex justify-end">
    <button type="submit" class="btn btn-primary" disabled={subscribePending}>
      {subscribePending ? "Saving…" : "Save holidays"}
    </button>
  </div>
</form>
