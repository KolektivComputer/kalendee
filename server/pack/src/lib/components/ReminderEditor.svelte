<script lang="ts">
  import { MAX_OFFSET_SECONDS, MAX_REMINDERS, nextReminderRowId, unitSeconds, type ReminderRow } from "../reminders"

  let {
    rows = $bindable<ReminderRow[]>([]),
    disabled = false,
  }: {
    rows: ReminderRow[]
    disabled?: boolean
  } = $props()

  function addRow() {
    if (rows.length >= MAX_REMINDERS) return
    rows = [...rows, { id: nextReminderRowId(), value: 10, unit: "minutes" }]
  }

  function removeRow(id: number) {
    rows = rows.filter((row) => row.id !== id)
  }

  function maxFor(unit: ReminderRow["unit"]): number {
    return Math.floor(MAX_OFFSET_SECONDS / unitSeconds(unit))
  }
</script>

<div class="reminder-editor flex flex-col gap-2">
  {#each rows as row (row.id)}
    <div class="flex items-center gap-2">
      <input
        class="input w-20"
        type="number"
        min="1"
        step="1"
        max={maxFor(row.unit)}
        bind:value={row.value}
        disabled={disabled}
        aria-label="Reminder amount"
      />
      <select class="select w-28" bind:value={row.unit} disabled={disabled} aria-label="Reminder unit">
        <option value="minutes">minutes</option>
        <option value="hours">hours</option>
        <option value="days">days</option>
      </select>
      <span class="settings-hint">before</span>
      <button
        type="button"
        class="btn btn-ghost btn-sm ml-auto"
        disabled={disabled}
        aria-label="Remove reminder"
        onclick={() => removeRow(row.id)}
      >
        Remove
      </button>
    </div>
  {/each}

  <div class="flex flex-wrap items-center gap-2">
    <button type="button" class="btn btn-sm" disabled={disabled || rows.length >= MAX_REMINDERS} onclick={addRow}>
      Add reminder
    </button>
    {#if rows.length === 0}
      <span class="settings-hint">No reminders configured.</span>
    {:else if rows.length >= MAX_REMINDERS}
      <span class="settings-hint">Up to {MAX_REMINDERS} reminders.</span>
    {/if}
  </div>
</div>
