import { defineCollection, z } from "astro:content";
import { glob } from "astro/loaders";

// Docs live as Markdown/MDX files under src/content/docs/. The file path
// becomes the slug and the URL: src/content/docs/product/external-calendars.md
// -> /docs/product/external-calendars/. Add a new guide by dropping a file in
// that tree (frontmatter `title`, optional `description`) and, if you want it in
// the sidebar, add the matching href to the `nav` in src/docs-chrome.ts.
const docs = defineCollection({
  loader: glob({ pattern: "**/*.{md,mdx}", base: "./src/content/docs" }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
  }),
});

export const collections = { docs };
