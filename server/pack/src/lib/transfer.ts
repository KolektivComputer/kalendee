import type { ViewerOrganization } from "./organizations"
import type { CalendarSummary, TeamSummary, TransferCalendarIn } from "./page-types"

export interface PersonalTransferTarget {
  kind: "personal"
}

export interface OrganizationTransferTarget {
  kind: "org"
  organizationId: string
}

export interface TeamTransferTarget {
  kind: "team"
  organizationId: string
  teamId: string
}

export type TransferTarget = PersonalTransferTarget | OrganizationTransferTarget | TeamTransferTarget

export interface TransferDestination {
  key: string
  label: string
  target: TransferTarget
  disabled: boolean
  nested: boolean
}

export function personalTarget(): PersonalTransferTarget {
  return { kind: "personal" }
}

export function organizationTarget(organizationId: string): OrganizationTransferTarget {
  return { kind: "org", organizationId }
}

export function teamTarget(organizationId: string, teamId: string): TeamTransferTarget {
  return { kind: "team", organizationId, teamId }
}

export function transferTargetKey(target: TransferTarget): string {
  if (target.kind === "personal") return "personal"
  if (target.kind === "org") return `org:${target.organizationId}`
  return `team:${target.teamId}`
}

export function targetFromElementData(dataset: DOMStringMap): TransferTarget | null {
  const kind = dataset.transferKind
  const organizationId = dataset.transferOrg
  if (kind === "personal") return personalTarget()
  if (!organizationId) return null
  if (kind === "org") return organizationTarget(organizationId)
  const teamId = dataset.transferTeam
  if (kind === "team" && teamId) return teamTarget(organizationId, teamId)
  return null
}

export function canTransferCalendar(calendar: CalendarSummary, readOnly: boolean): boolean {
  return !readOnly && calendar.permission === "owner"
}

export function canDropCalendar(
  calendar: CalendarSummary,
  target: TransferTarget,
  teams: readonly TeamSummary[],
  readOnly: boolean,
): boolean {
  if (!canTransferCalendar(calendar, readOnly)) return false
  if (target.kind === "personal") return calendar.organizationId !== null
  if (target.kind === "org") {
    return calendar.organizationId !== target.organizationId || calendar.teamId !== null
  }
  const team = teams.find(
    (entry) => entry.id === target.teamId && entry.organizationId === target.organizationId,
  )
  if (!team || !team.canManageGrants) return false
  return calendar.organizationId !== target.organizationId || calendar.teamId !== target.teamId
}

export function hasTransferDestination(
  calendar: CalendarSummary,
  organizations: readonly ViewerOrganization[],
  teams: readonly TeamSummary[],
  readOnly: boolean,
): boolean {
  if (!canTransferCalendar(calendar, readOnly)) return false
  if (calendar.organizationId !== null) return true
  if (
    organizations.some((organization) =>
      canDropCalendar(calendar, organizationTarget(organization.id), teams, readOnly),
    )
  ) {
    return true
  }
  return teams.some((team) =>
    canDropCalendar(calendar, teamTarget(team.organizationId, team.id), teams, readOnly),
  )
}

export function transferInputFor(calendarId: string, target: TransferTarget): TransferCalendarIn {
  if (target.kind === "personal") {
    return { id: calendarId, organizationId: null, teamId: null }
  }
  if (target.kind === "org") {
    return { id: calendarId, organizationId: target.organizationId, teamId: null }
  }
  return { id: calendarId, organizationId: target.organizationId, teamId: target.teamId }
}

export function transferDestinations(
  calendar: CalendarSummary,
  organizations: readonly ViewerOrganization[],
  teams: readonly TeamSummary[],
  readOnly: boolean,
): TransferDestination[] {
  if (!canTransferCalendar(calendar, readOnly)) return []
  const destinations: TransferDestination[] = []
  const personal = personalTarget()
  if (calendar.organizationId !== null) {
    destinations.push({
      key: transferTargetKey(personal),
      label: "Personal",
      target: personal,
      disabled: false,
      nested: false,
    })
  }
  for (const organization of organizations) {
    const orgTarget = organizationTarget(organization.id)
    destinations.push({
      key: transferTargetKey(orgTarget),
      label: organization.displayName,
      target: orgTarget,
      disabled: !canDropCalendar(calendar, orgTarget, teams, readOnly),
      nested: false,
    })
    for (const team of teams) {
      if (team.organizationId !== organization.id) continue
      const target = teamTarget(organization.id, team.id)
      destinations.push({
        key: transferTargetKey(target),
        label: team.name,
        target,
        disabled: !canDropCalendar(calendar, target, teams, readOnly),
        nested: true,
      })
    }
  }
  return destinations
}

export function rebucketCalendar(
  calendar: CalendarSummary,
  updated: CalendarSummary,
  input: TransferCalendarIn,
  organizations: readonly ViewerOrganization[],
  teams: readonly TeamSummary[],
): CalendarSummary {
  const organization = input.organizationId
    ? organizations.find((entry) => entry.id === input.organizationId)
    : undefined
  const team = input.teamId ? teams.find((entry) => entry.id === input.teamId) : undefined
  const defaultTeam = input.organizationId
    ? teams.find((entry) => entry.organizationId === input.organizationId && entry.slug === "all")
    : undefined
  const teamId = input.teamId ? team?.id ?? null : defaultTeam?.id ?? null
  const teamName = input.teamId ? team?.name ?? null : defaultTeam?.name ?? null
  return {
    ...calendar,
    ...updated,
    organizationId: input.organizationId ?? null,
    organizationName: organization?.displayName ?? updated.organizationName,
    organizationSlug: organization?.slug ?? updated.organizationSlug,
    teamId,
    teamName,
  }
}
