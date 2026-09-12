export interface ViewerOrganization {
  id: string
  slug: string
  displayName: string
  role: string
}

const ROLE_RANK: Record<string, number> = { owner: 0, admin: 1, member: 2 }

export function viewerOrganizations(value: unknown): ViewerOrganization[] {
  if (!Array.isArray(value)) return []
  const result: ViewerOrganization[] = []
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue
    const record = entry as Record<string, unknown>
    const { id, slug, displayName, role } = record
    if (typeof id !== "string" || typeof slug !== "string" || typeof displayName !== "string") continue
    result.push({
      id,
      slug,
      displayName,
      role: typeof role === "string" ? role : "member",
    })
  }
  return result
}

export function organizationRoleLabel(role: string): string {
  if (role === "owner") return "Owner"
  if (role === "admin") return "Admin"
  if (role === "member") return "Member"
  return role
}

export function organizationRoleBadge(role: string): string {
  if (role === "owner") return "badge-primary"
  if (role === "admin") return "badge-secondary"
  return "badge-ghost"
}

export function sortOrganizationMembers<T extends { role: string }>(members: readonly T[]): T[] {
  return [...members].sort((a, b) => (ROLE_RANK[a.role] ?? 3) - (ROLE_RANK[b.role] ?? 3))
}
