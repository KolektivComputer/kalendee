<script lang="ts">
  import { Head, Link, page, router, useAction } from "@kolektiv/keel-svelte"
  import ChevronDown from "@lucide/svelte/icons/chevron-down"
  import ChevronUp from "@lucide/svelte/icons/chevron-up"
  import X from "@lucide/svelte/icons/x"
  import {
    columnFilteringFeature,
    createColumnHelper,
    createFilteredRowModel,
    createPaginatedRowModel,
    createSortedRowModel,
    createTable,
    filterFn_includesString,
    FlexRender,
    globalFilteringFeature,
    renderSnippet,
    rowPaginationFeature,
    rowSortingFeature,
    sortFn_alphanumeric,
    sortFn_basic,
    tableFeatures,
  } from "@tanstack/svelte-table"
  import type { SvelteTable } from "@tanstack/svelte-table"
  import AdminGroupEditDialog from "../../../lib/components/AdminGroupEditDialog.svelte"
  import AdminGroupMembersDialog from "../../../lib/components/AdminGroupMembersDialog.svelte"
  import AdminUserEditDialog from "../../../lib/components/AdminUserEditDialog.svelte"
  import { actionMessage, fieldError } from "../../../lib/errors"
  import type {
    AdminCalendarSummary,
    AdminDeleteCalendarIn,
    AdminDeleteGroupIn,
    AdminDeleteUserIn,
    AdminPage,
    AdminSetCalendarPublicIn,
    AdminUserSummary,
    DeletedOut,
    EmailVerificationPolicyOut,
    GroupSummary,
    PublicAccessOut,
    RegistrationOut,
    SetEmailVerificationIn,
    SetPublicAccessIn,
    SetRegistrationIn,
  } from "../../../lib/page-types"

  type AdminTab = "users" | "calendars" | "groups" | "registration" | "email" | "privacy"

  type ConfirmTarget =
    | { kind: "delete-user"; user: AdminUserSummary }
    | { kind: "delete-calendar"; calendar: AdminCalendarSummary }
    | { kind: "disable-public"; calendar: AdminCalendarSummary }
    | { kind: "delete-group"; group: GroupSummary }

  const ctx = page<AdminPage>()
  const setRegistration = useAction<SetRegistrationIn, RegistrationOut>("kalendee.setRegistration")
  const setEmailVerification = useAction<SetEmailVerificationIn, EmailVerificationPolicyOut>(
    "kalendee.setEmailVerification",
  )
  const setPublicAccess = useAction<SetPublicAccessIn, PublicAccessOut>("kalendee.setPublicAccess")
  const deleteUser = useAction<AdminDeleteUserIn, DeletedOut>("kalendee.adminDeleteUser")
  const deleteCalendar = useAction<AdminDeleteCalendarIn, DeletedOut>("kalendee.adminDeleteCalendar")
  const setCalendarPublic = useAction<AdminSetCalendarPublicIn, AdminCalendarSummary>("kalendee.adminSetCalendarPublic")
  const deleteGroup = useAction<AdminDeleteGroupIn, DeletedOut>("kalendee.adminDeleteGroup")

  const titles: Record<AdminTab, string> = {
    users: "Users",
    calendars: "Calendars",
    groups: "Groups",
    registration: "Registration",
    email: "Email Verification",
    privacy: "Privacy",
  }

  const policies = [
    { value: "required", label: "Required", description: "Require a verified email before sign-in." },
    {
      value: "soft",
      label: "Soft",
      description: "Collect an email at registration; users can verify later and see a banner until they do.",
    },
    {
      value: "optional",
      label: "Optional",
      description: "Email is optional; users can add and verify it from Settings.",
    },
  ] as const

  let tab = $state<AdminTab>("users")
  let policy = $state(ctx.data.emailVerificationPolicy)
  let publicAccess = $state(ctx.data.publicAccess)

  let userDialogOpen = $state(false)
  let editingUser = $state<AdminUserSummary | null>(null)
  let groupDialogOpen = $state(false)
  let editingGroup = $state<GroupSummary | null>(null)
  let membersDialogOpen = $state(false)
  let membersGroup = $state<GroupSummary | null>(null)

  let confirmTarget = $state<ConfirmTarget | null>(null)
  let confirmDialog = $state<HTMLDialogElement | undefined>()
  let confirmBusy = $state(false)
  let confirmError = $state("")

  const viewerIsSuperadmin = $derived(
    ctx.data.users.some((user) => user.id === ctx.data.viewer.id && user.superadmin),
  )

  const isTableTab = $derived(tab === "users" || tab === "calendars" || tab === "groups")

  $effect(() => {
    policy = ctx.data.emailVerificationPolicy
  })

  $effect(() => {
    publicAccess = ctx.data.publicAccess
  })

  $effect(() => {
    if (!confirmDialog) return
    if (confirmTarget && !confirmDialog.open) confirmDialog.showModal()
    if (!confirmTarget && confirmDialog.open) confirmDialog.close()
  })

  $effect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key !== "Escape") return
      if (userDialogOpen || groupDialogOpen || membersDialogOpen || confirmTarget !== null) return
      event.preventDefault()
      closeAdmin()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  })

  function formatBytes(value: number): string {
    if (!Number.isFinite(value) || value <= 0) return "0 B"
    const units = ["B", "KiB", "MiB", "GiB", "TiB"]
    let size = value
    let unit = 0
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024
      unit += 1
    }
    const digits = unit === 0 || size >= 100 ? 0 : size >= 10 ? 1 : 2
    return `${size.toFixed(digits)} ${units[unit]}`
  }

  function canDeleteUser(user: AdminUserSummary): boolean {
    if (user.id === ctx.data.viewer.id) return false
    return !user.superadmin || viewerIsSuperadmin
  }

  const adminFeatures = tableFeatures({
    columnFilteringFeature,
    globalFilteringFeature,
    filteredRowModel: createFilteredRowModel(),
    filterFns: { includesString: filterFn_includesString },
    rowSortingFeature,
    sortedRowModel: createSortedRowModel(),
    sortFns: { alphanumeric: sortFn_alphanumeric, basic: sortFn_basic },
    rowPaginationFeature,
    paginatedRowModel: createPaginatedRowModel(),
  })

  const userHelper = createColumnHelper<typeof adminFeatures, AdminUserSummary>()
  const userColumns = userHelper.columns([
    userHelper.accessor((user) => `${user.displayName} ${user.username}`, {
      id: "user",
      header: "User",
      sortFn: "alphanumeric",
      cell: ({ row }) => renderSnippet(userCell, { user: row.original }),
    }),
    userHelper.accessor((user) => user.email ?? undefined, {
      id: "email",
      header: "Email",
      sortFn: "alphanumeric",
      sortUndefined: "last",
      cell: ({ row }) => renderSnippet(userEmailCell, { user: row.original }),
    }),
    userHelper.accessor((user) => (user.superadmin ? 2 : user.admin ? 1 : 0), {
      id: "role",
      header: "Role",
      sortFn: "basic",
      cell: ({ row }) => renderSnippet(userRoleCell, { user: row.original }),
    }),
    userHelper.accessor((user) => user.storageBytes, {
      id: "storage",
      header: "Storage",
      sortFn: "basic",
      cell: ({ row }) => renderSnippet(userStorageCell, { user: row.original }),
    }),
    userHelper.accessor((user) => user.groups.join(", "), {
      id: "groups",
      header: "Groups",
      enableSorting: false,
      cell: ({ row }) => renderSnippet(userGroupsCell, { user: row.original }),
    }),
    userHelper.display({
      id: "actions",
      header: "",
      enableSorting: false,
      cell: ({ row }) => renderSnippet(userActionsCell, { user: row.original }),
    }),
  ])

  const userTable = createTable({
    features: adminFeatures,
    columns: userColumns,
    get data() {
      return ctx.data.users
    },
    initialState: { pagination: { pageIndex: 0, pageSize: 25 } },
    globalFilterFn: "includesString",
    getColumnCanGlobalFilter: (column) => column.id === "user" || column.id === "email",
  })

  const userPagination = $derived(userTable.atoms.pagination.get())
  const userFilter = $derived(userTable.atoms.globalFilter.get() ?? "")
  const userTotal = $derived(userTable.getRowCount())
  const userPageCount = $derived(userTable.getPageCount())
  const userStart = $derived(userTotal === 0 ? 0 : userPagination.pageIndex * userPagination.pageSize + 1)
  const userEnd = $derived(userStart + userTable.getRowModel().rows.length - 1)

  const calendarHelper = createColumnHelper<typeof adminFeatures, AdminCalendarSummary>()
  const calendarColumns = calendarHelper.columns([
    calendarHelper.accessor("displayName", {
      id: "calendar",
      header: "Name",
      sortFn: "alphanumeric",
      cell: ({ row }) => renderSnippet(calendarNameCell, { calendar: row.original }),
    }),
    calendarHelper.accessor("ownerUsername", {
      id: "owner",
      header: "Owner",
      sortFn: "alphanumeric",
      cell: ({ row }) => renderSnippet(calendarOwnerCell, { calendar: row.original }),
    }),
    calendarHelper.accessor("eventCount", {
      id: "events",
      header: "Events",
      sortFn: "basic",
      cell: ({ getValue }) => getValue(),
    }),
    calendarHelper.accessor("publicLinkEnabled", {
      id: "public",
      header: "Public link",
      enableSorting: false,
      cell: ({ row }) => renderSnippet(calendarPublicCell, { calendar: row.original }),
    }),
    calendarHelper.display({
      id: "actions",
      header: "",
      enableSorting: false,
      cell: ({ row }) => renderSnippet(calendarActionsCell, { calendar: row.original }),
    }),
  ])

  const calendarTable = createTable({
    features: adminFeatures,
    columns: calendarColumns,
    get data() {
      return ctx.data.calendars
    },
    globalFilterFn: "includesString",
    getColumnCanGlobalFilter: (column) => column.id === "calendar" || column.id === "owner",
  })

  const calendarFilter = $derived(calendarTable.atoms.globalFilter.get() ?? "")

  const groupHelper = createColumnHelper<typeof adminFeatures, GroupSummary>()
  const groupColumns = groupHelper.columns([
    groupHelper.accessor("name", {
      id: "name",
      header: "Name",
      sortFn: "alphanumeric",
      cell: ({ row }) => renderSnippet(groupNameCell, { group: row.original }),
    }),
    groupHelper.accessor("memberCount", {
      id: "members",
      header: "Members",
      sortFn: "basic",
      cell: ({ getValue }) => getValue(),
    }),
    groupHelper.accessor((group) => group.storageQuotaBytes ?? Number.POSITIVE_INFINITY, {
      id: "quota",
      header: "Quota",
      sortFn: "basic",
      cell: ({ row }) => renderSnippet(groupQuotaCell, { group: row.original }),
    }),
    groupHelper.display({
      id: "actions",
      header: "",
      enableSorting: false,
      cell: ({ row }) => renderSnippet(groupActionsCell, { group: row.original }),
    }),
  ])

  const groupTable = createTable({
    features: adminFeatures,
    columns: groupColumns,
    get data() {
      return ctx.data.groups
    },
  })

  function selectTab(next: AdminTab) {
    tab = next
  }

  function selectPolicy(next: string) {
    if (policy === next) return
    const previous = policy
    policy = next
    void setEmailVerification.mutateAsync({ policy: next }).catch(() => {
      policy = previous
    })
  }

  function selectPublicAccess(next: string) {
    if (publicAccess === next) return
    const previous = publicAccess
    publicAccess = next
    void setPublicAccess.mutateAsync({ mode: next }).catch(() => {
      publicAccess = previous
    })
  }

  function closeAdmin() {
    void router.visit("/")
  }

  function calendarHref(userId: string): string {
    return `/?as=${encodeURIComponent(userId)}`
  }

  function openUserDialog(user: AdminUserSummary) {
    editingUser = user
    userDialogOpen = true
  }

  function openNewGroup() {
    editingGroup = null
    groupDialogOpen = true
  }

  function openEditGroup(group: GroupSummary) {
    editingGroup = group
    groupDialogOpen = true
  }

  function openGroupMembers(group: GroupSummary) {
    membersGroup = group
    membersDialogOpen = true
  }

  function openConfirm(target: ConfirmTarget) {
    confirmError = ""
    confirmTarget = target
  }

  function closeConfirm() {
    if (confirmBusy) return
    confirmTarget = null
    confirmError = ""
  }

  function onConfirmClose() {
    confirmTarget = null
    confirmError = ""
  }

  function confirmTitleFor(target: ConfirmTarget): string {
    switch (target.kind) {
      case "delete-user":
        return `Delete ${target.user.displayName}?`
      case "delete-calendar":
        return `Delete ${target.calendar.displayName}?`
      case "disable-public":
        return `Disable the public link for ${target.calendar.displayName}?`
      case "delete-group":
        return `Delete the ${target.group.name} group?`
    }
  }

  function confirmBodyFor(target: ConfirmTarget): string {
    switch (target.kind) {
      case "delete-user":
        return "The account is deleted. This cannot be undone."
      case "delete-calendar":
        return "Every event on this calendar is deleted. This cannot be undone."
      case "disable-public":
        return "The public link stops working for everyone who has it."
      case "delete-group":
        return "The group and its membership are removed. This cannot be undone."
    }
  }

  async function runConfirm() {
    const target = confirmTarget
    if (!target || confirmBusy) return
    confirmBusy = true
    confirmError = ""
    try {
      if (target.kind === "delete-user") {
        await deleteUser.mutateAsync({ userId: target.user.id })
      } else if (target.kind === "delete-calendar") {
        await deleteCalendar.mutateAsync({ calendarId: target.calendar.id })
      } else if (target.kind === "disable-public") {
        await setCalendarPublic.mutateAsync({ calendarId: target.calendar.id, enabled: false })
      } else {
        await deleteGroup.mutateAsync({ groupId: target.group.id })
      }
      confirmTarget = null
    } catch (error) {
      confirmError = actionMessage(error)
    } finally {
      confirmBusy = false
    }
  }
</script>

<Head />

<div class="settings-page settings-root">
  <aside class="settings-sidebar-region">
    <div class="settings-sidebar-scroller">
      <nav class="settings-sidebar" aria-label="Admin">
        <div class="settings-mobile-bar">
          <p class="px-2.5 text-sm font-semibold">Admin</p>
          <button type="button" class="settings-close" aria-label="Back to calendar" onclick={closeAdmin}>
            <X class="h-[18px] w-[18px]" />
          </button>
        </div>

        <p class="settings-nav-header">Kalendee Admin</p>
        <p class="settings-nav-header">Content</p>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "users"}
          aria-current={tab === "users" ? "page" : undefined}
          onclick={() => selectTab("users")}
        >
          Users
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "calendars"}
          aria-current={tab === "calendars" ? "page" : undefined}
          onclick={() => selectTab("calendars")}
        >
          Calendars
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "groups"}
          aria-current={tab === "groups" ? "page" : undefined}
          onclick={() => selectTab("groups")}
        >
          Groups
        </button>
        <p class="settings-nav-header mt-3">Instance</p>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "registration"}
          aria-current={tab === "registration" ? "page" : undefined}
          onclick={() => selectTab("registration")}
        >
          Registration
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "email"}
          aria-current={tab === "email" ? "page" : undefined}
          onclick={() => selectTab("email")}
        >
          Email Verification
        </button>
        <button
          type="button"
          class="settings-nav"
          class:settings-nav-active={tab === "privacy"}
          aria-current={tab === "privacy" ? "page" : undefined}
          onclick={() => selectTab("privacy")}
        >
          Privacy
        </button>

        <div class="settings-separator"></div>

        <Link href="/settings" class="settings-nav">Back to user settings</Link>

        <Link href="/" class="settings-nav">Back to calendar</Link>
      </nav>
    </div>
  </aside>

  <div class="settings-content-region">
    <div class="settings-content-scroller" class:admin-content-scroller={isTableTab}>
      <div class="settings-content admin-content" class:admin-content-tables={isTableTab}>
        <h1 class="settings-title">{titles[tab]}</h1>

        {#if tab === "users"}
          <div class="settings-stack">
            <div class="admin-table-toolbar">
              <p class="settings-hint">Accounts on this server. Edit details, roles, or remove users.</p>
              <input
                type="text"
                class="input admin-table-filter"
                placeholder="Search users"
                aria-label="Search users"
                value={userFilter}
                oninput={(event) => userTable.setGlobalFilter(event.currentTarget.value)}
              />
            </div>
            <div class="settings-card">
              <div class="admin-table-viewport">
                {@render adminTable(userTable, "min-w-[960px]")}
              </div>
              {#if userTable.getRowModel().rows.length === 0}
                <p class="settings-hint p-4">
                  {ctx.data.users.length === 0 ? "No users yet." : "No users match your search."}
                </p>
              {/if}
              <div class="admin-table-footer">
                <span class="settings-hint">
                  {#if userTotal === 0}
                    No users
                  {:else}
                    Showing {userStart}–{userEnd} of {userTotal}
                  {/if}
                </span>
                <div class="flex items-center gap-1">
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm"
                    disabled={!userTable.getCanPreviousPage()}
                    onclick={() => userTable.previousPage()}
                  >
                    Previous
                  </button>
                  <span class="settings-hint">Page {userPagination.pageIndex + 1} / {userPageCount}</span>
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm"
                    disabled={!userTable.getCanNextPage()}
                    onclick={() => userTable.nextPage()}
                  >
                    Next
                  </button>
                </div>
              </div>
            </div>
          </div>
        {:else if tab === "calendars"}
          <div class="settings-stack">
            <div class="admin-table-toolbar">
              <p class="settings-hint">Calendars across all accounts.</p>
              <input
                type="text"
                class="input admin-table-filter"
                placeholder="Search calendars"
                aria-label="Search calendars"
                value={calendarFilter}
                oninput={(event) => calendarTable.setGlobalFilter(event.currentTarget.value)}
              />
            </div>
            <div class="settings-card">
              <div class="admin-table-viewport">
                {@render adminTable(calendarTable, "min-w-[720px]")}
              </div>
              {#if calendarTable.getRowModel().rows.length === 0}
                <p class="settings-hint p-4">
                  {ctx.data.calendars.length === 0 ? "No calendars yet." : "No calendars match your search."}
                </p>
              {/if}
            </div>
          </div>
        {:else if tab === "groups"}
          <div class="settings-stack">
            <div class="admin-table-toolbar">
              <p class="settings-hint">Storage quotas and membership for user groups.</p>
              <button type="button" class="btn btn-primary btn-sm" onclick={openNewGroup}>New group</button>
            </div>
            <div class="settings-card">
              <div class="admin-table-viewport">
                {@render adminTable(groupTable, "min-w-[640px]")}
              </div>
              {#if groupTable.getRowModel().rows.length === 0}
                <p class="settings-hint p-4">No groups yet.</p>
              {/if}
            </div>
          </div>
        {:else if tab === "registration"}
          <div class="settings-card">
            <div class="settings-card-body settings-stack">
              <label class="flex cursor-pointer items-center gap-3">
                <input
                  type="checkbox"
                  class="toggle"
                  checked={ctx.data.registrationOpen}
                  disabled={setRegistration.isPending}
                  onchange={(event) =>
                    void setRegistration.mutateAsync({ open: event.currentTarget.checked }).catch(() => undefined)}
                />
                <span class="font-medium">{ctx.data.registrationOpen ? "Open" : "Closed"}</span>
              </label>
              <p class="settings-hint">
                When closed, only existing accounts can sign in. Opening it lets new people create accounts.
              </p>
            </div>
          </div>
        {:else if tab === "email"}
          <div class="settings-stack">
            <p class="settings-hint">Choose how email verification works for new accounts.</p>
            <ul class="grid grid-cols-1 gap-3">
              {#each policies as option (option.value)}
                <li>
                  <button
                    type="button"
                    class="settings-choice"
                    class:settings-swatch-active={policy === option.value}
                    aria-pressed={policy === option.value}
                    disabled={setEmailVerification.isPending}
                    onclick={() => selectPolicy(option.value)}
                  >
                    <span class="font-medium">{option.label}</span>
                    <span class="settings-hint">{option.description}</span>
                  </button>
                </li>
              {/each}
            </ul>
            {#if fieldError(setEmailVerification.error, "policy")}
              <p class="text-error text-sm">{fieldError(setEmailVerification.error, "policy")}</p>
            {/if}
          </div>
        {:else}
          <div class="settings-stack">
            <p class="settings-hint">
              Who can view public calendar links by default. Users and calendars can override this.
            </p>
            <div class="settings-card">
              <label class="settings-row cursor-pointer">
                <span class="min-w-0">
                  <span class="block">Anyone with the link</span>
                  <span class="settings-hint">No account needed to view public calendars.</span>
                </span>
                <input
                  type="radio"
                  class="radio"
                  name="public-access"
                  value="public"
                  checked={publicAccess === "public"}
                  disabled={setPublicAccess.isPending}
                  onchange={() => selectPublicAccess("public")}
                />
              </label>
              <label class="settings-row cursor-pointer">
                <span class="min-w-0">
                  <span class="block">Signed-in users only</span>
                  <span class="settings-hint">Visitors must have an account and sign in.</span>
                </span>
                <input
                  type="radio"
                  class="radio"
                  name="public-access"
                  value="signed_in"
                  checked={publicAccess === "signed_in"}
                  disabled={setPublicAccess.isPending}
                  onchange={() => selectPublicAccess("signed_in")}
                />
              </label>
            </div>
            {#if fieldError(setPublicAccess.error, "mode")}
              <p class="text-error text-sm">{fieldError(setPublicAccess.error, "mode")}</p>
            {:else if actionMessage(setPublicAccess.error)}
              <p class="text-error text-sm">{actionMessage(setPublicAccess.error)}</p>
            {/if}
          </div>
        {/if}
      </div>
    </div>

    <div class="settings-tools">
      <button type="button" class="settings-close" aria-label="Back to calendar" onclick={closeAdmin}>
        <X class="h-[18px] w-[18px]" />
      </button>
      <span class="settings-esc">ESC</span>
    </div>
  </div>
</div>

{#snippet adminTable(table: SvelteTable<typeof adminFeatures, any>, minWidth: string)}
  <table class="table table-sm w-full {minWidth}">
    <thead>
      {#each table.getHeaderGroups() as headerGroup (headerGroup.id)}
        <tr>
          {#each headerGroup.headers as header (header.id)}
            <th>
              {#if !header.isPlaceholder}
                {#if header.column.getCanSort()}
                  <button
                    type="button"
                    class="admin-sort"
                    onclick={(event) => header.column.getToggleSortingHandler()?.(event)}
                  >
                    <FlexRender content={header.column.columnDef.header} context={header.getContext()} />
                    <span class="admin-sort-icon" aria-hidden="true">
                      {#if header.column.getIsSorted() === "asc"}
                        <ChevronUp class="h-2.5 w-2.5" />
                      {:else if header.column.getIsSorted() === "desc"}
                        <ChevronDown class="h-2.5 w-2.5" />
                      {/if}
                    </span>
                  </button>
                {:else}
                  <FlexRender content={header.column.columnDef.header} context={header.getContext()} />
                {/if}
              {/if}
            </th>
          {/each}
        </tr>
      {/each}
    </thead>
    <tbody>
      {#each table.getRowModel().rows as row (row.id)}
        <tr>
          {#each row.getAllCells() as cell (cell.id)}
            <td>
              <FlexRender {cell} />
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
{/snippet}

{#snippet userCell({ user }: { user: AdminUserSummary })}
  <div class="font-medium">{user.displayName}</div>
  <div class="text-sm text-base-content/50">@{user.username}</div>
{/snippet}

{#snippet userEmailCell({ user }: { user: AdminUserSummary })}
  {#if user.email}
    <div class="flex flex-col items-start gap-1">
      <span class="max-w-48 truncate" title={user.email}>{user.email}</span>
      {#if user.emailVerified}
        <span class="badge badge-success badge-sm">Verified</span>
      {:else}
        <span class="badge badge-warning badge-sm">Unverified</span>
      {/if}
    </div>
  {:else}
    <span class="text-base-content/50">None</span>
  {/if}
{/snippet}

{#snippet userRoleCell({ user }: { user: AdminUserSummary })}
  {#if user.superadmin}
    <span class="badge badge-primary badge-sm">Superadmin</span>
  {:else if user.admin}
    <span class="badge badge-sm">Admin</span>
  {:else}
    <span class="text-base-content/50">Member</span>
  {/if}
{/snippet}

{#snippet userStorageCell({ user }: { user: AdminUserSummary })}
  <span class="whitespace-nowrap text-sm">
    {formatBytes(user.storageBytes)}
    <span class="text-base-content/50">
      / {user.quotaBytes === null ? "Unlimited" : formatBytes(user.quotaBytes)}
    </span>
  </span>
{/snippet}

{#snippet userGroupsCell({ user }: { user: AdminUserSummary })}
  {#if user.groups.length === 0}
    <span class="text-base-content/50">None</span>
  {:else}
    <div class="flex flex-wrap gap-1">
      {#each user.groups as group (group)}
        <span class="badge badge-ghost badge-sm">{group}</span>
      {/each}
    </div>
  {/if}
{/snippet}

{#snippet userActionsCell({ user }: { user: AdminUserSummary })}
  <div class="flex flex-wrap justify-end gap-1">
    <button type="button" class="btn btn-ghost btn-sm" onclick={() => openUserDialog(user)}>Edit</button>
    <button
      type="button"
      class="btn btn-ghost btn-sm text-error"
      disabled={!canDeleteUser(user)}
      title={canDeleteUser(user)
        ? "Delete this user"
        : user.id === ctx.data.viewer.id
          ? "You cannot delete yourself"
          : "Only a superadmin can delete a superadmin"}
      onclick={() => openConfirm({ kind: "delete-user", user })}
    >
      Delete
    </button>
    <Link class="btn btn-ghost btn-sm" href={calendarHref(user.id)}>Open calendar</Link>
  </div>
{/snippet}

{#snippet calendarNameCell({ calendar }: { calendar: AdminCalendarSummary })}
  <span class="font-medium">{calendar.displayName}</span>
{/snippet}

{#snippet calendarOwnerCell({ calendar }: { calendar: AdminCalendarSummary })}
  @{calendar.ownerUsername}
{/snippet}

{#snippet calendarPublicCell({ calendar }: { calendar: AdminCalendarSummary })}
  {#if calendar.publicLinkEnabled}
    <span class="badge badge-success badge-sm">Enabled</span>
  {:else}
    <span class="badge badge-ghost badge-sm">Disabled</span>
  {/if}
{/snippet}

{#snippet calendarActionsCell({ calendar }: { calendar: AdminCalendarSummary })}
  <div class="flex flex-wrap justify-end gap-1">
    {#if calendar.publicLinkEnabled}
      <button
        type="button"
        class="btn btn-ghost btn-sm"
        onclick={() => openConfirm({ kind: "disable-public", calendar })}
      >
        Disable public link
      </button>
    {/if}
    <button
      type="button"
      class="btn btn-ghost btn-sm text-error"
      onclick={() => openConfirm({ kind: "delete-calendar", calendar })}
    >
      Delete
    </button>
  </div>
{/snippet}

{#snippet groupNameCell({ group }: { group: GroupSummary })}
  <div class="flex flex-wrap items-center gap-2">
    <span class="font-medium">{group.name}</span>
    {#if group.isSystem}
      <span class="badge badge-ghost badge-sm">System</span>
    {/if}
  </div>
{/snippet}

{#snippet groupQuotaCell({ group }: { group: GroupSummary })}
  <span class="whitespace-nowrap">
    {group.storageQuotaBytes === null ? "Unlimited" : formatBytes(group.storageQuotaBytes)}
  </span>
{/snippet}

{#snippet groupActionsCell({ group }: { group: GroupSummary })}
  <div class="flex flex-wrap justify-end gap-1">
    <button type="button" class="btn btn-ghost btn-sm" onclick={() => openEditGroup(group)}>Edit quota</button>
    <button type="button" class="btn btn-ghost btn-sm" onclick={() => openGroupMembers(group)}>Members</button>
    {#if !group.isSystem}
      <button
        type="button"
        class="btn btn-ghost btn-sm text-error"
        onclick={() => openConfirm({ kind: "delete-group", group })}
      >
        Delete
      </button>
    {/if}
  </div>
{/snippet}

<dialog class="modal" bind:this={confirmDialog} onclose={onConfirmClose}>
  <div class="modal-box max-w-md">
    <h3 class="text-lg font-bold">{confirmTarget ? confirmTitleFor(confirmTarget) : ""}</h3>
    <p class="py-2 text-base-content/70">{confirmTarget ? confirmBodyFor(confirmTarget) : ""}</p>
    {#if confirmError}
      <p class="text-error text-sm">{confirmError}</p>
    {/if}
    <div class="modal-action">
      <button type="button" class="btn btn-ghost" disabled={confirmBusy} onclick={closeConfirm}>Cancel</button>
      <button type="button" class="btn btn-error" disabled={confirmBusy} onclick={() => void runConfirm()}>
        {confirmBusy
          ? "Working…"
          : confirmTarget?.kind === "disable-public"
            ? "Disable link"
            : "Delete"}
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop"><button>close</button></form>
</dialog>

<AdminUserEditDialog
  bind:open={userDialogOpen}
  user={editingUser}
  viewerId={ctx.data.viewer.id}
  viewerSuperadmin={viewerIsSuperadmin}
/>

<AdminGroupEditDialog bind:open={groupDialogOpen} group={editingGroup} />

<AdminGroupMembersDialog
  bind:open={membersDialogOpen}
  group={membersGroup}
  users={ctx.data.users}
  viewer={ctx.data.viewer}
/>
