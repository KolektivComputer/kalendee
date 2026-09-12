export const SIDEBAR_COLLAPSED_KEY = "kalendee.sidebarCollapsed"

export function readCollapsed(): Record<string, boolean> {
  if (typeof localStorage === "undefined") return {}
  try {
    const raw = localStorage.getItem(SIDEBAR_COLLAPSED_KEY)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {}
    const collapsed: Record<string, boolean> = {}
    for (const [key, value] of Object.entries(parsed)) {
      if (typeof value === "boolean") collapsed[key] = value
    }
    return collapsed
  } catch {
    return {}
  }
}

export function writeCollapsed(collapsed: Record<string, boolean>): void {
  if (typeof localStorage === "undefined") return
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, JSON.stringify(collapsed))
  } catch {
    // Storage can fail in private mode or when the quota is exhausted.
  }
}
