# Lucide icon layer

Vendored [Lucide](https://lucide.dev) icons as Compose `ImageVector`s, pinned to
**Lucide v1.52.0** (tag `1.52.0`). Generated with
[Valkyrie CLI v1.2.1](https://github.com/ComposeGears/Valkyrie) and committed as
sources; there is no build-time generation and no Valkyrie Gradle plugin.

Import only `dev.kolektiv.kalendee.ui.icons.Lucide`. The properties in
`generated/` are `internal` on purpose; do not use them directly.

## Included icons (50)

Exposed names in `Lucide` (lucide kebab-case source names are the same unless
noted below):

`AlertCircle`, `ArrowLeft`, `ArrowRight`, `Bell`, `BellRing`, `Building2`,
`Calendar`, `CalendarDays`, `CalendarPlus`, `CalendarRange`, `Check`,
`ChevronDown`, `ChevronLeft`, `ChevronRight`, `ChevronUp`, `Circle`, `Clock`,
`Ellipsis`, `Eye`, `EyeOff`, `ExternalLink`, `Globe`, `LayoutGrid`, `Link`,
`List`, `LoaderCircle`, `Lock`, `LogIn`, `LogOut`, `Mail`, `MapPin`, `Menu`,
`Monitor`, `Moon`, `MoveLeft`, `MoveRight`, `Palette`, `Pencil`, `Plus`,
`RefreshCw`, `Search`, `Settings`, `SlidersHorizontal`, `Sun`, `Trash2`, `User`,
`UserPlus`, `Users`, `WifiOff`, `X`.

Substitutions against the originally requested lucide names:

- `AlertCircle` is backed by `circle-alert` (`alert-circle` was renamed in Lucide 1.0).
- `Trash2` is backed by `trash` (`trash-2` is a deprecated alias of the same glyph).
- `Building2` is backed by `building` (`building-2` no longer exists in Lucide 1.x).

## Adding or updating an icon

1. Get the Valkyrie CLI (Apache-2.0) once:

   ```bash
   curl -fLo /tmp/valkyrie.zip https://github.com/ComposeGears/Valkyrie/releases/download/cli-1.2.1/valkyrie-cli-1.2.1.zip
   python3 -m zipfile -e /tmp/valkyrie.zip /tmp/valkyrie/
   chmod +x /tmp/valkyrie/bin/valkyrie
   ```

2. Download the SVGs at the pinned version, using the canonical lucide file
   name (for example `circle-alert.svg`, `trash.svg`, `building.svg`):

   ```bash
   curl -fLo /tmp/lucide-svgs/<name>.svg \
     https://raw.githubusercontent.com/lucide-icons/lucide/1.52.0/icons/<name>.svg
   ```

3. Generate into a temp dir:

   ```bash
   /tmp/valkyrie/bin/valkyrie svgxml2imagevector \
     --input-path /tmp/lucide-svgs \
     --output-path /tmp/valkyrie-out \
     --package-name dev.kolektiv.kalendee.ui.icons.generated \
     --output-format backing-property \
     --explicit-mode=true
   ```

4. Post-process each generated file: prepend the two-line generated header
   (`// Generated from Lucide v1.52.0 ...` + the licenses line, matching the
   existing files) and change `public val` to `internal val`. Copy the result
   into `generated/` and keep only the icons listed above.

5. Add the delegating property to `Lucide.kt`, using an aliased import
   (`import dev.kolektiv.kalendee.ui.icons.generated.Check as GeneratedCheck`)
   so the getter cannot recurse into itself.

To re-pin Lucide: bump `1.52.0` in steps 2-4, re-download every icon in the
list, update the header comment, and update this README.

## License

Lucide is ISC licensed; icons derived from Feather are MIT licensed. Among the
icons vendored here the Feather-derived ones are `arrow-left`, `arrow-right`,
`calendar`, `check`, `chevron-*`, `circle`, `circle-alert`, `clock`,
`ellipsis`, `external-link`, `link`, `lock`, `log-in`, `log-out`, `monitor`,
`moon`, `plus`, `search`, `trash`, and `x` (the full list lives in
`licenses/lucide-ISC.txt`). See `licenses/lucide-ISC.txt` and
`licenses/feather-MIT.txt` at the repository root. Keep both files and the
header in the generated sources when adding or regenerating icons.
