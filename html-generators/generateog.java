///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3
//DEPS org.apache.xmlgraphics:batik-transcoder:1.18
//DEPS org.apache.xmlgraphics:batik-codec:1.18

import module java.base;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/**
 * Generate Open Graph SVG cards (1200×630) for each pattern.
 * Light theme, side-by-side Old/Modern code, slug title at top.
 *
 * Usage: jbang html-generators/generate-og.java [category/slug]
 *        No arguments → generate all patterns.
 */
static final String CONTENT_DIR = "content";
static final String OUTPUT_DIR = "site/og";
static final String CATEGORIES_FILE = "html-generators/categories.properties";

static final ObjectMapper JSON_MAPPER = new ObjectMapper();
static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
static final Map<String, ObjectMapper> MAPPERS = Map.of(
    "json", JSON_MAPPER, "yaml", YAML_MAPPER, "yml", YAML_MAPPER
);

static final SequencedMap<String, String> CATEGORY_DISPLAY = loadProperties(CATEGORIES_FILE);

// ── Light-theme palette ────────────────────────────────────────────────
static final String BG        = "#ffffff";
static final String BORDER    = "#d8d8e0";
static final String TEXT       = "#1a1a2e";
static final String TEXT_MUTED = "#6b7280";
static final String OLD_BG     = "#fef2f2";
static final String MODERN_BG  = "#eff6ff";
static final String OLD_ACCENT = "#dc2626";
static final String GREEN      = "#059669";
static final String ACCENT     = "#6366f1";
static final String BADGE_BG   = "#f3f4f6";

// ── Syntax highlight colors (VS Code light-inspired) ───────────────────
static final String SYN_KEYWORD    = "#7c3aed"; // purple — keywords & modifiers
static final String SYN_TYPE       = "#0e7490"; // teal   — type names
static final String SYN_STRING     = "#059669"; // green  — strings & chars
static final String SYN_COMMENT    = "#6b7280"; // gray   — comments
static final String SYN_ANNOTATION = "#b45309"; // amber  — annotations
static final String SYN_NUMBER     = "#c2410c"; // orange — numeric literals
static final String SYN_DEFAULT    = "#1a1a2e"; // dark   — everything else

static final Set<String> JAVA_KEYWORDS = Set.of(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "null",
    "package", "private", "protected", "public", "record", "return", "sealed",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "var", "void", "volatile", "when",
    "while", "with", "yield", "permits", "non-sealed", "module", "open", "opens",
    "requires", "exports", "provides", "to", "uses", "transitive",
    "true", "false"
);

static final Pattern SYN_PATTERN = Pattern.compile(
    "(?<comment>//.*)|" +                           // line comment
    "(?<blockcomment>/\\*.*?\\*/)|" +               // block comment (single line)
    "(?<annotation>@\\w+)|" +                       // annotation
    "(?<string>\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|" + // strings
    "(?<number>\\b\\d[\\d_.]*[dDfFlL]?\\b)|" +      // numbers
    "(?<word>\\b[A-Za-z_]\\w*\\b)|" +               // words (keywords or identifiers)
    "(?<other>[^\\s])"                              // other single chars
);

// ── Dimensions ─────────────────────────────────────────────────────────
static final int W = 1200, H = 630;
static final int PAD = 40;
static final int HEADER_H = 100;
static final int FOOTER_H = 56;
static final int CODE_TOP = HEADER_H;
static final int CODE_H = H - HEADER_H - FOOTER_H;
static final int COL_W = (W - PAD * 2 - 20) / 2;  // 20px gap between panels
static final int CODE_PAD = 14;                     // padding inside each panel
static final int LABEL_H = 32;                      // space reserved for label above code
static final int USABLE_W = COL_W - CODE_PAD * 2;  // usable width for code text
static final int USABLE_H = CODE_H - LABEL_H - CODE_PAD; // usable height for code text
static final double CHAR_WIDTH_RATIO = 0.6;         // monospace char width ≈ 0.6 × font size
static final double LINE_HEIGHT_RATIO = 1.55;       // line height ≈ 1.55 × font size
static final int MIN_CODE_FONT = 9;
static final int MAX_CODE_FONT = 16;

// ── Helpers ────────────────────────────────────────────────────────────
static final Path FONT_CACHE = Path.of(System.getProperty("user.home"), ".cache", "javaevolved-fonts");

static final Map<String, String> FONT_URLS = Map.of(
    "Inter-Regular.ttf",
        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuLyfMZg.ttf",
    "Inter-Medium.ttf",
        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuI6fMZg.ttf",
    "Inter-SemiBold.ttf",
        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuGKYMZg.ttf",
    "Inter-Bold.ttf",
        "https://fonts.gstatic.com/s/inter/v20/UcCO3FwrK3iLTeHuS_nVMrMxCp50SjIw2boKoduKmMEVuFuYMZg.ttf",
    "JetBrainsMono-Regular.ttf",
        "https://fonts.gstatic.com/s/jetbrainsmono/v24/tDbY2o-flEEny0FZhsfKu5WU4zr3E_BX0PnT8RD8yKxjPQ.ttf",
    "JetBrainsMono-Medium.ttf",
        "https://fonts.gstatic.com/s/jetbrainsmono/v24/tDbY2o-flEEny0FZhsfKu5WU4zr3E_BX0PnT8RD8-qxjPQ.ttf"
);

/** Download fonts to cache and register with Java's graphics environment. */
static void ensureFonts() throws IOException {
    Files.createDirectories(FONT_CACHE);
    var ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
    for (var entry : FONT_URLS.entrySet()) {
        var file = FONT_CACHE.resolve(entry.getKey());
        if (!Files.exists(file)) {
            IO.println("Downloading %s...".formatted(entry.getKey()));
            try (var in = URI.create(entry.getValue()).toURL().openStream()) {
                Files.copy(in, file);
            }
        }
        try {
            var font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, file.toFile());
            ge.registerFont(font);
        } catch (java.awt.FontFormatException e) {
            IO.println("[WARN] Could not register font %s: %s".formatted(entry.getKey(), e.getMessage()));
        }
    }
}

/** Convert an SVG string to a PNG file using Batik. */
static void svgToPng(String svgContent, Path pngPath) throws Exception {
    var input = new TranscoderInput(new java.io.StringReader(svgContent));
    try (var out = new java.io.BufferedOutputStream(Files.newOutputStream(pngPath))) {
        var transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) W * 2);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) H * 2);
        transcoder.transcode(input, new TranscoderOutput(out));
    }
}

static SequencedMap<String, String> loadProperties(String file) {
    try {
        var map = new LinkedHashMap<String, String>();
        for (var line : Files.readAllLines(Path.of(file))) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            var idx = line.indexOf('=');
            if (idx > 0) map.put(line.substring(0, idx).strip(), line.substring(idx + 1).strip());
        }
        return map;
    } catch (IOException e) { throw new UncheckedIOException(e); }
}

static String xmlEscape(String s) {
    return s == null ? ""
        : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
           .replace("\"", "&quot;").replace("'", "&apos;");
}

record Snippet(JsonNode node) {
    String get(String f)        { return node.get(f).asText(); }
    String slug()               { return get("slug"); }
    String category()           { return get("category"); }
    String title()              { return get("title"); }
    String jdkVersion()         { return get("jdkVersion"); }
    String oldCode()            { return get("oldCode"); }
    String modernCode()         { return get("modernCode"); }
    String oldApproach()        { return get("oldApproach"); }
    String modernApproach()     { return get("modernApproach"); }
    String oldLabel()           { return get("oldLabel"); }
    String modernLabel()        { return get("modernLabel"); }
    String key()                { return category() + "/" + slug(); }
    String catDisplay()         { return CATEGORY_DISPLAY.get(category()); }
}

SequencedMap<String, Snippet> loadAllSnippets() throws IOException {
    var snippets = new LinkedHashMap<String, Snippet>();
    for (var cat : CATEGORY_DISPLAY.sequencedKeySet()) {
        var catDir = Path.of(CONTENT_DIR, cat);
        if (!Files.isDirectory(catDir)) continue;
        var sorted = new ArrayList<Path>();
        for (var ext : MAPPERS.keySet()) {
            try (var stream = Files.newDirectoryStream(catDir, "*." + ext)) {
                stream.forEach(sorted::add);
            }
        }
        sorted.sort(Path::compareTo);
        for (var path : sorted) {
            var ext = path.getFileName().toString();
            ext = ext.substring(ext.lastIndexOf('.') + 1);
            var snippet = new Snippet(MAPPERS.get(ext).readTree(path.toFile()));
            snippets.put(snippet.key(), snippet);
        }
    }
    return snippets;
}

// ── SVG rendering ──────────────────────────────────────────────────────

/** Compute the best font size (MIN–MAX) that fits both code blocks in their panels. */
static int bestFontSize(List<String> oldLines, List<String> modernLines) {
    int maxChars = Math.max(
        oldLines.stream().mapToInt(String::length).max().orElse(1),
        modernLines.stream().mapToInt(String::length).max().orElse(1)
    );
    int maxLines = Math.max(oldLines.size(), modernLines.size());

    // Largest font where the widest line fits the panel width
    int byWidth  = (int) (USABLE_W / (maxChars * CHAR_WIDTH_RATIO));
    // Largest font where all lines fit the panel height
    int byHeight = (int) (USABLE_H / (maxLines * LINE_HEIGHT_RATIO));

    return Math.max(MIN_CODE_FONT, Math.min(MAX_CODE_FONT, Math.min(byWidth, byHeight)));
}

/** Truncate lines to fit the panel height at the given font size. */
static List<String> fitLines(List<String> lines, int fontSize) {
    int lineH = (int) (fontSize * LINE_HEIGHT_RATIO);
    int maxLines = USABLE_H / lineH;
    if (lines.size() <= maxLines) return lines;
    var truncated = new ArrayList<>(lines.subList(0, maxLines - 1));
    truncated.add("...");
    return truncated;
}

/** Syntax-highlight a single line of Java, returning SVG tspan fragments. */
static String highlightLine(String line) {
    if (line.equals("...")) return xmlEscape(line);
    var sb = new StringBuilder();
    var m = SYN_PATTERN.matcher(line);
    int last = 0;
    while (m.find()) {
        // append any skipped whitespace
        if (m.start() > last) sb.append(xmlEscape(line.substring(last, m.start())));
        last = m.end();
        var token = m.group();
        String color = null;
        if (m.group("comment") != null || m.group("blockcomment") != null) {
            color = SYN_COMMENT;
        } else if (m.group("annotation") != null) {
            color = SYN_ANNOTATION;
        } else if (m.group("string") != null) {
            color = SYN_STRING;
        } else if (m.group("number") != null) {
            color = SYN_NUMBER;
        } else if (m.group("word") != null) {
            if (JAVA_KEYWORDS.contains(token)) {
                color = SYN_KEYWORD;
            } else if (Character.isUpperCase(token.charAt(0))) {
                color = SYN_TYPE;
            }
        }
        if (color != null) {
            sb.append("<tspan fill=\"").append(color).append("\">").append(xmlEscape(token)).append("</tspan>");
        } else {
            sb.append(xmlEscape(token));
        }
    }
    if (last < line.length()) sb.append(xmlEscape(line.substring(last)));
    return sb.toString();
}

/** Render a column of code lines as SVG <text> elements with syntax highlighting. */
static String renderCodeBlock(List<String> lines, int x, int y, int lineH) {
    var sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
        sb.append("    <text x=\"%d\" y=\"%d\" class=\"code\" xml:space=\"preserve\">%s</text>\n"
            .formatted(x, y + i * lineH, highlightLine(lines.get(i))));
    }
    return sb.toString();
}

static String generateSvg(Snippet s) {
    int leftX  = PAD;
    int rightX = PAD + COL_W + 20;
    int labelY = CODE_TOP + 26;
    int codeY  = CODE_TOP + 52;

    var rawOldLines = s.oldCode().lines().toList();
    var rawModernLines = s.modernCode().lines().toList();

    int fontSize = bestFontSize(rawOldLines, rawModernLines);
    int lineH = (int) (fontSize * LINE_HEIGHT_RATIO);

    var oldLines = fitLines(rawOldLines, fontSize);
    var modernLines = fitLines(rawModernLines, fontSize);

    return """
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
  <defs>
    <style>
      .title    { font: 700 24px/1 'Inter', sans-serif; fill: %s; }
      .category { font: 600 13px/1 'Inter', sans-serif; fill: %s; }
      .label    { font: 600 11px/1 'Inter', sans-serif; text-transform: uppercase; letter-spacing: 0.05em; }
      .code     { font: 400 %dpx/1 'JetBrains Mono', monospace; fill: %s; }
      .footer   { font: 500 13px/1 'Inter', sans-serif; fill: %s; }
      .brand    { font: 700 14px/1 'Inter', sans-serif; fill: %s; }
    </style>
    <clipPath id="clip-left">
      <rect x="%d" y="%d" width="%d" height="%d" rx="8"/>
    </clipPath>
    <clipPath id="clip-right">
      <rect x="%d" y="%d" width="%d" height="%d" rx="8"/>
    </clipPath>
  </defs>

  <!-- Background -->
  <rect width="%d" height="%d" rx="16" fill="%s"/>
  <rect x="0.5" y="0.5" width="%d" height="%d" rx="16" fill="none" stroke="%s" stroke-width="1"/>

  <!-- Header: category badge + title -->
  <rect x="%d" y="%d" width="%d" height="22" rx="6" fill="%s"/>
  <text x="%d" y="%d" class="category">%s</text>
  <text x="%d" y="%d" class="title">%s</text>

  <!-- Left panel: Old code -->
  <rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="%s"/>
  <rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="none" stroke="%s" stroke-width="0.5"/>
  <text x="%d" y="%d" class="label" fill="%s">✗  %s</text>
  <g clip-path="url(#clip-left)">
%s  </g>

  <!-- Right panel: Modern code -->
  <rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="%s"/>
  <rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="none" stroke="%s" stroke-width="0.5"/>
  <text x="%d" y="%d" class="label" fill="%s">✓  %s</text>
  <g clip-path="url(#clip-right)">
%s  </g>

  <!-- Footer -->
  <text x="%d" y="%d" class="footer">JDK %s+</text>
  <text x="%d" y="%d" class="brand">javaevolved.github.io</text>
</svg>
""".formatted(
        // viewBox
        W, H, W, H,
        // style fills
        TEXT, TEXT_MUTED, fontSize, TEXT, TEXT_MUTED, ACCENT,
        // clip-left
        leftX, CODE_TOP, COL_W, CODE_H,
        // clip-right
        rightX, CODE_TOP, COL_W, CODE_H,
        // background
        W, H, BG, W - 1, H - 1, BORDER,
        // header badge
        PAD, 28, xmlEscape(s.catDisplay()).length() * 8 + 16, BADGE_BG,
        PAD + 8, 43, xmlEscape(s.catDisplay()),
        // title
        PAD, 76, xmlEscape(s.title()),
        // left panel bg + border
        leftX, CODE_TOP, COL_W, CODE_H, OLD_BG,
        leftX, CODE_TOP, COL_W, CODE_H, BORDER,
        // left label
        leftX + 14, labelY, OLD_ACCENT, xmlEscape(s.oldLabel()),
        // left code
        renderCodeBlock(oldLines, leftX + 14, codeY, lineH),
        // right panel bg + border
        rightX, CODE_TOP, COL_W, CODE_H, MODERN_BG,
        rightX, CODE_TOP, COL_W, CODE_H, BORDER,
        // right label
        rightX + 14, labelY, GREEN, xmlEscape(s.modernLabel()),
        // right code
        renderCodeBlock(modernLines, rightX + 14, codeY, lineH),
        // footer text
        PAD, H - 22, s.jdkVersion(),
        W - PAD, H - 22,
        // need text-anchor for brand — handled in the template
        ""  // unused but keeps format args aligned
    ).replace(
        // Right-align the brand text
        "class=\"brand\">javaevolved.github.io</text>",
        "class=\"brand\" text-anchor=\"end\">javaevolved.github.io</text>"
    );
}

// ── Main ───────────────────────────────────────────────────────────────
void main(String... args) throws Exception {
    ensureFonts();

    var allSnippets = loadAllSnippets();
    IO.println("Loaded %d snippets".formatted(allSnippets.size()));

    // Filter to a single slug if provided
    Collection<Snippet> targets;
    if (args.length > 0) {
        var key = args[0];
        if (!allSnippets.containsKey(key)) {
            IO.println("Unknown pattern: " + key);
            IO.println("Available: " + String.join(", ", allSnippets.keySet()));
            System.exit(1);
        }
        targets = List.of(allSnippets.get(key));
    } else {
        targets = allSnippets.values();
    }

    int count = 0;
    for (var s : targets) {
        var dir = Path.of(OUTPUT_DIR, s.category());
        Files.createDirectories(dir);
        var svg = generateSvg(s);
        Files.writeString(dir.resolve(s.slug() + ".svg"), svg);
        svgToPng(svg, dir.resolve(s.slug() + ".png"));
        count++;
    }
    IO.println("Generated %d SVG+PNG card(s) in %s/".formatted(count, OUTPUT_DIR));
}
