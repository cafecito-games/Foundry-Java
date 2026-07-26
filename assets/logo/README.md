# Foundry-Java logo

The Foundry-Java mark forges the **steaming coffee cup** into the Foundry **forged
hex**, using the Foundry **Iron & Ember** palette. It signals the fork's identity
(Java bindings for Foundry, Android) while staying visually part of the Foundry
family alongside the FoundrySwift bird mark.

- **Palette:** Iron & Ember — forge charcoal `#14161B`, steel `#3A4250`/`#6B7686`,
  ember `#FF6A13`/`#FF8A3D`/`#FFD76B`, bone-white ink `#ECE7DC`.
- **Wordmark:** Cinzel (700 for `FOUNDRY`, 600 for `JAVA`), outlined to paths —
  no font dependency.
- The cup's ember fill is a vertical gradient (spark at top → ember at base),
  matching the Foundry mark convention, so the steam catches the spark highlight.

## Files

| File | Role |
|------|------|
| `foundryjava-mark.svg` | Primary mark — hex + ember cup, transparent. Use on dark surfaces. |
| `foundryjava-mark-tile.svg` | Mark on the dark rounded forge tile — app / project icon. |
| `foundryjava-mark-mono.svg` | Single-color mark (`currentColor`) for busy / light / one-color contexts. |
| `foundryjava-lockup-horizontal.svg` | Primary lockup — mark + divider + FOUNDRY / JAVA. |
| `foundryjava-lockup-horizontal-mono.svg` | One-color horizontal lockup (`currentColor`). |
| `foundryjava-lockup-stacked.svg` | Centered lockup for square-ish spaces. |
| `foundryjava-banner.svg` | Wide banner (README / social), dark panel. |

Rasterized PNGs live in `png/` (mark 32–512, mono 32–512, tile 256/512/1024,
lockups, banner). The mono PNGs are rendered in bone-white `#ECE7DC` for dark
backgrounds.

## Regenerating PNGs

```sh
rsvg-convert -w 512 -h 512 foundryjava-mark.svg -o png/foundryjava-mark-512.png
```

> The steaming cup is a nod to the Java platform's own iconography, redrawn here
> in the Foundry idiom for this community project. Java is a trademark of Oracle
> and/or its affiliates; this is not an official Oracle or Java project.
