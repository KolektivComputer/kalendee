---
title: Organizations
description: Group people and calendars under a shared page, with roles and teams.
---

An **organization** is a group of members that can own calendars together. It
has a public-or-private page at `/o/{slug}`, a membership list, and optional
**teams** that grant calendar access to groups of people.

Organizations are how a small team shares calendars without inviting each
person to every calendar individually.

## Create an organization

Only signed-in users can create organizations.

1. Open the [directory](/docs/product/directory-and-profiles) and select
   **New organization**.
2. Enter a **Slug** (the page URL: `/o/<slug>`), a **Name**, and an optional
   **Description**.
3. Choose **Private** (members and invitees only) or **Public** (listed in the
   directory).
4. Select **Create organization**.

The slug is 3–32 characters, starts with a letter or digit, and contains
lowercase letters, digits, dots, underscores, or hyphens. Reserved slugs such
as `admin`, `settings`, and `u` cannot be used. Creating an organization also
creates its default team and makes you an `owner`.

## Roles

Every member has exactly one organization role:

| Role | Capabilities |
| --- | --- |
| `owner` | Everything: manage members and roles, change visibility, manage teams, delete the organization. |
| `admin` | Manage members and teams, but may only add or invite `member`s and cannot change visibility or delete the organization. |
| `member` | View the organization and its (shared) calendars. Cannot manage members, invitations, or teams. |

An organization always keeps at least one owner. Only an owner can promote
someone to `admin` or `owner`, and only an owner can remove an admin or owner.

## Invite and manage members

From **Organization settings** (`/o/{slug}/settings`):

1. Under **Members**, enter a **username or email** and pick a role.
2. Select **Invite**.

Details:

- Inviting an existing user sends them an in-app notification and links the
  invitation to their account. Inviting an email address sends an email with a
  join link instead.
- Invitations are valid for **7 days**. Pending invitations are listed with an
  expiry and a **Revoke** action.
- The invited person accepts or declines from the organization page. Accepting
  adds them as a member and to the default team automatically.
- Change a member's role with the dropdown, or **Remove** them. Removing a
  member also removes them from the organization's teams.

## Teams

Teams are sub-groups inside an organization that hold calendar access.

- Each organization has a default team named **Everyone** (slug `all`). Every
  member joins it automatically, and it is granted **write** access to the
  organization's calendars. This is why an organization calendar is visible to
  all members by default.
- Owners and admins can create named teams under **Organization settings →
  Teams**, with a name, slug, and description.
- Team members have a team role: `member` or `maintainer`. A maintainer can
  manage that team.
- A team can be **granted** `read` or `write` access to a calendar that belongs
  to the same organization. Only the calendar's owner or an organization
  owner/admin can manage those grants.

The net effect: put people in a team once, grant the team a calendar, and
everyone in the team gets that access.

## The organization page

`/o/{slug}` shows the organization's members, its public calendars, and a join
banner for anyone with a pending invitation. Members of a private organization
are the only people who can see its page; public organizations are listed in
the directory.

If a calendar belongs to an organization, it is grouped under that
organization in the sidebar and can be grouped by team. Moving a calendar into
an organization is done with **Move to…** on the calendar's menu.

## Visibility

| Visibility | Effect |
| --- | --- |
| **Private** | Only members and invitees can see the page. Its calendars are also forced to `signed_in` public access, and non-members cannot open their public links. |
| **Public** | The organization is listed in the directory; its page is visible to anyone. |

Only an owner can change visibility. For how visibility interacts with public
calendar links, see [Public access](/docs/product/public-access).

## Related

- [Groups and quotas](/docs/product/groups-and-quotas)
- [Sharing and following](/docs/product/sharing-and-following)
- [Directory and profiles](/docs/product/directory-and-profiles)
- [Public access](/docs/product/public-access)
