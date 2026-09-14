---
title: Sharing and following
description: Share a calendar with people on your instance, and follow public calendars.
---

Kalendee sharing works on a single instance: you share a calendar with a user
account that exists on the same server. To let anyone with a link (including
people without an account) read a calendar, use a
[public link](/docs/product/public-access) instead.

## Permission model

Every calendar has one owner and a set of grants. The viewer's permission
determines what they can do.

| Permission | Granted by | Can view events | Can create/edit/delete events | Can manage sharing |
| --- | --- | --- | --- | --- |
| `owner` | Creating or receiving the calendar | Yes | Yes | Yes |
| `write` | A share, or a team grant | Yes | Yes | No |
| `read` | A share, or a team grant | Yes | No | No |
| `follow` | Following a public link | Yes | No | No |

Only the owner can change sharing. A calendar you can write to appears in your
sidebar with an **Editable** label; a read-only share shows **Read-only**.

## Share a calendar with a user

1. Right-click the calendar in the sidebar and choose **Share…**.
2. Under **Username or email**, enter the person's username or email address.
3. Choose **Read** or **Write**.
4. Select **Invite**.

The calendar appears for that person under **Shared by …** in their sidebar.
They get an in-app notification, and an email if their account has a verified
address and the server sends mail.

Useful details:

- The Share dialog lists **Friends** you can invite with one click.
- If the person is already in **People with access**, change their permission
  with the dropdown or select **Remove** to revoke it.
- Sharing with yourself, sharing twice, or sharing with an unknown username is
  rejected with a clear message.
- To share with several people, invite them one at a time.

## Follow a public calendar

Following is the read-only, link-based counterpart to sharing. It does not
require the owner to know who you are.

1. Open a public calendar at `/c/{token}`.
2. Select **Follow** (sign in first if the page asks you to).
3. The calendar appears in your sidebar as **Following …** and updates as the
   owner changes it.

Select **Unfollow** from the public page or the calendar's sidebar menu to stop
following. The owner sees a follower count and is notified when someone
follows; the follower count is not a list of names.

Publishing the link itself is the owner's decision and is covered in
[Public access](/docs/product/public-access).

## Friends

Friendships are a lightweight social list, separate from calendar permissions:

- Add someone from the **Friends** section of the sidebar with the **+**
  button, or search from within a Share dialog.
- A pending request can be accepted or declined by the recipient.
- Once you are friends, the Share dialog offers each friend as a one-click
  invite.

Friendship does not grant access to anything by itself. It only makes sharing
and scheduling easier to discover.

## Sharing through organizations and teams

When a calendar belongs to an organization, access can also be granted to a
whole team instead of individual users. Team grants use the same `read` and
`write` permissions and are managed from the organization's settings. See
[Organizations](/docs/product/organizations).

## Related

- [Public access](/docs/product/public-access)
- [Organizations](/docs/product/organizations)
- [Event invites and RSVP](/docs/product/event-invites-and-rsvp)
- [Notifications](/docs/product/notifications)
