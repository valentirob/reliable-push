# Diagrams

Every diagram in the docs is an SVG in this folder, generated from a short TOML source in [`src/`](src/) by
[`scripts/diagrams.py`](../../scripts/diagrams.py). Edit the source, never the SVG.

```bash
python3 scripts/diagrams.py            # rebuild every SVG (and the at-a-glance PNG, with Chrome or Chromium)
python3 scripts/diagrams.py --check    # what CI runs: sources and SVGs in sync, layout rules respected
python3 scripts/diagrams.py --png DIR  # render PNGs into DIR to look at the result
python3 -m unittest discover -s scripts/tests -p 'test_diagrams.py'
```

Python 3.11 or newer, standard library only. Rendering a PNG needs Google Chrome or Chromium (`CHROME=<path>` if it is
not found); building and checking the SVGs does not.

## Style

The visual language is the [diagram-design](https://github.com/cathrynlavery/diagram-design) plugin's (MIT), with the
reliable-push palette:

| Role | Colour | Used for |
|---|---|---|
| paper / ink / muted | `#f5f7fb` / `#172033` / `#5b6478` | background, text and strokes, secondary text and default arrows |
| accent | `#0b7268` (teal) | reliable-push: tracking arrows, the component in focus |
| link | `#2f5596` (blue) | your push path, your systems |
| alert | `#b42318` (red) | the fallback |

- Connectors are orthogonal with rounded elbows; labels sit on an opaque mask; arrows are drawn before boxes; every
  coordinate is on a 4px grid; a legend strip closes each diagram; at most one or two elements in focus.
- System fonts only: GitHub shows these SVGs as images, which cannot load web fonts.
- Animation (only the at-a-glance picture today) is CSS inside the SVG: no JavaScript, so it plays on GitHub too, and it
  stops for a viewer who asks for reduced motion. The committed PNG is that static frame.
- Each SVG has a `<title>` and a one-sentence `<desc>` that says what the diagram means: keep it true when you edit.

`scripts/diagrams.py` refuses (and `--check` fails on) a node or point off the grid, a diagonal segment, an arrow that
passes behind a box that is not its end, a label over a box or another label, text wider than its box and a text colour
under 4.5:1. Claude Code with the diagram-design plugin can also check the HTML previews
(`python3 scripts/diagrams.py --html <dir>`, then the plugin's `self_check.py`); nothing here needs the plugin.

## Source format

```toml
title = "Short name"                 # <title>, 60 characters or fewer
desc = "One sentence on what it shows."
width = 960                          # 960 for the docs; the at-a-glance picture is 1600
height = 420                         # the drawing area; the legend is added below it
type = "flow"                        # or "sequence"
animate = false                      # true: edges with a step play in order
output = "name.svg"                  # optional; default <file name>.svg in this folder
png = "../images/name.png"           # optional; keep a static PNG next to it

[ramp]                               # optional type sizes; default name 12, sub 9, tag 7, label 8, body 10
name = 12

[legend]                             # optional: rename a legend entry, or skip = ["kind"], or hide = true
input = "Your system"

[[zone]]                             # a group: x, y, w, h, label, tone = plain | accent | link | alert | dashed
[[node]]                             # id, x, y, w, h, kind, tag, name ("\n" for two lines), sub (text or list), bullets
[[edge]]                             # points = [[x, y], ...] orthogonal; style; label; step; label_seg; label_side
[[shape]]                            # any rect / circle / path / text, passed through (layer = "back" or "front")
```

Node kinds: `input` (yours), `external` (Google, Apple, Kubernetes), `focal` (in focus, one or two), `backend`
(reliable-push), `store`, `alert` (fallback), `optional` (dashed), `state` and `final` (state diagrams).

Edge styles: `push` (your push path), `track` (reliable-push tracking), `fallback`, `flow` (default), `dashed`
(optional, async, return). `dashed = true` dashes any style; `step = n` adds a numbered badge (and, with `animate`, a
token that runs along the edge in step order); `label_seg` picks the segment that carries the label (default: the
longest), `label_side` = `above` / `below` (horizontal) or `right` / `left` (vertical) or `on`, `label_at` = 0 to 1.

Sequence diagrams use `[[actor]]` (id, name, sub, kind) and `[[row]]` in order: `call`, `return`, `async`, `push`,
`track`, `fallback` (from, to, text), `self` (from, text), `note` (over = [ids], text), `alt` / `opt` / `loop` (guard,
actors), `else` (guard), `end`. `numbered = true` numbers the messages.

## Where each diagram is used

| Source | Used in |
|---|---|
| `glance.toml` → `../images/reliable-push-at-a-glance.svg` (+ `.png`) | `README.md`, `overview.md`, `presentation.html` |
| `sidecar.toml` | `README.md`, `overview.md` §1 |
| `components.toml` | `overview.md` §2, `architecture.md` |
| `e2e.toml`, `states.toml` | `overview.md` §3 (`states` also in `architecture.md`) |
| `backend.toml`, `worker.toml` | `overview.md` §4 |
| `dashboard.toml` | `overview.md` §5 |
| `javaclient.toml` | `overview.md` §6 |
| `android-receive.toml`, `android-events.toml` | `overview.md` §7 |
| `ios.toml` | `overview.md` §8 |
| `harness.toml` | `overview.md` §9 |
| `helm.toml` | `overview.md` §10 |
| `loadtest.toml` | `overview.md` §11 |
| `integration.toml` | `integration-guide.md` |

The at-a-glance picture of release 0.2.0, drawn by hand, is kept in [`../images/backup/`](../images/backup/).
