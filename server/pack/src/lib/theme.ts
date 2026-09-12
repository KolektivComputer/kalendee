export const ACCENTS = {
  primary: { label: "Primary" },
  secondary: { label: "Secondary" },
  accent: { label: "Accent" },
  info: { label: "Info" },
  success: { label: "Success" },
  error: { label: "Error" },
} as const

export type AccentId = keyof typeof ACCENTS

export const ACCENT_IDS = Object.keys(ACCENTS) as AccentId[]

let snapshotted = false

export function parseAccent(value: string | undefined | null): AccentId {
  return value && value in ACCENTS ? (value as AccentId) : "primary"
}

export function accentSwatch(id: AccentId): string {
  return `var(--kalendee-${id})`
}

export function applyAccent(value: string | undefined | null): void {
  if (typeof document === "undefined") return
  const root = document.documentElement
  const computed = getComputedStyle(root)
  if (!snapshotted) {
    for (const id of ACCENT_IDS) {
      root.style.setProperty(`--kalendee-${id}`, computed.getPropertyValue(`--color-${id}`).trim())
      root.style.setProperty(`--kalendee-${id}-content`, computed.getPropertyValue(`--color-${id}-content`).trim())
    }
    snapshotted = true
  }
  const accent = parseAccent(value)
  root.style.setProperty("--color-primary", `var(--kalendee-${accent})`)
  root.style.setProperty("--color-primary-content", `var(--kalendee-${accent}-content)`)
}
