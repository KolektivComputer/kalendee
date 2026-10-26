/** Host-seeded IANA zone ids from shared page props (`ZoneId.getAvailableZoneIds()`). */
export function sharedTimeZones(shared: unknown): string[] {
  if (!shared || typeof shared !== "object") return []
  const raw = (shared as { timeZones?: unknown }).timeZones
  if (!Array.isArray(raw)) return []
  const zones: string[] = []
  for (const entry of raw) {
    if (typeof entry === "string" && entry !== "") zones.push(entry)
  }
  return zones
}
