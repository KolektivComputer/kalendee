<script lang="ts">
  import { Combobox } from "bits-ui"
  import ChevronDown from "@lucide/svelte/icons/chevron-down"

  let {
    zones,
    value = $bindable(""),
    id = undefined,
    disabled = false,
    required = false,
    placeholder = "Select a time zone",
    class: className = "",
  }: {
    zones: string[]
    value?: string
    id?: string
    disabled?: boolean
    required?: boolean
    placeholder?: string
    class?: string
  } = $props()

  let searchValue = $state("")
  let open = $state(false)
  let rootEl: HTMLDivElement | undefined = $state()

  /** Prefer the open <dialog> (top layer) so the list isn't under the modal; fall back to body. */
  const portalTo = $derived.by(() => {
    if (!rootEl || typeof document === "undefined") return undefined
    return (rootEl.closest("dialog") as HTMLElement | null) ?? document.body
  })

  const items = $derived(zones.map((zone) => ({ value: zone, label: zone })))

  const filtered = $derived.by(() => {
    const q = searchValue.trim().toLowerCase()
    if (q === "") return zones
    return zones.filter((zone) => zone.toLowerCase().includes(q))
  })

  /**
   * bits-ui Combobox keeps selection (`value`) separate from the Input text (`inputValue`).
   * Drive inputValue ourselves: show the bound IANA id when closed; while open, show the
   * filter query (empty on open), matching the pre-Combobox TzDropdown contract.
   */
  const inputValue = $derived(open ? searchValue : value)
</script>

<div class={["relative w-full", className].filter(Boolean).join(" ")} bind:this={rootEl}>
  <Combobox.Root
    type="single"
    bind:value
    bind:open
    {disabled}
    {required}
    allowDeselect={false}
    {items}
    {inputValue}
    onOpenChangeComplete={(isOpen) => {
      if (!isOpen) searchValue = ""
    }}
  >
    <div class="relative">
      <Combobox.Input
        {id}
        {placeholder}
        {required}
        class="input w-full pr-9"
        autocomplete="off"
        defaultValue={value}
        oninput={(event) => {
          searchValue = event.currentTarget.value
        }}
      />
      <Combobox.Trigger
        class="absolute top-1/2 right-2.5 -translate-y-1/2 text-base-content/50"
        tabindex={-1}
        aria-label="Open time zone list"
      >
        <ChevronDown class="h-4 w-4" aria-hidden="true" />
      </Combobox.Trigger>
    </div>

    <Combobox.Portal to={portalTo}>
      <Combobox.Content
        class="z-[100] max-h-60 w-[var(--bits-combobox-anchor-width)] min-w-[var(--bits-combobox-anchor-width)] overflow-x-hidden overflow-y-auto rounded-box border border-base-300 bg-base-100 p-1 shadow-lg outline-none"
        side="bottom"
        sideOffset={4}
        preventScroll={false}
      >
        {#each filtered as zone (zone)}
          <Combobox.Item
            value={zone}
            label={zone}
            class="rounded-field flex w-full cursor-default items-center px-3 py-1.5 text-sm outline-none data-[highlighted]:bg-base-content/10 data-[selected]:font-medium"
          >
            {zone}
          </Combobox.Item>
        {:else}
          <div class="px-3 py-2 text-sm text-base-content/50">No matching time zones</div>
        {/each}
      </Combobox.Content>
    </Combobox.Portal>
  </Combobox.Root>
</div>
