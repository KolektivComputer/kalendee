import { defineDocsChrome, type ChromeTheme } from "@kolektiv/common-docs-chrome";

const REPO_URL = "https://github.com/KolektivComputer/kalendee";

/**
 * Canonical site origin. Override per deployment with `PUBLIC_SITE_URL` (for
 * example the production custom domain); the default is the Cloudflare Pages
 * project URL.
 */
const siteUrl = process.env.PUBLIC_SITE_URL ?? "https://kalendee.kolektiv.computer";

/**
 * Provisional Kalendee palette.
 *
 * The dark scheme mirrors the `kalendee` daisyUI theme that ships in the web UI
 * (`server/pack/src/styles.css`): near-neutral greys for the surface, an orange
 * primary (`oklch(66% 0.179 58.318)`), a blue secondary and a magenta accent —
 * the same hues as the old docs `--accent: #c700de` / `--link: #0b6fb5` pair.
 * The light scheme reuses those hues on light surfaces. These values are
 * intentionally provisional until the Kalendee brand palette is finalised.
 */
const kalendeeDark: ChromeTheme = {
  id: "kalendee-dark",
  label: "Kalendee Dark",
  scheme: "dark",
  family: "kalendee",
  shiki: "catppuccin-mocha",
  colors: {
    "base-100": "oklch(14% 0 0)",
    "base-200": "oklch(20% 0 0)",
    "base-300": "oklch(26% 0 0)",
    "base-content": "oklch(97% 0 0)",
    primary: "oklch(66% 0.179 58.318)",
    "primary-content": "oklch(98% 0.016 73.684)",
    secondary: "oklch(60% 0.126 221.723)",
    "secondary-content": "oklch(98% 0.019 200.873)",
    accent: "oklch(59% 0.293 322.896)",
    "accent-content": "oklch(97% 0.021 166.113)",
    neutral: "oklch(43% 0 0)",
    "neutral-content": "oklch(98% 0 0)",
    info: "oklch(68% 0.169 237.323)",
    "info-content": "oklch(97% 0.013 236.62)",
    success: "oklch(62% 0.194 149.214)",
    "success-content": "oklch(98% 0.014 180.72)",
    warning: "oklch(79% 0.184 86.047)",
    "warning-content": "oklch(98% 0.026 102.212)",
    error: "oklch(63% 0.237 25.331)",
    "error-content": "oklch(97% 0.013 17.38)",
  },
};

const kalendeeLight: ChromeTheme = {
  id: "kalendee-light",
  label: "Kalendee Light",
  scheme: "light",
  family: "kalendee",
  shiki: "catppuccin-latte",
  colors: {
    "base-100": "oklch(99% 0 0)",
    "base-200": "oklch(96.5% 0 0)",
    "base-300": "oklch(92% 0 0)",
    "base-content": "oklch(22% 0 0)",
    primary: "oklch(58% 0.179 58.318)",
    "primary-content": "oklch(98% 0.016 73.684)",
    secondary: "oklch(52% 0.126 221.723)",
    "secondary-content": "oklch(98% 0.019 200.873)",
    accent: "oklch(52% 0.293 322.896)",
    "accent-content": "oklch(98% 0.021 166.113)",
    neutral: "oklch(38% 0 0)",
    "neutral-content": "oklch(98% 0 0)",
    info: "oklch(55% 0.169 237.323)",
    "info-content": "oklch(98% 0.013 236.62)",
    success: "oklch(52% 0.194 149.214)",
    "success-content": "oklch(98% 0.014 180.72)",
    warning: "oklch(72% 0.184 86.047)",
    "warning-content": "oklch(22% 0.026 102.212)",
    error: "oklch(55% 0.237 25.331)",
    "error-content": "oklch(98% 0.013 17.38)",
  },
};

/**
 * Navigation skeleton.
 *
 * Items are href-based, not content-collection slugs. Each href maps 1:1 onto a
 * Markdown file under `src/content/docs/` — `/docs/product/getting-started`
 * renders `src/content/docs/product/getting-started.md`. The markdown for the
 * pages listed here (other than the moved external-calendars guide) is authored
 * in a follow-up wave; the sidebar intentionally lists the full skeleton now.
 */
export const docs = defineDocsChrome({
  name: "Kalendee",
  title: "Kalendee Docs",
  description:
    "Self-hosted CalDAV server with first-party Kotlin Multiplatform clients for Android, iOS, and Desktop, plus a themeable web UI.",
  siteUrl,
  base: "/",
  mark: "/favicon.svg",
  defaultTheme: "kalendee-dark",
  defaultCodeTheme: "follow",
  themeFamilies: ["kalendee", "kolektiv", "catppuccin", "nord", "daisyui"],
  themeFamily: "kalendee",
  themes: [kalendeeDark, kalendeeLight],
  repo: {
    url: REPO_URL,
    branch: "main",
    editBaseUrl: `${REPO_URL}/edit/main/docs/src/content/docs`,
  },
  scm: [
    { label: "Source", href: REPO_URL },
    { label: "Issues", href: `${REPO_URL}/issues` },
    { label: "Releases", href: `${REPO_URL}/releases` },
  ],
  nav: [
    {
      label: "Kalendee",
      icon: "M5 19V6.2c0-.3.2-.6.5-.7L12 3l6.5 2.5c.3.1.5.4.5.7V19l-7-2.5L5 19z",
      items: [
        {
          label: "Overview",
          href: "/",
          description: "Product landing page",
        },
        {
          label: "Source on GitHub",
          href: REPO_URL,
          external: true,
          description: "Kalendee repository",
        },
      ],
    },
    {
      label: "Product",
      icon: "M4 7h16M4 12h10M4 17h7",
      items: [
        {
          label: "Overview",
          href: "/docs/product/index",
          description: "What Kalendee is and what it does",
        },
        {
          label: "Getting started",
          href: "/docs/product/getting-started",
          description: "Set up an account and your first calendar",
        },
        {
          label: "Calendars and events",
          href: "/docs/product/calendars-and-events",
          description: "Create and manage calendars and events",
        },
        {
          label: "Recurring events",
          href: "/docs/product/recurring-events",
          description: "Repeating events, exceptions, and edits",
        },
        {
          label: "Sharing and following",
          href: "/docs/product/sharing-and-following",
          description: "Share calendars and follow other users",
        },
        {
          label: "Public access",
          href: "/docs/product/public-access",
          description: "Anonymous read-only links and public pages",
        },
        {
          label: "Organizations",
          href: "/docs/product/organizations",
          description: "Multi-user organizations and membership",
        },
        {
          label: "Groups and quotas",
          href: "/docs/product/groups-and-quotas",
          description: "Groups, roles, and resource quotas",
        },
        {
          label: "Availability and scheduling",
          href: "/docs/product/availability-and-scheduling",
          description: "Time-slot proposals and availability",
        },
        {
          label: "Event invites and RSVP",
          href: "/docs/product/event-invites-and-rsvp",
          description: "Invitations and yes / no / maybe replies",
        },
        {
          label: "Notifications",
          href: "/docs/product/notifications",
          description: "Reminders and activity notifications",
        },
        {
          label: "External calendars",
          href: "/docs/product/external-calendars",
          description: "Link and mirror external calendar providers",
        },
        {
          label: "Directory and profiles",
          href: "/docs/product/directory-and-profiles",
          description: "User directory, profiles, and avatars",
        },
        {
          label: "Clients",
          href: "/docs/product/clients",
          description: "Web, desktop, Android, and iOS clients",
        },
        {
          label: "Theming",
          href: "/docs/product/theming",
          description: "Themes across the web and native clients",
        },
        {
          label: "Accounts and security",
          href: "/docs/product/accounts-and-security",
          description: "Passwords, sessions, and account security",
        },
      ],
    },
    {
      label: "Self-hosting",
      icon: "M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01",
      items: [
        {
          label: "Overview",
          href: "/docs/self-hosting/index",
          description: "Deployment options at a glance",
        },
        {
          label: "Requirements",
          href: "/docs/self-hosting/requirements",
          description: "Supported platforms and prerequisites",
        },
        {
          label: "Docker Compose",
          href: "/docs/self-hosting/docker-compose",
          description: "Run the published image with PostgreSQL",
        },
        {
          label: "Standalone binary",
          href: "/docs/self-hosting/binary",
          description: "Run the server without Docker",
        },
        {
          label: "NixOS",
          href: "/docs/self-hosting/nixos",
          description: "Declarative NixOS deployment",
        },
        {
          label: "Configuration",
          href: "/docs/self-hosting/configuration",
          description: "HOCON configuration and overrides",
        },
        {
          label: "Environment variables",
          href: "/docs/self-hosting/environment",
          description: "Every KALENDEE_* variable",
        },
        {
          label: "Database",
          href: "/docs/self-hosting/database",
          description: "PostgreSQL setup and migrations",
        },
        {
          label: "Object storage",
          href: "/docs/self-hosting/object-storage",
          description: "S3-compatible storage for avatars",
        },
        {
          label: "Email",
          href: "/docs/self-hosting/email",
          description: "Transactional email providers",
        },
        {
          label: "Cloudflare Workers",
          href: "/docs/self-hosting/cloudflare-workers",
          description: "Mailer and R2 gateway workers",
        },
        {
          label: "Reverse proxy",
          href: "/docs/self-hosting/reverse-proxy",
          description: "TLS, headers, and proxying",
        },
        {
          label: "Backups and upgrades",
          href: "/docs/self-hosting/backups-and-upgrades",
          description: "Back up data and upgrade safely",
        },
        {
          label: "Administration",
          href: "/docs/self-hosting/administration",
          description: "Admin settings, users, and registrations",
        },
        {
          label: "Discord OAuth",
          href: "/docs/self-hosting/oauth-discord",
          description: "Configure the Discord integration",
        },
        {
          label: "Security",
          href: "/docs/self-hosting/security",
          description: "Hardening and operational security",
        },
        {
          label: "Troubleshooting",
          href: "/docs/self-hosting/troubleshooting",
          description: "Common problems and fixes",
        },
      ],
    },
  ],
  footer: {
    tagline: "Self-hosted CalDAV server with first-party clients.",
    links: [
      { label: "Documentation", href: "/docs/" },
      { label: "GitHub", href: REPO_URL },
      { label: "Issues", href: `${REPO_URL}/issues` },
      { label: "License (AGPL-3.0-only)", href: `${REPO_URL}/blob/main/LICENSE` },
      { label: "Kolektiv", href: "https://kolektiv.computer" },
    ],
  },
});
