#!/usr/bin/env bash
# Benchmark the HTML and OG card generators across languages and execution methods.
#
# Phase 1: Training/build cost (one-time setup)
#   - Python first run (creates __pycache__)
#   - Java AOT training run (creates .aot)
#   - JBang export (creates fat JAR)
#
# Phase 2: Steady-state execution (5 runs averaged)
#   - Python (warm, with __pycache__)
#   - JBang (from source)
#   - Fat JAR (java -jar)
#   - Fat JAR + AOT (java -XX:AOTCache)
#
# Usage:
#   ./html-generators/benchmark/run.sh            # print results to stdout
#   ./html-generators/benchmark/run.sh --update    # also update LOCAL.md

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

JAR="html-generators/generate.jar"
AOT="html-generators/generate.aot"
OG_JAR="html-generators/generateog.jar"
OG_AOT="html-generators/generateog.aot"
STEADY_RUNS=5
UPDATE_MD=false
[[ "${1:-}" == "--update" ]] && UPDATE_MD=true

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
time_once() {
  /usr/bin/time -p "$@" > /dev/null 2>&1 | awk '/^real/ {print $2}'
  # fallback: capture from stderr
  local t
  t=$( { /usr/bin/time -p "$@" > /dev/null; } 2>&1 | awk '/^real/ {print $2}' )
  echo "$t"
}

measure() {
  local t
  t=$( { /usr/bin/time -p "$@" > /dev/null; } 2>&1 | awk '/^real/ {print $2}' )
  echo "$t"
}

avg_runs() {
  local n="$1"; shift
  local sum=0
  for ((i = 1; i <= n; i++)); do
    local t
    t=$(measure "$@")
    sum=$(echo "$sum + $t" | bc)
  done
  echo "scale=2; $sum / $n" | bc | sed 's/^\./0./'
}

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------
CPU=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | awk -F: '/Model name/ {gsub(/^ +/,"",$2); print $2}' || echo "unknown")
RAM=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%d GB", $1/1024/1024/1024}' || free -h 2>/dev/null | awk '/Mem:/ {print $2}' || echo "unknown")
JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
JBANG_VER=$(jbang version 2>/dev/null || echo "n/a")
PYTHON_VER=$(python3 --version 2>/dev/null | awk '{print $2}' || echo "n/a")
OS=$(uname -s)
SNIPPET_COUNT=$(find content -name '*.json' | wc -l | tr -d ' ')

echo ""
echo "Environment: $CPU · $RAM · Java $JAVA_VER · $OS"
echo "Snippets:    $SNIPPET_COUNT across 11 categories"
echo ""

# ---------------------------------------------------------------------------
# Phase 1: Training / build cost (one-time)
# ---------------------------------------------------------------------------
echo "=== Phase 1: Training / Build Cost (one-time) ==="
echo ""

# Clean up any cached state
rm -f html-generators/generate.aot html-generators/generate.jar
rm -f html-generators/generateog.aot html-generators/generateog.jar
find html-generators -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

# Python first run (populates __pycache__)
PY_TRAIN=$(measure python3 html-generators/generate.py)
echo "  Python first run (creates __pycache__):   ${PY_TRAIN}s"

# JBang export (creates fat JAR)
JBANG_EXPORT=$(measure jbang export fatjar --force --output "$JAR" html-generators/generate.java)
echo "  JBang export (creates fat JAR):            ${JBANG_EXPORT}s"

# AOT training run (creates .aot from JAR)
AOT_TRAIN=$(measure java -XX:AOTCacheOutput="$AOT" -jar "$JAR")
echo "  AOT training run (creates .aot):           ${AOT_TRAIN}s"

echo ""
echo "--- OG Card Generator ---"
echo ""

OG_PY_TRAIN=$(measure python3 html-generators/generateog.py)
echo "  Python first run (creates __pycache__):   ${OG_PY_TRAIN}s"

OG_JBANG_EXPORT=$(measure jbang export fatjar --force --output "$OG_JAR" html-generators/generateog.java)
echo "  JBang export (creates fat JAR):            ${OG_JBANG_EXPORT}s"

OG_AOT_TRAIN=$(measure java -XX:AOTCacheOutput="$OG_AOT" -jar "$OG_JAR")
echo "  AOT training run (creates .aot):           ${OG_AOT_TRAIN}s"

echo ""

# ---------------------------------------------------------------------------
# Phase 2: Steady-state execution (averaged over $STEADY_RUNS runs)
# ---------------------------------------------------------------------------
echo "=== Phase 2: Steady-State Execution (avg of $STEADY_RUNS runs) ==="
echo ""

PY_STEADY=$(avg_runs $STEADY_RUNS python3 html-generators/generate.py)
echo "  Python (warm):           ${PY_STEADY}s"

JBANG_STEADY=$(avg_runs $STEADY_RUNS jbang html-generators/generate.java)
echo "  JBang (from source):     ${JBANG_STEADY}s"

JAR_STEADY=$(avg_runs $STEADY_RUNS java -jar "$JAR")
echo "  Fat JAR:                 ${JAR_STEADY}s"

AOT_STEADY=$(avg_runs $STEADY_RUNS java -XX:AOTCache="$AOT" -jar "$JAR")
echo "  Fat JAR + AOT:           ${AOT_STEADY}s"

echo ""
echo "--- OG Card Generator ---"
echo ""

OG_PY_STEADY=$(avg_runs $STEADY_RUNS python3 html-generators/generateog.py)
echo "  Python (warm):           ${OG_PY_STEADY}s"

OG_JBANG_STEADY=$(avg_runs $STEADY_RUNS jbang html-generators/generateog.java)
echo "  JBang (from source):     ${OG_JBANG_STEADY}s"

OG_JAR_STEADY=$(avg_runs $STEADY_RUNS java -jar "$OG_JAR")
echo "  Fat JAR:                 ${OG_JAR_STEADY}s"

OG_AOT_STEADY=$(avg_runs $STEADY_RUNS java -XX:AOTCache="$OG_AOT" -jar "$OG_JAR")
echo "  Fat JAR + AOT:           ${OG_AOT_STEADY}s"

echo ""

# ---------------------------------------------------------------------------
# Phase 3: CI cold start (no caches, simulates fresh runner)
# ---------------------------------------------------------------------------
echo "=== Phase 3: CI Cold Start (fresh runner, no caches) ==="
echo ""

find html-generators -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true
PY_CI=$(measure python3 html-generators/generate.py)
echo "  Python (no __pycache__): ${PY_CI}s"

jbang cache clear > /dev/null 2>&1 || true
JBANG_CI=$(measure jbang html-generators/generate.java)
echo "  JBang (no cache):        ${JBANG_CI}s"

JAR_CI=$(measure java -jar "$JAR")
echo "  Fat JAR:                 ${JAR_CI}s"

AOT_CI=$(measure java -XX:AOTCache="$AOT" -jar "$JAR")
echo "  Fat JAR + AOT:           ${AOT_CI}s"

echo ""
echo "--- OG Card Generator ---"
echo ""

find html-generators -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true
OG_PY_CI=$(measure python3 html-generators/generateog.py)
echo "  Python (no __pycache__): ${OG_PY_CI}s"

OG_JAR_CI=$(measure java -jar "$OG_JAR")
echo "  Fat JAR:                 ${OG_JAR_CI}s"

OG_AOT_CI=$(measure java -XX:AOTCache="$OG_AOT" -jar "$OG_JAR")
echo "  Fat JAR + AOT:           ${OG_AOT_CI}s"

echo ""

# ---------------------------------------------------------------------------
# Optionally update LOCAL.md
# ---------------------------------------------------------------------------
if $UPDATE_MD; then
  MD="html-generators/benchmark/LOCAL.md"
  cat > "$MD" <<EOF
# Local Benchmark Results

Local benchmark results from \`run.sh\`. These will differ from CI because of OS file caching and warm \`__pycache__/\`.

## HTML Generator

### Phase 1: Training / Build Cost (one-time)

These are one-time setup costs, comparable across languages.

| Step | Time | What it does |
|------|------|-------------|
| Python first run | ${PY_TRAIN}s | Interprets source, creates \`__pycache__\` bytecode |
| JBang export | ${JBANG_EXPORT}s | Compiles source + bundles dependencies into fat JAR |
| AOT training run | ${AOT_TRAIN}s | Runs JAR once to record class loading, produces \`.aot\` cache |

### Phase 2: Steady-State Execution (avg of $STEADY_RUNS runs)

After one-time setup, these are the per-run execution times.

| Method | Avg Time | Notes |
|--------|---------|-------|
| **Fat JAR + AOT** | **${AOT_STEADY}s** | Fastest; pre-loaded classes from AOT cache |
| **Fat JAR** | ${JAR_STEADY}s | JVM class loading on every run |
| **JBang** | ${JBANG_STEADY}s | Includes JBang launcher overhead |
| **Python** | ${PY_STEADY}s | Uses cached \`__pycache__\` bytecode |

### Phase 3: CI Cold Start (simulated locally)

Clears \`__pycache__/\` and JBang cache, then measures a single run. On a local machine the OS file cache still helps, so these numbers are faster than true CI.

| Method | Time | Notes |
|--------|------|-------|
| **Fat JAR + AOT** | **${AOT_CI}s** | AOT cache ships pre-loaded classes |
| **Fat JAR** | ${JAR_CI}s | JVM class loading from scratch |
| **JBang** | ${JBANG_CI}s | Must compile source before running |
| **Python** | ${PY_CI}s | No \`__pycache__\`; full interpretation |

## OG Card Generator

### Phase 1: Training / Build Cost (one-time)

| Step | Time | What it does |
|------|------|-------------|
| Python first run | ${OG_PY_TRAIN}s | Generates SVG+PNG via cairosvg |
| JBang export | ${OG_JBANG_EXPORT}s | Compiles source + bundles Batik dependencies into fat JAR |
| AOT training run | ${OG_AOT_TRAIN}s | Runs JAR once to record class loading, produces \`.aot\` cache |

### Phase 2: Steady-State Execution (avg of $STEADY_RUNS runs)

| Method | Avg Time | Notes |
|--------|---------|-------|
| **Fat JAR + AOT** | **${OG_AOT_STEADY}s** | Fastest; pre-loaded classes from AOT cache |
| **Fat JAR** | ${OG_JAR_STEADY}s | JVM class loading on every run |
| **JBang** | ${OG_JBANG_STEADY}s | Includes JBang launcher overhead |
| **Python** | ${OG_PY_STEADY}s | Uses cached \`__pycache__\` bytecode |

### Phase 3: CI Cold Start (simulated locally)

| Method | Time | Notes |
|--------|------|-------|
| **Fat JAR + AOT** | **${OG_AOT_CI}s** | AOT cache ships pre-loaded classes |
| **Fat JAR** | ${OG_JAR_CI}s | JVM class loading from scratch |
| **Python** | ${OG_PY_CI}s | No \`__pycache__\`; full interpretation |

## How each method works

- **Python** caches compiled bytecode in \`__pycache__/\` after the first run, similar to how Java's AOT cache works. But this cache is local-only and not available in CI.
- **Java AOT** (JEP 483) snapshots ~3,300 pre-loaded classes from a training run into a \`.aot\` file, eliminating class loading overhead on subsequent runs. The \`.aot\` file is stored in the GitHub Actions cache.
- **JBang** compiles and caches internally but adds launcher overhead on every invocation.
- **Fat JAR** (\`java -jar\`) loads and links all classes from scratch each time.

## AOT Cache Setup

\`\`\`bash
# HTML generator
jbang export fatjar --force --output html-generators/generate.jar html-generators/generate.java
java -XX:AOTCacheOutput=html-generators/generate.aot -jar html-generators/generate.jar
java -XX:AOTCache=html-generators/generate.aot -jar html-generators/generate.jar

# OG card generator
jbang export fatjar --force --output html-generators/generateog.jar html-generators/generateog.java
java -XX:AOTCacheOutput=html-generators/generateog.aot -jar html-generators/generateog.jar
java -XX:AOTCache=html-generators/generateog.aot -jar html-generators/generateog.jar
\`\`\`

## Environment

| | |
|---|---|
| **CPU** | $CPU |
| **RAM** | $RAM |
| **Java** | OpenJDK $JAVA_VER (Temurin) |
| **JBang** | $JBANG_VER |
| **Python** | $PYTHON_VER |
| **OS** | $OS |

## Reproduce

\`\`\`bash
./html-generators/benchmark/run.sh            # print results to stdout
./html-generators/benchmark/run.sh --update    # also update this file
\`\`\`
EOF
  echo "Updated $MD"
fi
