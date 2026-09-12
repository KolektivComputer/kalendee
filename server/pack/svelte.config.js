/** @type {import('@sveltejs/vite-plugin-svelte').Options} */
export default {
  compilerOptions: { runes: true },
  vitePlugin: {
    dynamicCompileOptions({ filename }) {
      // Harbor skips runes for node_modules because keel-svelte is a workspace
      // package there. Published @kolektiv/keel-svelte is in node_modules and
      // Wrap/Link/Head use $props — compiling them as legacy treats $props as
      // a store and the page mount throws.
      const runePackages = ["@kolektiv/keel-svelte", "@tanstack/svelte-table", "bits-ui", "runed"]
      if (
        filename.includes("node_modules") &&
        !runePackages.some((pkg) => filename.includes(pkg))
      ) {
        return { runes: false }
      }
    },
  },
}
