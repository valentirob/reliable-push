#!/usr/bin/env python3
"""Builds the documentation diagrams from their TOML sources (rule .agents/rules/docs-sync.md).

    python3 scripts/diagrams.py               write docs/diagrams/<slug>.svg for every docs/diagrams/src/<slug>.toml
    python3 scripts/diagrams.py --check       fail when an SVG is stale or a layout rule is broken (CI)
    python3 scripts/diagrams.py --html DIR    also write one HTML preview page per diagram into DIR (not committed;
                                              the diagram-design plugin's self_check.py reads these)
    python3 scripts/diagrams.py --png DIR     also render each SVG to DIR/<slug>.png at 2x with headless Chrome or
                                              Chromium (static frame: reduced motion), to look at the result

The visual language is the diagram-design plugin's (github.com/cathrynlavery/diagram-design, MIT) with the
reliable-push palette: orthogonal connectors with rounded elbows, arrow labels on an opaque mask, arrows drawn before
boxes, a 4px grid, a legend strip at the bottom, an accessible <title>/<desc>. System fonts only: GitHub shows these
SVGs as images, which cannot load web fonts. Source format: docs/diagrams/README.md. Python 3.11+, standard library.
"""
from __future__ import annotations

import argparse
import html
import os
import pathlib
import shutil
import subprocess
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "diagrams" / "src"
OUT = ROOT / "docs" / "diagrams"

# reliable-push skin: every text colour clears 4.5:1 on paper (checked by check_contrast below).
P = {
    "paper": "#f5f7fb",
    "paper2": "#e9edf4",
    "white": "#ffffff",
    "ink": "#172033",
    "muted": "#5b6478",
    "soft": "#5d6680",
    "accent": "#0b7268",  # reliable-push: tracking, the sidecar
    "link": "#2f5596",  # your push path
    "alert": "#b42318",  # fallback
}
ALPHA = {  # rgb triples for rgba() tints
    "ink": "23,32,51",
    "muted": "91,100,120",
    "accent": "11,114,104",
    "link": "47,85,150",
    "alert": "180,35,24",
}


def rgba(role: str, a: float) -> str:
    return f"rgba({ALPHA[role]},{a:g})"


SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace"
SERIF = "Georgia, 'Times New Roman', serif"

# node kind -> (fill, stroke, dash, tag stroke, tag text)
NODE = {
    "focal": (rgba("accent", 0.08), P["accent"], None, rgba("accent", 0.5), P["accent"]),
    "backend": (P["white"], P["ink"], None, rgba("ink", 0.4), P["ink"]),
    "store": (rgba("ink", 0.05), P["muted"], None, rgba("muted", 0.5), P["muted"]),
    "external": (rgba("ink", 0.03), rgba("ink", 0.3), None, rgba("ink", 0.22), P["soft"]),
    "input": (rgba("link", 0.07), P["link"], None, rgba("link", 0.45), P["link"]),
    "optional": (rgba("ink", 0.02), rgba("ink", 0.3), "4,3", rgba("ink", 0.22), P["soft"]),
    "alert": (rgba("alert", 0.06), P["alert"], None, rgba("alert", 0.5), P["alert"]),
    "state": (P["white"], P["ink"], None, rgba("ink", 0.4), P["ink"]),
    "final": (rgba("ink", 0.05), P["muted"], None, rgba("muted", 0.5), P["muted"]),
}
NODE_LEGEND = {
    "focal": "reliable-push",
    "backend": "reliable-push component",
    "store": "Data store",
    "external": "Third-party service",
    "input": "Your system",
    "optional": "Optional",
    "alert": "Fallback",
    "state": "State",
    "final": "Final state",
}
# edge style -> (colour, width, dash, marker)
EDGE = {
    "push": (P["link"], 1.4, None, "link"),
    "track": (P["accent"], 1.4, None, "accent"),
    "fallback": (P["alert"], 1.4, None, "alert"),
    "flow": (P["muted"], 1.2, None, "muted"),
    "dashed": (P["muted"], 1.0, "4,3", "muted"),
}
EDGE_LEGEND = {
    "push": "Your push path",
    "track": "reliable-push tracking",
    "fallback": "Fallback",
    "flow": "Call / data",
    "dashed": "Optional, async or return",
}
STANDARD_RAMP = {"name": 12, "sub": 9, "tag": 7, "label": 8, "legend": 8.5, "body": 10, "line": 14}


class LayoutError(Exception):
    pass


def esc(text: str) -> str:
    return html.escape(str(text), quote=True)


def text_width(text: str, size: float, mono: bool = False, tracking: float = 0.0) -> float:
    advance = 0.62 if mono else 0.6
    return len(text) * size * (advance + tracking)


def up4(value: float) -> int:
    return int(-(-value // 4) * 4)


class Diagram:
    def __init__(self, spec: dict, slug: str):
        self.spec = spec
        self.slug = spec.get("slug", slug)
        self.kind = spec.get("type", "flow")
        self.width = spec["width"]
        self.height = spec["height"]
        self.ramp = {**STANDARD_RAMP, **spec.get("ramp", {})}
        self.errors: list[str] = []
        self.nodes = {n["id"]: n for n in spec.get("node", [])}
        self.used_nodes: list[str] = []
        self.used_edges: list[str] = []
        self.masks: list[tuple[float, float, float, float, str]] = []
        self.zone_tabs: list[str] = []

    # ---------------------------------------------------------------- helpers
    def err(self, message: str) -> None:
        self.errors.append(f"{self.slug}: {message}")

    def grid(self, what: str, *values: float) -> None:
        for v in values:
            if v % 4:
                self.err(f"{what}: {v} is off the 4px grid")

    def use_node(self, kind: str) -> None:
        if kind not in NODE:
            raise LayoutError(f"{self.slug}: unknown node kind {kind}")
        if kind not in self.used_nodes:
            self.used_nodes.append(kind)

    def use_edge(self, style: str) -> None:
        if style not in EDGE:
            raise LayoutError(f"{self.slug}: unknown edge style {style}")
        if style not in self.used_edges:
            self.used_edges.append(style)

    def marker(self, name: str) -> str:
        return f"url(#{self.slug}-m-{name})"

    # ---------------------------------------------------------------- shared pieces
    def defs(self) -> str:
        colours = {"link": P["link"], "accent": P["accent"], "alert": P["alert"], "muted": P["muted"]}
        out = ["<defs>"]
        for name, colour in colours.items():
            out.append(
                f'<marker id="{self.slug}-m-{name}" markerWidth="8" markerHeight="6" refX="7" refY="3" '
                f'orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{colour}"/></marker>'
            )
        out.append(
            f'<marker id="{self.slug}-m-open" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">'
            f'<polyline points="0 0, 8 3, 0 6" fill="none" stroke="{P["muted"]}" stroke-width="1.2"/></marker>'
        )
        out.append("</defs>")
        return "".join(out)

    def style(self) -> str:
        r = self.ramp
        css = [
            f".{self.slug} text{{font-family:{SANS}}}",
            f".{self.slug} .nm{{font-size:{r['name']}px;font-weight:600;fill:{P['ink']}}}",
            f".{self.slug} .sb{{font-family:{MONO};font-size:{r['sub']}px;fill:{P['muted']}}}",
            f".{self.slug} .bd{{font-size:{r['body']}px;fill:{P['ink']}}}",
            f".{self.slug} .tg{{font-family:{MONO};font-size:{r['tag']}px;letter-spacing:.08em}}",
            f".{self.slug} .lb{{font-family:{MONO};font-size:{r['label']}px;letter-spacing:.06em}}",
            f".{self.slug} .zn{{font-family:{MONO};font-size:{r['tag']}px;letter-spacing:.14em;fill:{rgba('ink', 0.55)}}}",
            f".{self.slug} .lg{{font-size:{r['legend']}px;fill:{P['muted']}}}",
            f".{self.slug} .lh{{font-family:{MONO};font-size:{r['tag'] + 1}px;letter-spacing:.18em;fill:{P['muted']}}}",
            f".{self.slug} .ti{{font-family:{SERIF};font-size:{r.get('title', 28)}px;fill:{P['ink']}}}",
            f".{self.slug} .wm{{font-family:{MONO};font-weight:600;fill:{rgba('ink', 0.06)}}}",
        ]
        css.extend(self.spec.get("css", []))
        return "<style>" + "".join(css) + "</style>"

    def label(self, x: float, y: float, text: str, colour: str, anchor: str = "middle", step: int | None = None) -> str:
        """An arrow label on an opaque mask. (x, y) is the centre of the mask."""
        r = self.ramp
        lines = text.split("\n")
        width = max(text_width(line, r["label"], mono=True, tracking=0.06) for line in lines) + 10
        badge = 0
        if step is not None:
            badge = r["label"] * 2 + 4
            width += badge
        width = up4(width)
        line_h = r["label"] + 4
        height = up4(len(lines) * line_h + 2)
        mx, my = x - width / 2, y - height / 2
        self.masks.append((mx, my, width, height, text))
        out = [f'<rect x="{mx:g}" y="{my:g}" width="{width}" height="{height}" rx="2" fill="{P["paper"]}"/>']
        tx = x + badge / 2
        first = y - (len(lines) - 1) * line_h / 2 + r["label"] * 0.36
        if step is not None:
            cx = mx + badge / 2 + 1
            out.append(self.badge(cx, y, step, colour))
        for i, line in enumerate(lines):
            out.append(
                f'<text class="lb" x="{tx:g}" y="{first + i * line_h:g}" fill="{colour}" '
                f'text-anchor="middle">{esc(line)}</text>'
            )
        return "".join(out)

    def badge(self, cx: float, cy: float, step: int, colour: str) -> str:
        radius = self.ramp["label"] * 0.95 + 1
        size = self.ramp["label"] + 1
        anim = f' class="bg bg{step}"' if self.spec.get("animate") else ""
        return (
            f'<g{anim}><circle cx="{cx:g}" cy="{cy:g}" r="{radius:g}" fill="{colour}"/>'
            f'<text x="{cx:g}" y="{cy + size * 0.36:g}" font-size="{size:g}" font-weight="700" fill="{P["white"]}" '
            f'text-anchor="middle">{step}</text></g>'
        )

    def legend(self, top: float) -> tuple[str, int]:
        spec = self.spec.get("legend", {})
        if spec.get("hide"):
            return "", 0
        r = self.ramp
        items: list[tuple[str, str]] = []
        for kind in self.used_nodes:
            if kind in spec.get("skip", []):
                continue
            items.append(("node:" + kind, spec.get(kind, NODE_LEGEND[kind])))
        for style in self.used_edges:
            if style in spec.get("skip", []):
                continue
            items.append(("edge:" + style, spec.get(style, EDGE_LEGEND[style])))
        out = [
            f'<line x1="40" y1="{top}" x2="{self.width - 40}" y2="{top}" stroke="{rgba("ink", 0.1)}" stroke-width="0.8"/>',
            f'<text class="lh" x="40" y="{top + 18}">LEGEND</text>',
        ]
        x, y = 40.0, top + 36
        rows = 1
        for key, text in items:
            w = (30 if key.startswith("edge") else 20) + text_width(text, r["legend"]) * 0.88 + 24
            if x + w > self.width - 40:
                x, y = 40.0, y + 20
                rows += 1
            kind, name = key.split(":")
            if kind == "node":
                fill, stroke, dash, _, _ = NODE[name]
                d = f' stroke-dasharray="{dash}"' if dash else ""
                out.append(f'<rect x="{x:g}" y="{y - 8:g}" width="14" height="10" rx="2" fill="{fill}" stroke="{stroke}"{d}/>')
            else:
                colour, sw, dash, marker = EDGE[name]
                d = f' stroke-dasharray="{dash}"' if dash else ""
                out.append(
                    f'<line x1="{x:g}" y1="{y - 3:g}" x2="{x + 22:g}" y2="{y - 3:g}" stroke="{colour}" stroke-width="{sw}"'
                    f'{d} marker-end="{self.marker(marker)}"/>'
                )
            tx = x + (30 if kind == "edge" else 20)
            out.append(f'<text class="lg" x="{tx:g}" y="{y:g}">{esc(text)}</text>')
            x += w
        return "".join(out), rows

    def wrap(self, body: str, height: float) -> str:
        title = self.spec["title"]
        desc = self.spec["desc"]
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {height:g}" width="{self.width}" '
            f'height="{height:g}" role="img" aria-labelledby="{self.slug}-title {self.slug}-desc" class="{self.slug}">\n'
            f'<title id="{self.slug}-title">{esc(title)}</title>\n<desc id="{self.slug}-desc">{esc(desc)}</desc>\n'
            f"<!-- Generated by scripts/diagrams.py from docs/diagrams/src/{self.slug}.toml: edit the source, not this file. -->\n"
            f"{self.defs()}\n{self.style()}\n"
            f'<rect width="100%" height="100%" fill="{P["paper"]}"/>\n{body}\n</svg>\n'
        )

    # ---------------------------------------------------------------- raw shapes
    def shapes(self, layer: str) -> str:
        out = []
        for s in self.spec.get("shape", []):
            if s.get("layer", "back") != layer:
                continue
            attrs = {k: v for k, v in s.items() if k not in ("kind", "text", "layer")}
            tag = s["kind"]
            attr_text = " ".join(f'{k.replace("_", "-")}="{esc(v)}"' for k, v in attrs.items())
            if tag == "text":
                lines = str(s["text"]).split("\n")
                if len(lines) == 1:
                    out.append(f"<text {attr_text}>{esc(lines[0])}</text>")
                else:
                    size = float(s.get("font_size", self.ramp["body"]))
                    spans = "".join(
                        f'<tspan x="{esc(s["x"])}" dy="{0 if i == 0 else size * 1.35:g}">{esc(line)}</tspan>'
                        for i, line in enumerate(lines)
                    )
                    out.append(f"<text {attr_text}>{spans}</text>")
            else:
                out.append(f"<{tag} {attr_text}/>")
        return "".join(out)

    # ---------------------------------------------------------------- flow diagrams
    def zone(self, z: dict) -> str:
        x, y, w, h = z["x"], z["y"], z["w"], z["h"]
        self.grid(f"zone {z.get('label', '')}", x, y, w, h)
        tone = z.get("tone", "plain")
        fill, stroke, dash = {
            "plain": (rgba("ink", 0.02), rgba("ink", 0.12), None),
            "accent": (rgba("accent", 0.04), rgba("accent", 0.4), None),
            "link": (rgba("link", 0.03), rgba("link", 0.3), None),
            "alert": (rgba("alert", 0.03), rgba("alert", 0.35), None),
            "dashed": (rgba("ink", 0.015), rgba("ink", 0.25), "4,4"),
        }[tone]
        d = f' stroke-dasharray="{dash}"' if dash else ""
        out = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="0.8"{d}/>']
        self.zone_tabs.append(self.zone_tab(z, tone))
        return "".join(out)

    def zone_tab(self, z: dict, tone: str) -> str:
        x, y = z["x"], z["y"]
        out = []
        label = z.get("label")
        if label:
            r = self.ramp
            lw = up4(text_width(label, r["tag"], mono=True, tracking=0.14) + 12)
            colour = {"accent": P["accent"], "link": P["link"], "alert": P["alert"]}.get(tone, P["soft"])
            th = r["tag"] + 6
            out.append(f'<rect x="{x + 12}" y="{y - th / 2:g}" width="{lw}" height="{th}" rx="2" fill="{P["paper"]}"/>')
            out.append(
                f'<text class="zn" x="{x + 12 + lw / 2:g}" y="{y + r["tag"] * 0.36:g}" text-anchor="middle" '
                f'style="fill:{colour}">{esc(label.upper())}</text>'
            )
        return "".join(out)

    def node(self, n: dict) -> str:
        r = self.ramp
        x, y, w, h = n["x"], n["y"], n["w"], n["h"]
        self.grid(f"node {n['id']}", x, y, w, h)
        kind = n.get("kind", "backend")
        self.use_node(kind)
        fill, stroke, dash, tag_stroke, tag_text = NODE[kind]
        d = f' stroke-dasharray="{dash}"' if dash else ""
        rx = n.get("rx", 16 if kind in ("state", "final") else 6)
        out = [
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{P["paper"]}"/>',
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="1"{d}/>',
        ]
        if n.get("shape") == "store":  # cylinder hint: one more arc at the top
            out.append(
                f'<path d="M {x} {y + 10} Q {x + w / 2} {y + 22} {x + w} {y + 10}" fill="none" stroke="{stroke}" stroke-width="0.8"/>'
            )
        tag = n.get("tag")
        top = y + 6
        if tag:
            tw = up4(text_width(tag, r["tag"], mono=True, tracking=0.08) + 10)
            out.append(
                f'<rect x="{x + 8}" y="{y + 8}" width="{tw}" height="{r["tag"] + 5}" rx="2" fill="none" '
                f'stroke="{tag_stroke}" stroke-width="0.8"/>'
            )
            out.append(
                f'<text class="tg" x="{x + 8 + tw / 2:g}" y="{y + 8 + r["tag"] + 1:g}" fill="{tag_text}" '
                f'text-anchor="middle">{esc(tag.upper())}</text>'
            )
            top = y + 8 + r["tag"] + 5
            if tw + 16 > w:
                self.err(f"node {n['id']}: tag wider than the box")
        if n.get("num"):
            size = min(h * 0.5, 32)
            out.append(
                f'<text class="wm" x="{x + w - 8}" y="{y + h - 8}" font-size="{size:g}" text-anchor="end">{esc(n["num"])}</text>'
            )
        names = str(n.get("name", "")).split("\n") if n.get("name") else []
        subs = n.get("sub", [])
        if isinstance(subs, str):
            subs = [subs]
        bullets = n.get("bullets", [])
        name_h = r["name"] + 4
        sub_h = r["sub"] + 4
        body_h = r["body"] * 1.45
        content = len(names) * name_h + len(subs) * sub_h + len(bullets) * body_h
        if bullets:
            content += 6
        area_top, area_bottom = top, y + h - 6
        if content > area_bottom - area_top + 2:
            self.err(f"node {n['id']}: text needs {content:g}px, the box has {area_bottom - area_top:g}px")
        cy = area_top + (area_bottom - area_top - content) / 2
        cx = x + w / 2
        for line in names:
            cy += name_h
            out.append(f'<text class="nm" x="{cx:g}" y="{cy - 4:g}" text-anchor="middle">{esc(line)}</text>')
            if text_width(line, r["name"]) > w - 12:
                self.err(f"node {n['id']}: name '{line}' wider than the box")
        for line in subs:
            cy += sub_h
            out.append(f'<text class="sb" x="{cx:g}" y="{cy - 4:g}" text-anchor="middle">{esc(line)}</text>')
            if text_width(line, r["sub"], mono=True) > w - 12:
                self.err(f"node {n['id']}: sublabel '{line}' wider than the box")
        if bullets:
            cy += 6
            bx = x + 14
            for line in bullets:
                cy += body_h
                out.append(f'<text class="bd" x="{bx:g}" y="{cy - 4:g}">{esc(line)}</text>')
                if text_width(line, r["body"]) * 0.92 > w - 24:
                    self.err(f"node {n['id']}: bullet '{line}' wider than the box")
        return "".join(out)

    def edge_path(self, pts: list[list[float]], radius: float = 8) -> str:
        d = [f"M {pts[0][0]:g},{pts[0][1]:g}"]
        for i in range(1, len(pts) - 1):
            (x0, y0), (x1, y1), (x2, y2) = pts[i - 1], pts[i], pts[i + 1]
            len_in = abs(x1 - x0) + abs(y1 - y0)
            len_out = abs(x2 - x1) + abs(y2 - y1)
            rr = min(radius, len_in / 2, len_out / 2)
            dxi, dyi = (x1 > x0) - (x1 < x0), (y1 > y0) - (y1 < y0)
            dxo, dyo = (x2 > x1) - (x2 < x1), (y2 > y1) - (y2 < y1)
            d.append(f"L {x1 - dxi * rr:g},{y1 - dyi * rr:g}")
            d.append(f"Q {x1:g},{y1:g} {x1 + dxo * rr:g},{y1 + dyo * rr:g}")
        d.append(f"L {pts[-1][0]:g},{pts[-1][1]:g}")
        return " ".join(d)

    def inside(self, px: float, py: float, n: dict, pad: float = 2) -> bool:
        return n["x"] - pad <= px <= n["x"] + n["w"] + pad and n["y"] - pad <= py <= n["y"] + n["h"] + pad

    def check_edge(self, e: dict, pts: list[list[float]]) -> None:
        name = f"edge {e.get('label', '') or pts[0]}"
        for p in pts:
            self.grid(name, *p)
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            if x0 != x1 and y0 != y1:
                self.err(f"{name}: diagonal segment {x0},{y0} -> {x1},{y1}")
        ends = [n for n in self.nodes.values() if self.inside(*pts[0], n) or self.inside(*pts[-1], n)]
        ends += [self.nodes[i] for i in e.get("through", []) if i in self.nodes]
        for n in self.nodes.values():
            if n in ends:
                continue
            for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                lo_x, hi_x, lo_y, hi_y = min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1)
                if lo_x < n["x"] + n["w"] and hi_x > n["x"] and lo_y < n["y"] + n["h"] and hi_y > n["y"]:
                    self.err(f"{name}: passes behind node {n['id']}")

    def edge(self, e: dict) -> tuple[str, str]:
        pts = e["points"]
        style = e.get("style", "flow")
        self.use_edge(style)
        colour, sw, dash, marker = EDGE[style]
        if e.get("dashed"):
            dash = "4,3"
        self.check_edge(e, pts)
        d = self.edge_path(pts)
        dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
        end = "" if e.get("no_arrow") else f' marker-end="{self.marker("open" if e.get("open") else marker)}"'
        start = f' marker-start="{self.marker(marker)}"' if e.get("both") else ""
        line = f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{sw}"{dash_attr}{end}{start}/>'
        if self.spec.get("animate") and e.get("step"):
            k = e["step"]
            line += (
                f'<path class="tk tk{k}" d="{d}" pathLength="100" fill="none" stroke="{colour}" stroke-width="{sw * 3.2:g}" '
                f'stroke-linecap="round" stroke-dasharray="8 120"/>'
            )
        label = ""
        if e.get("label") or (e.get("step") and not e.get("no_badge")):
            label = self.edge_label(e, pts, colour)
        return line, label

    def edge_label(self, e: dict, pts: list[list[float]], colour: str) -> str:
        r = self.ramp
        segs = list(zip(pts, pts[1:]))
        idx = e.get("label_seg")
        if idx is None:
            idx = max(range(len(segs)), key=lambda i: abs(segs[i][1][0] - segs[i][0][0]) + abs(segs[i][1][1] - segs[i][0][1]))
        (x0, y0), (x1, y1) = segs[idx]
        at = e.get("label_at", 0.5)
        mx, my = x0 + (x1 - x0) * at, y0 + (y1 - y0) * at
        text = e.get("label", "")
        lines = text.split("\n") if text else [""]
        width = max(text_width(line, r["label"], mono=True, tracking=0.06) for line in lines) + 10
        if e.get("step"):
            width += r["label"] * 2 + 4
        width = up4(width)
        height = up4(len(lines) * (r["label"] + 4) + 2)
        side = e.get("label_side")
        gap = 8
        if y0 == y1:  # horizontal segment: above (default) or below
            if side == "on":
                cy = my
            elif side == "below":
                cy = my + gap + height / 2
            else:
                cy = my - gap - height / 2
            cx = mx + e.get("label_dx", 0)
        else:  # vertical segment: right (default) or left
            if side == "on":
                cx = mx
            elif side == "left":
                cx = mx - gap - width / 2
            else:
                cx = mx + gap + width / 2
            cy = my + e.get("label_dy", 0)
        if not text:
            return self.badge(cx, cy, e["step"], colour)
        return self.label(cx, cy, text, colour, step=e.get("step"))

    def flow(self) -> str:
        out = [self.shapes("back")]
        for z in self.spec.get("zone", []):
            out.append(self.zone(z))
        lines, labels = [], []
        for e in self.spec.get("edge", []):
            line, label = self.edge(e)
            lines.append(line)
            labels.append(label)
        out.extend(lines)
        out.extend(self.zone_tabs)
        out.extend(labels)
        for n in self.spec.get("node", []):
            out.append(self.node(n))
        out.append(self.shapes("front"))
        for mx, my, mw, mh, text in self.masks:
            for n in self.nodes.values():
                if mx < n["x"] + n["w"] and mx + mw > n["x"] and my < n["y"] + n["h"] and my + mh > n["y"]:
                    self.err(f"label '{text}' overlaps node {n['id']}")
        for i, a in enumerate(self.masks):
            for b in self.masks[i + 1 :]:
                if a[0] < b[0] + b[2] and a[0] + a[2] > b[0] and a[1] < b[1] + b[3] and a[1] + a[3] > b[1]:
                    self.err(f"labels '{a[4]}' and '{b[4]}' overlap")
        for n in self.nodes.values():
            if n["x"] < 0 or n["y"] < 0 or n["x"] + n["w"] > self.width or n["y"] + n["h"] > self.height:
                self.err(f"node {n['id']} outside the canvas")
        if self.spec.get("animate"):
            out.append(self.animation())
        return "".join(out)

    def animation(self) -> str:
        steps = sorted({e["step"] for e in self.spec.get("edge", []) if e.get("step")})
        per = self.spec.get("step_seconds", 2)
        cycle = per * len(steps) + per
        share = per / cycle * 100
        css = [
            f".{self.slug} .tk{{opacity:0;animation:{self.slug}-tk {cycle}s linear infinite}}",
            f".{self.slug} .bg,.{self.slug} .cp{{animation:{self.slug}-hl {cycle}s linear infinite;transform-box:fill-box;transform-origin:center}}",
            f"@keyframes {self.slug}-tk{{0%{{stroke-dashoffset:8;opacity:0}}1%{{opacity:1}}"
            f"{share * 0.9:.2f}%{{stroke-dashoffset:-100;opacity:1}}{share:.2f}%{{opacity:0}}100%{{opacity:0;stroke-dashoffset:-100}}}}",
            f"@keyframes {self.slug}-hl{{0%{{opacity:1;transform:scale(1.18)}}{share * 0.9:.2f}%{{opacity:1;transform:scale(1.18)}}"
            f"{share:.2f}%{{opacity:.45;transform:scale(1)}}100%{{opacity:.45;transform:scale(1)}}}}",
        ]
        for i, k in enumerate(steps):
            delay = i * per - cycle
            css.append(f".{self.slug} .tk{k},.{self.slug} .bg{k},.{self.slug} .cp{k}{{animation-delay:{delay}s}}")
        css.append(
            f"@media (prefers-reduced-motion: reduce){{.{self.slug} .tk{{display:none}}"
            f".{self.slug} .bg,.{self.slug} .cp{{animation:none}}}}"
        )
        return "<style>" + "".join(css) + "</style>"

    # ---------------------------------------------------------------- sequence diagrams
    def sequence(self) -> str:
        r = self.ramp
        actors = self.spec["actor"]
        n = len(actors)
        left, right = 40, self.width - 40
        spacing = (right - left) / n
        xs = {a["id"]: left + spacing * (i + 0.5) for i, a in enumerate(actors)}
        box_w = min(up4(spacing - 24), 176)
        box_h = 52
        top = 24
        rows = self.spec.get("row", [])
        y = top + box_h + 36
        body: list[str] = []
        frames: list[dict] = []
        numbering = self.spec.get("numbered", False)
        count = 0
        layout = []
        for row in rows:
            kind = row["kind"]
            if kind in ("call", "return", "async", "accent", "track", "fallback", "push"):
                layout.append((row, y))
                y += 36
            elif kind == "self":
                layout.append((row, y))
                y += 44
            elif kind == "note":
                lines = row["text"].split("\n")
                hgt = 12 + len(lines) * (r["body"] + 4)
                layout.append((row, y - 10))
                y += hgt + 12
            elif kind in ("alt", "opt", "loop"):
                frames.append({"op": kind, "guard": row.get("guard", ""), "top": y - 8, "actors": row.get("actors"), "divs": []})
                y += 36
            elif kind == "else":
                frames[-1]["divs"].append((y - 8, row.get("guard", "[else]")))
                y += 28
            elif kind == "end":
                frames[-1]["bottom"] = y - 8
                y += 16
            else:
                raise LayoutError(f"{self.slug}: unknown row kind {kind}")
        bottom = y + 8
        # lifelines
        for a in actors:
            x = xs[a["id"]]
            body.append(
                f'<line x1="{x:g}" y1="{top + box_h}" x2="{x:g}" y2="{bottom}" stroke="{rgba("ink", 0.25)}" '
                f'stroke-width="1" stroke-dasharray="4,4"/>'
            )
        # frames (behind messages)
        for f in frames:
            ids = f["actors"] or [a["id"] for a in actors]
            fx = min(xs[i] for i in ids) - spacing / 2 + 8
            fw = max(xs[i] for i in ids) - min(xs[i] for i in ids) + spacing - 16
            fy, fb = f["top"], f.get("bottom", bottom - 8)
            body.append(
                f'<rect x="{fx:g}" y="{fy:g}" width="{fw:g}" height="{fb - fy:g}" rx="4" fill="{rgba("ink", 0.02)}" '
                f'stroke="{rgba("ink", 0.22)}" stroke-width="1"/>'
            )
            op = f["op"].upper()
            body.append(
                f'<rect x="{fx:g}" y="{fy:g}" width="40" height="16" rx="2" fill="{P["paper"]}" stroke="{rgba("ink", 0.22)}"/>'
                f'<text class="lb" x="{fx + 20:g}" y="{fy + 11:g}" fill="{P["muted"]}" text-anchor="middle">{op}</text>'
                f'<rect x="{fx + 44:g}" y="{fy + 2:g}" width="{text_width(f["guard"], r["label"], mono=True, tracking=0.06) + 8:g}" '
                f'height="12" fill="{P["paper"]}"/>'
                f'<text class="lb" x="{fx + 48:g}" y="{fy + 11:g}" fill="{P["muted"]}">{esc(f["guard"])}</text>'
            )
            for dy, guard in f["divs"]:
                body.append(
                    f'<line x1="{fx + 8:g}" y1="{dy:g}" x2="{fx + fw - 8:g}" y2="{dy:g}" stroke="{rgba("ink", 0.2)}" '
                    f'stroke-dasharray="4,3"/><rect x="{fx + 8:g}" y="{dy + 5:g}" '
                    f'width="{text_width(guard, r["label"], mono=True, tracking=0.06) + 8:g}" height="12" fill="{P["paper"]}"/>'
                    f'<text class="lb" x="{fx + 12:g}" y="{dy + 14:g}" fill="{P["muted"]}">{esc(guard)}</text>'
                )
        # actors
        for a in actors:
            x = xs[a["id"]]
            node = {"id": a["id"], "x": up4(x - box_w / 2) - 4 if (x - box_w / 2) % 4 else x - box_w / 2, "y": top,
                    "w": box_w, "h": box_h, "kind": a.get("kind", "backend"), "name": a["name"], "sub": a.get("sub", [])}
            node["x"] = int(round(node["x"] / 4) * 4)
            body.append(self.node(node))
        # rows
        for row, ry in layout:
            kind = row["kind"]
            if kind == "note":
                ids = row.get("over", [actors[0]["id"]])
                lines = row["text"].split("\n")
                nx0 = min(xs[i] for i in ids) - (spacing / 2 - 12 if len(ids) > 1 else 0)
                nx1 = max(xs[i] for i in ids) + (spacing / 2 - 12 if len(ids) > 1 else 0)
                tw = max(text_width(line, r["body"]) for line in lines) * 0.92 + 20
                if nx1 - nx0 < tw:
                    mid = (nx0 + nx1) / 2
                    nx0, nx1 = mid - tw / 2, mid + tw / 2
                nh = 12 + len(lines) * (r["body"] + 4)
                body.append(
                    f'<rect x="{nx0:g}" y="{ry:g}" width="{nx1 - nx0:g}" height="{nh:g}" rx="4" fill="{P["paper2"]}" '
                    f'stroke="{rgba("ink", 0.15)}" stroke-width="0.8"/>'
                )
                for i, line in enumerate(lines):
                    body.append(
                        f'<text class="bd" x="{(nx0 + nx1) / 2:g}" y="{ry + 6 + (i + 1) * (r["body"] + 4) - 3:g}" '
                        f'text-anchor="middle" font-style="italic">{esc(line)}</text>'
                    )
                continue
            x0 = xs[row["from"]]
            style = {"call": "flow", "return": "dashed", "async": "dashed", "accent": "track", "track": "track",
                     "fallback": "fallback", "push": "push", "self": "flow"}[kind]
            self.use_edge(style)
            colour, sw, dash, marker = EDGE[style]
            mk = self.marker("open" if kind == "async" else marker)
            dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
            text = row.get("text", "")
            label_x = None
            if numbering:
                count += 1
            if kind == "self":
                d = f"M {x0 + 4:g},{ry:g} H {x0 + 36:g} Q {x0 + 44:g},{ry:g} {x0 + 44:g},{ry + 8:g} V {ry + 16:g} Q {x0 + 44:g},{ry + 24:g} {x0 + 36:g},{ry + 24:g} H {x0 + 6:g}"
                body.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{sw}"{dash_attr} marker-end="{mk}"/>')
                tx, ty, anchor = x0 + 52, ry + 15, "start"
            else:
                x1 = xs[row["to"]]
                direction = 1 if x1 > x0 else -1
                body.append(
                    f'<line x1="{x0 + 4 * direction:g}" y1="{ry:g}" x2="{x1 - 5 * direction:g}" y2="{ry:g}" stroke="{colour}" '
                    f'stroke-width="{sw}"{dash_attr} marker-end="{mk}"/>'
                )
                tx, ty, anchor = (x0 + 22 * direction + x1) / 2 if numbering else (x0 + x1) / 2, ry - 7, "middle"
                if row.get("align") == "start":
                    tx, anchor = min(x0, x1) + 12, "start"
                label_x = (min(x0, x1), max(x0, x1))
            tw = text_width(text, r["body"]) * 0.92
            if label_x and tw > label_x[1] - label_x[0] - (40 if numbering else 16) and anchor == "middle":
                self.err(f"message '{text}' wider than its arrow")
            if text:
                mx = tx - tw / 2 - 4 if anchor == "middle" else tx - 4
                body.append(
                    f'<rect x="{mx:g}" y="{ty - r["body"]:g}" width="{tw + 8:g}" height="{r["body"] + 4:g}" fill="{P["paper"]}"/>'
                )
                weight = ' font-weight="600"' if kind in ("accent", "fallback") else ""
                body.append(
                    f'<text class="bd" x="{tx:g}" y="{ty:g}" text-anchor="{anchor}" fill="{colour if kind != "call" else P["ink"]}"{weight}>{esc(text)}</text>'
                )
            if numbering:
                bx = x0 + (14 if kind == "self" or xs.get(row.get("to"), x0) > x0 else -14)
                body.append(self.badge(bx, ry, count, colour))
        self.height = bottom + 8
        return "".join(body)

    # ---------------------------------------------------------------- build
    def render(self) -> str:
        if self.kind == "sequence":
            body = self.sequence()
        else:
            body = self.flow()
        legend_top = self.height
        legend, rows = self.legend(legend_top)
        total = legend_top + (36 + rows * 20 + 4 if legend else 8)
        return self.wrap(body + legend, total)


def check_contrast() -> list[str]:
    def lum(h: str) -> float:
        h = h.lstrip("#")
        rgb = [int(h[i : i + 2], 16) / 255 for i in (0, 2, 4)]
        f = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
        return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]

    problems = []
    for role in ("ink", "muted", "soft", "accent", "link", "alert"):
        a, b = sorted((lum(P[role]), lum(P["paper"])), reverse=True)
        ratio = (a + 0.05) / (b + 0.05)
        if ratio < 4.5:
            problems.append(f"palette: {role} {P[role]} is {ratio:.2f}:1 on paper, below 4.5:1")
    return problems


def preview(svg: str, spec: dict) -> str:
    title = esc(spec["title"])
    width = spec["width"]
    return (
        '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="UTF-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1.0">\n'
        f"<title>{title}</title>\n<style>\n"
        "*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}\n"
        f"body{{font-family:{SANS};background:{P['paper']};color:{P['ink']};min-height:100vh;display:flex;"
        "align-items:center;justify-content:center;padding:3rem 2rem}\n"
        ".frame{max-width:1640px;width:100%}.diagram-container{width:100%;overflow-x:auto}\n"
        f".eyebrow{{font-family:{MONO};font-size:.66rem;letter-spacing:.18em;text-transform:uppercase;color:{P['muted']};margin-bottom:.5rem}}\n"
        f"h1{{font-family:{SERIF};font-weight:400;font-size:2rem;margin-bottom:1.5rem}}\n"
        f"svg{{width:100%;min-width:{width}px;height:auto;display:block}}\n"
        "@media print{.diagram-container{overflow-x:visible}svg{min-width:0}}\n"
        "</style>\n</head>\n<body>\n<div class=\"frame\">\n"
        f'<p class="eyebrow">reliable-push · Diagram Design</p>\n<h1>{title}</h1>\n'
        f'<div class="diagram-container">\n{svg}</div>\n</div>\n</body>\n</html>\n'
    )


def find_chrome() -> str | None:
    candidates = [
        os.environ.get("CHROME", ""),
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ]
    for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome"):
        found = shutil.which(name)
        if found:
            candidates.append(found)
    return next((c for c in candidates if c and os.path.exists(c)), None)


def render_png(svg_path: pathlib.Path, png_path: pathlib.Path, width: int, height: int, scale: int = 2) -> None:
    """Static frame of the SVG (reduced motion) as a PNG, width x height CSS pixels at the given scale."""
    chrome = find_chrome()
    if not chrome:
        raise LayoutError("no Chrome or Chromium found for --png (set CHROME=<path>)")
    png_path.parent.mkdir(parents=True, exist_ok=True)
    page = png_path.with_suffix(".render.html")
    page.write_text(
        f'<!DOCTYPE html><html><head><style>html,body{{margin:0;background:{P["paper"]}}}img{{display:block}}</style>'
        f'</head><body><img src="{svg_path.resolve().as_uri()}" width="{width}" height="{height}"></body></html>',
        encoding="utf-8",
    )
    subprocess.run(
        [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-prefers-reduced-motion",
         f"--force-device-scale-factor={scale}", f"--window-size={width},{height}",
         f"--screenshot={png_path.resolve()}", page.resolve().as_uri()],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120,
    )
    page.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify instead of writing")
    parser.add_argument("--html", metavar="DIR", help="also write HTML previews into DIR")
    parser.add_argument("--png", metavar="DIR", help="also render PNGs into DIR (needs Chrome or Chromium)")
    parser.add_argument("only", nargs="*", help="build only these slugs")
    args = parser.parse_args()

    problems = check_contrast()
    sources = sorted(SRC.glob("*.toml"))
    if args.only:
        sources = [s for s in sources if s.stem in args.only]
    for source in sources:
        with source.open("rb") as fh:
            spec = tomllib.load(fh)
        try:
            diagram = Diagram(spec, source.stem)
            svg = diagram.render()
        except (KeyError, LayoutError) as exc:
            problems.append(f"{source.stem}: {exc}")
            continue
        problems.extend(diagram.errors)
        target = (OUT / spec.get("output", f"{diagram.slug}.svg")).resolve()
        if args.check:
            current = target.read_text(encoding="utf-8") if target.exists() else None
            if current != svg:
                problems.append(f"{target.relative_to(ROOT)} is stale: run python3 scripts/diagrams.py")
        before = target.read_text(encoding="utf-8") if target.exists() else None
        if not args.check:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(svg, encoding="utf-8")
        size = svg.split('viewBox="0 0 ')[1].split('"')[0].split()
        static_png = spec.get("png")
        if static_png:  # a committed static frame (for slides and documents), refreshed with the SVG
            png_target = (OUT / static_png).resolve()
            if args.check:
                if not png_target.exists():
                    problems.append(f"{png_target.relative_to(ROOT)} is missing: run python3 scripts/diagrams.py")
            elif before != svg or not png_target.exists():
                try:
                    render_png(target, png_target, int(float(size[0])), int(float(size[1])))
                except (LayoutError, subprocess.SubprocessError) as exc:
                    problems.append(f"{png_target.relative_to(ROOT)} not refreshed ({exc}): re-run where Chrome is installed")
        if args.png and not args.check:
            render_png(target, pathlib.Path(args.png) / f"{diagram.slug}.png", int(float(size[0])), int(float(size[1])))
        if args.html:
            out_dir = pathlib.Path(args.html)
            out_dir.mkdir(parents=True, exist_ok=True)
            (out_dir / f"{diagram.slug}.html").write_text(preview(svg, spec), encoding="utf-8")
    for problem in problems:
        print(f"diagrams: {problem}", file=sys.stderr)
    if problems:
        print(f"diagrams: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print(f"diagrams: {'checked' if args.check else 'built'} {len(sources)} diagram(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
