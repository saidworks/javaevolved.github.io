# Generator Benchmarks

Performance comparison of execution methods for the HTML and OG card generators, measured on 112 snippets across 11 categories.

## CI Benchmark (GitHub Actions)

[![Benchmark Generator](https://github.com/javaevolved/javaevolved.github.io/actions/workflows/benchmark.yml/badge.svg)](https://github.com/javaevolved/javaevolved.github.io/actions/workflows/benchmark.yml)

The most important benchmark runs on GitHub Actions because it measures performance in the environment where the generator actually executes — CI. The [Benchmark Generator](https://github.com/javaevolved/javaevolved.github.io/actions/workflows/benchmark.yml) workflow is manually triggered and runs across **Ubuntu**, **Windows**, and **macOS**.

### Why CI benchmarks matter

On a developer machine, repeated runs benefit from warm OS file caches — the operating system keeps recently read files in RAM, making subsequent reads nearly instant. This masks real-world performance differences. Python also benefits from `__pycache__/` bytecode that persists between runs.

In CI, **every workflow run starts on a fresh runner**. There is no `__pycache__/`, no warm OS cache, no JBang compilation cache. This is the environment where the deploy workflow runs, so these numbers reflect actual production performance.

### How the CI benchmark works

The workflow has three jobs:

1. **`benchmark`** — Runs Phase 1 (training/build costs) and Phase 2 (steady-state execution) on each OS. All tools are installed in the same job, so this measures raw execution speed after setup.

2. **`build-jar`** — Builds the fat JAR and AOT cache on each OS, then uploads them as workflow artifacts. This simulates what the `build-generator.yml` workflow does weekly: produce the JAR and AOT cache and store them in the GitHub Actions cache.

3. **`ci-cold-start`** — The key benchmark. Runs on a **completely fresh runner** that has never executed Java or Python in the current job. It downloads the JAR and AOT artifacts (simulating the `actions/cache/restore` step in the deploy workflow), then measures a single cold run of each method. This is the closest simulation of what happens when the deploy workflow runs:
   - **Python** has no `__pycache__/` — it must interpret every `.py` file from scratch
   - **Fat JAR** must load and link all classes on a cold JVM
   - **Fat JAR + AOT** loads pre-linked classes from the `.aot` file, skipping class loading entirely

   The `setup-java` and `setup-python` actions are required to provide the runtimes, but they don't warm up the generator code. The first invocation of `java` or `python3` in this job is the benchmark measurement itself.

### Why Java AOT wins in CI

Java's AOT cache (JEP 483) snapshots the result of class loading and linking from a training run into a `.aot` file. This file is platform-specific and ~21 MB. When restored from the actions cache, the JVM skips the expensive class discovery, verification, and linking steps that normally happen on first run.

Python's `__pycache__/` serves a similar purpose — it caches compiled bytecode so Python doesn't re-parse `.py` files. But `__pycache__/` is not committed to git or stored in CI caches, so **Python always pays full interpretation cost in CI**. Java AOT, by contrast, is stored in the actions cache and restored before each deploy.

## Local Benchmark

See [LOCAL.md](LOCAL.md) for local benchmark results and instructions to run on your own machine.
