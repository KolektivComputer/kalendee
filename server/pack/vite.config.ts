import { svelte } from "@sveltejs/vite-plugin-svelte"
import { keelPack } from "@kolektiv/keel-pack/vite"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vite"

export default defineConfig({
  plugins: [
    tailwindcss(),
    svelte(),
    keelPack({
      id: "kalendee",
      version: "0.2.1",
      framework: "svelte",
      pagesDir: "src/pages",
      bootstrap: "src/bootstrap.ts",
      contract: "src/lib/page-types.json",
      notFound: "kalendee.notFound",
      pack: "dist/kalendee.feb",
    }),
  ],
})
