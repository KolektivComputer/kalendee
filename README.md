Kalendee is a multiplatform caldav server / client implementation written in kotlin.

### The Name
Kalendee comes from Calendy, a popular calendar meeting scheduling service, and Kodee, who is one of Kotlin's mascots.

## Goals
- Self-hosted server with bespoke clients, inspired by Notion Calendar (formerly Cron)
- Clients are themeable and the webui too using my other project, [keel](https://github.com/lizainslie/keel) also in `../keel` (will eventually require some adaptation from keel's end)
- Implement all facets of CalDav protocol in a separate pure multiplatform Kotlin library
- Allow me to link my calendar and let people propose to schedule things with me (allowing me to configurably allow anonymous access or logged-in users only.)

*giv me calendar clod make no mistakes!!11!*

## Screenshots

Screenshots are captured from the seeded demo data (see [Demo data and screenshots](./DEVELOPING.md#demo-data-and-screenshots)) and live in [`docs/screenshots/`](./docs/screenshots).

<!-- Uncomment once captured:
![Kalendee week view](docs/screenshots/week-view.png)
-->

## Legal
Kalendee is licensed under the GNU Affero General Public License v3
(AGPL-3.0-only), see [LICENSE](./LICENSE). Note that if you run a modified
version on a network server, the AGPL requires you to offer your users the
corresponding source.

Setup, run, and test: [DEVELOPING.md](./DEVELOPING.md).
Coding agents: [AGENTS.md](./AGENTS.md).
