<script lang="ts">
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

  let open = $state(false)
  let query = $state("")
  let root: HTMLDivElement | undefined = $state()

  const listId = $derived(id ? `${id}-list` : undefined)

  const filtered = $derived.by(() => {
    const q = query.trim().toLowerCase()
    if (q === "") return zones
    return zones.filter((zone) => zone.toLowerCase().includes(q))
  })

  function select(zone: string) {
    value = zone
    query = ""
    open = false
  }

  function onFocus() {
    if (disabled) return
    open = true
    query = ""
  }

  function onInput(event: Event) {
    query = (event.currentTarget as HTMLInputElement).value
    open = true
  }

  function onBlur(event: FocusEvent) {
    const next = event.relatedTarget as Node | null
    if (root?.contains(next)) return
    const typed = query.trim()
    if (typed !== "" && zones.includes(typed)) {
      value = typed
    }
    query = ""
    open = false
  }

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault()
      query = ""
      open = false
      ;(event.currentTarget as HTMLElement).blur()
      return
    }
    if (event.key === "Enter") {
      event.preventDefault()
      const first = filtered[0]
      if (first) select(first)
    }
  }
</script>

<div class={["relative", className].filter(Boolean).join(" ")} bind:this={root}>
  <input
    {id}
    class="input w-full pr-9"
    type="text"
    role="combobox"
    aria-expanded={open}
    aria-autocomplete="list"
    aria-controls={listId}
    {disabled}
    {required}
    {placeholder}
    value={open ? query : value}
    onfocus={onFocus}
    oninput={onInput}
    onblur={onBlur}
    onkeydown={onKeyDown}
    autocomplete="off"
  />
  <ChevronDown
    class="pointer-events-none absolute top-1/2 right-2.5 h-4 w-4 -translate-y-1/2 text-base-content/50"
    aria-hidden="true"
  />
  {#if open && !disabled}
    <ul
      id={listId}
      role="listbox"
      class="menu menu-sm absolute z-50 mt-1 max-h-60 w-full overflow-y-auto rounded-box border border-base-300 bg-base-100 p-1 shadow-lg"
    >
      {#each filtered as zone (zone)}
        <li>
          <button
            type="button"
            role="option"
            class="rounded-field"
            class:menu-active={zone === value}
            aria-selected={zone === value}
            onmousedown={(event) => event.preventDefault()}
            onclick={() => select(zone)}
          >
            {zone}
          </button>
        </li>
      {:else}
        <li class="disabled">
          <span class="text-base-content/50">No matching time zones</span>
        </li>
      {/each}
    </ul>
  {/if}
</div>
