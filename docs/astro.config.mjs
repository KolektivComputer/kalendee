// @ts-check
import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";
import tailwindcss from "@tailwindcss/vite";
import { createShikiConfig } from "@kolektiv/common-docs-chrome";

import { docs } from "./src/docs-chrome.ts";

export default defineConfig({
  site: docs.siteUrl,
  base: docs.base,
  output: "static",
  integrations: [sitemap()],
  markdown: {
    // Registers every built-in palette plus the Kalendee site themes.
    shikiConfig: createShikiConfig(docs.themes),
  },
  vite: {
    plugins: [tailwindcss()],
  },
});
