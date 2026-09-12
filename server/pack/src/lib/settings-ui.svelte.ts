export type SettingsTab = "account" | "appearance" | "holidays" | "notifications"

export function settingsHref(tab: SettingsTab = "account"): string {
  return tab === "account" ? "/settings" : `/settings?tab=${tab}`
}

export function parseSettingsTab(value: string | null | undefined): SettingsTab {
  return value === "appearance" || value === "holidays" || value === "notifications" ? value : "account"
}
