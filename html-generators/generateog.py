#!/usr/bin/env python3
"""
Generate Open Graph SVG+PNG cards (1200×630) for each pattern.
Light theme, side-by-side Old/Modern code, slug title at top.
Python equivalent of generateog.java — produces identical output.

Usage: python html-generators/generateog.py [category/slug]
       No arguments → generate all patterns.

Requires: cairosvg (pip install cairosvg)
"""

import json
import os
import re
import sys
import glob as glob_mod
from collections import OrderedDict

try:
    import yaml
except ImportError:
    yaml = None

try:
    import cairosvg
except ImportError:
    cairosvg = None

CONTENT_DIR = "content"
OUTPUT_DIR = "site/og"
CATEGORIES_FILE = "html-generators/categories.properties"

# ── Light-theme palette ─────────────────────────────────────────────────
BG         = "#ffffff"
BORDER     = "#d8d8e0"
TEXT       = "#1a1a2e"
TEXT_MUTED = "#6b7280"
OLD_BG     = "#fef2f2"
MODERN_BG  = "#eff6ff"
OLD_ACCENT = "#dc2626"
GREEN      = "#059669"
ACCENT     = "#6366f1"
BADGE_BG   = "#f3f4f6"

# ── Syntax highlight colors (VS Code light-inspired) ────────────────────
SYN_KEYWORD    = "#7c3aed"
SYN_TYPE       = "#0e7490"
SYN_STRING     = "#059669"
SYN_COMMENT    = "#6b7280"
SYN_ANNOTATION = "#b45309"
SYN_NUMBER     = "#c2410c"
SYN_DEFAULT    = "#1a1a2e"

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "null",
    "package", "private", "protected", "public", "record", "return", "sealed",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "var", "void", "volatile", "when",
    "while", "with", "yield", "permits", "non-sealed", "module", "open", "opens",
    "requires", "exports", "provides", "to", "uses", "transitive",
    "true", "false",
}

SYN_PATTERN = re.compile(
    r"(?P<comment>//.*)|"
    r"(?P<blockcomment>/\*.*?\*/)|"
    r"(?P<annotation>@\w+)|"
    r'(?P<string>"""[\s\S]*?"""|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\')|'
    r"(?P<number>\b\d[\d_.]*[dDfFlL]?\b)|"
    r"(?P<word>\b[A-Za-z_]\w*\b)|"
    r"(?P<other>[^\s])"
)

# ── Dimensions ──────────────────────────────────────────────────────────
W = 1200
H = 630
PAD = 40
HEADER_H = 100
FOOTER_H = 56
CODE_TOP = HEADER_H
CODE_H = H - HEADER_H - FOOTER_H
COL_W = (W - PAD * 2 - 20) // 2
CODE_PAD = 14
LABEL_H = 32
USABLE_W = COL_W - CODE_PAD * 2
USABLE_H = CODE_H - LABEL_H - CODE_PAD
CHAR_WIDTH_RATIO = 0.6
LINE_HEIGHT_RATIO = 1.55
MIN_CODE_FONT = 9
MAX_CODE_FONT = 16


# ── Helpers ─────────────────────────────────────────────────────────────

def load_properties(path):
    props = OrderedDict()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            idx = line.find("=")
            if idx > 0:
                props[line[:idx].strip()] = line[idx + 1:].strip()
    return props


CATEGORY_DISPLAY = load_properties(CATEGORIES_FILE)


def read_auto(path):
    with open(path, encoding="utf-8") as f:
        if path.endswith((".yaml", ".yml")):
            if yaml is None:
                raise ImportError("PyYAML is required for YAML files: pip install pyyaml")
            return yaml.safe_load(f)
        return json.load(f)


def xml_escape(s):
    if s is None:
        return ""
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;").replace("'", "&apos;"))


def load_all_snippets():
    snippets = OrderedDict()
    for cat in CATEGORY_DISPLAY:
        cat_dir = os.path.join(CONTENT_DIR, cat)
        if not os.path.isdir(cat_dir):
            continue
        files = []
        for ext in ("json", "yaml", "yml"):
            files.extend(glob_mod.glob(os.path.join(cat_dir, f"*.{ext}")))
        files.sort()
        for path in files:
            data = read_auto(path)
            key = f"{data['category']}/{data['slug']}"
            snippets[key] = data
    return snippets


# ── Syntax highlighting ─────────────────────────────────────────────────

def highlight_line(line):
    if line == "...":
        return xml_escape(line)
    result = []
    last = 0
    for m in SYN_PATTERN.finditer(line):
        if m.start() > last:
            result.append(xml_escape(line[last:m.start()]))
        last = m.end()
        token = m.group()
        color = None
        if m.group("comment") or m.group("blockcomment"):
            color = SYN_COMMENT
        elif m.group("annotation"):
            color = SYN_ANNOTATION
        elif m.group("string"):
            color = SYN_STRING
        elif m.group("number"):
            color = SYN_NUMBER
        elif m.group("word"):
            if token in JAVA_KEYWORDS:
                color = SYN_KEYWORD
            elif token[0].isupper():
                color = SYN_TYPE
        if color:
            result.append(f'<tspan fill="{color}">{xml_escape(token)}</tspan>')
        else:
            result.append(xml_escape(token))
    if last < len(line):
        result.append(xml_escape(line[last:]))
    return "".join(result)


# ── SVG rendering ───────────────────────────────────────────────────────

def best_font_size(old_lines, modern_lines):
    max_chars = max(
        max((len(l) for l in old_lines), default=1),
        max((len(l) for l in modern_lines), default=1),
    )
    max_lines = max(len(old_lines), len(modern_lines))
    by_width = int(USABLE_W / (max_chars * CHAR_WIDTH_RATIO))
    by_height = int(USABLE_H / (max_lines * LINE_HEIGHT_RATIO))
    return max(MIN_CODE_FONT, min(MAX_CODE_FONT, min(by_width, by_height)))


def fit_lines(lines, font_size):
    line_h = int(font_size * LINE_HEIGHT_RATIO)
    max_lines = USABLE_H // line_h
    if len(lines) <= max_lines:
        return lines
    truncated = list(lines[:max_lines - 1])
    truncated.append("...")
    return truncated


def render_code_block(lines, x, y, line_h):
    parts = []
    for i, line in enumerate(lines):
        parts.append(
            f'    <text x="{x}" y="{y + i * line_h}" class="code" xml:space="preserve">'
            f'{highlight_line(line)}</text>\n'
        )
    return "".join(parts)


def generate_svg(data):
    left_x = PAD
    right_x = PAD + COL_W + 20
    label_y = CODE_TOP + 26
    code_y = CODE_TOP + 52

    old_lines = data["oldCode"].split("\n")
    modern_lines = data["modernCode"].split("\n")

    font_size = best_font_size(old_lines, modern_lines)
    line_h = int(font_size * LINE_HEIGHT_RATIO)

    old_lines = fit_lines(old_lines, font_size)
    modern_lines = fit_lines(modern_lines, font_size)

    cat_display = CATEGORY_DISPLAY.get(data["category"], data["category"])
    badge_width = len(cat_display) * 8 + 16

    old_code_svg = render_code_block(old_lines, left_x + 14, code_y, line_h)
    modern_code_svg = render_code_block(modern_lines, right_x + 14, code_y, line_h)

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <style>
      .title    {{ font: 700 24px/1 'Inter', sans-serif; fill: {TEXT}; }}
      .category {{ font: 600 13px/1 'Inter', sans-serif; fill: {TEXT_MUTED}; }}
      .label    {{ font: 600 11px/1 'Inter', sans-serif; text-transform: uppercase; letter-spacing: 0.05em; }}
      .code     {{ font: 400 {font_size}px/1 'JetBrains Mono', monospace; fill: {TEXT}; }}
      .footer   {{ font: 500 13px/1 'Inter', sans-serif; fill: {TEXT_MUTED}; }}
      .brand    {{ font: 700 14px/1 'Inter', sans-serif; fill: {ACCENT}; }}
    </style>
    <clipPath id="clip-left">
      <rect x="{left_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8"/>
    </clipPath>
    <clipPath id="clip-right">
      <rect x="{right_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8"/>
    </clipPath>
  </defs>

  <!-- Background -->
  <rect width="{W}" height="{H}" rx="16" fill="{BG}"/>
  <rect x="0.5" y="0.5" width="{W - 1}" height="{H - 1}" rx="16" fill="none" stroke="{BORDER}" stroke-width="1"/>

  <!-- Header: category badge + title -->
  <rect x="{PAD}" y="28" width="{badge_width}" height="22" rx="6" fill="{BADGE_BG}"/>
  <text x="{PAD + 8}" y="43" class="category">{xml_escape(cat_display)}</text>
  <text x="{PAD}" y="76" class="title">{xml_escape(data['title'])}</text>

  <!-- Left panel: Old code -->
  <rect x="{left_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8" fill="{OLD_BG}"/>
  <rect x="{left_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8" fill="none" stroke="{BORDER}" stroke-width="0.5"/>
  <text x="{left_x + 14}" y="{label_y}" class="label" fill="{OLD_ACCENT}">\u2717  {xml_escape(data['oldLabel'])}</text>
  <g clip-path="url(#clip-left)">
{old_code_svg}  </g>

  <!-- Right panel: Modern code -->
  <rect x="{right_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8" fill="{MODERN_BG}"/>
  <rect x="{right_x}" y="{CODE_TOP}" width="{COL_W}" height="{CODE_H}" rx="8" fill="none" stroke="{BORDER}" stroke-width="0.5"/>
  <text x="{right_x + 14}" y="{label_y}" class="label" fill="{GREEN}">\u2713  {xml_escape(data['modernLabel'])}</text>
  <g clip-path="url(#clip-right)">
{modern_code_svg}  </g>

  <!-- Footer -->
  <text x="{PAD}" y="{H - 22}" class="footer">JDK {data['jdkVersion']}+</text>
  <text x="{W - PAD}" y="{H - 22}" class="brand" text-anchor="end">javaevolved.github.io</text>
</svg>
"""


def svg_to_png(svg_content, png_path):
    if cairosvg is None:
        raise ImportError("cairosvg is required for PNG generation: pip install cairosvg")
    cairosvg.svg2png(
        bytestring=svg_content.encode("utf-8"),
        write_to=png_path,
        output_width=W * 2,
        output_height=H * 2,
    )


# ── Main ────────────────────────────────────────────────────────────────

def main():
    all_snippets = load_all_snippets()
    print(f"Loaded {len(all_snippets)} snippets")

    # Filter to a single slug if provided
    if len(sys.argv) > 1:
        key = sys.argv[1]
        if key not in all_snippets:
            print(f"Unknown pattern: {key}")
            print(f"Available: {', '.join(all_snippets.keys())}")
            sys.exit(1)
        targets = {key: all_snippets[key]}
    else:
        targets = all_snippets

    count = 0
    for key, data in targets.items():
        cat = data["category"]
        slug = data["slug"]
        out_dir = os.path.join(OUTPUT_DIR, cat)
        os.makedirs(out_dir, exist_ok=True)

        svg = generate_svg(data)
        svg_path = os.path.join(out_dir, f"{slug}.svg")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write(svg)

        png_path = os.path.join(out_dir, f"{slug}.png")
        svg_to_png(svg, png_path)
        count += 1

    print(f"Generated {count} SVG+PNG card(s) in {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
