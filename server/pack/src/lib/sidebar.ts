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

export const SIDEBAR_WIDTH_KEY = "kalendee.sidebarWidth"
export const SIDEBAR_WIDTH_DEFAULT = 256
export const SIDEBAR_WIDTH_MIN = 200
export const SIDEBAR_WIDTH_MAX = 480
/** Below this width, calendar rows keep only the eye (icon-primary). */
export const SIDEBAR_LABEL_MIN_WIDTH = 228

export function clampSidebarWidth(width: number): number {
  return Math.min(SIDEBAR_WIDTH_MAX, Math.max(SIDEBAR_WIDTH_MIN, Math.round(width)))
}

export function readSidebarWidth(): number {
  if (typeof localStorage === "undefined") return SIDEBAR_WIDTH_DEFAULT
  try {
    const raw = localStorage.getItem(SIDEBAR_WIDTH_KEY)
    if (!raw) return SIDEBAR_WIDTH_DEFAULT
    const parsed = Number(raw)
    if (!Number.isFinite(parsed)) return SIDEBAR_WIDTH_DEFAULT
    return clampSidebarWidth(parsed)
  } catch {
    return SIDEBAR_WIDTH_DEFAULT
  }
}

export function writeSidebarWidth(width: number): void {
  if (typeof localStorage === "undefined") return
  try {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(clampSidebarWidth(width)))
  } catch {
    // Storage can fail in private mode or when the quota is exhausted.
  }
}
