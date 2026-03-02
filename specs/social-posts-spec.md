# Automated Weekly Social Posts — Specification

## Problem
Post one pattern per week to X/Twitter and Bluesky, covering all 112 patterns (~2 years of content). Fully automated via GitHub Actions with no manual steps.

## Approach
Use a **GitHub Actions scheduled workflow** that:
1. Reads a pre-shuffled queue file (`content/social-queue.txt`) listing all pattern keys
2. Each week, picks the next unposted pattern, posts to X and Bluesky
3. Commits the updated queue pointer back to the repo to track progress
4. When all 112 are exhausted, reshuffles and starts over

### Why a queue file?
- Deterministic: you can review/reorder upcoming posts
- Resumable: survives workflow failures, repo changes
- Auditable: git history shows what was posted when

## Post Format
```
☕ {title}

{summary}

{oldLabel} → {modernLabel} (JDK {jdkVersion}+)

🔗 https://javaevolved.github.io/{category}/{slug}.html

#Java #JavaEvolved
```

## Implementation

### 1. Social Queue Generator
**File:** `html-generators/generate-social-queue.java`

JBang script that reads all content files, shuffles them, and writes `content/social-queue.txt` (one `category/slug` per line).

### 2. Social Post Script
**File:** `html-generators/social-post.sh`

Shell script that:
- Reads the next unposted line from `content/social-queue.txt`
- Loads the pattern's JSON/YAML to build the post text
- Posts to X/Twitter via API v2 (OAuth 1.0a)
- Posts to Bluesky via AT Protocol API
- Updates the pointer file (`content/social-queue-pointer.txt`)

### 3. GitHub Actions Workflow
**File:** `.github/workflows/social-post.yml`

- Schedule: weekly cron (e.g., Tuesday 15:00 UTC)
- Runs the post script
- Commits updated queue state back to repo

## Required GitHub Secrets
| Secret | Purpose |
|--------|---------|
| `TWITTER_API_KEY` | X API v2 OAuth 1.0a consumer key |
| `TWITTER_API_SECRET` | X API v2 OAuth 1.0a consumer secret |
| `TWITTER_ACCESS_TOKEN` | X API v2 user access token |
| `TWITTER_ACCESS_SECRET` | X API v2 user access secret |
| `BLUESKY_HANDLE` | Bluesky handle (e.g., javaevolved.bsky.social) |
| `BLUESKY_APP_PASSWORD` | Bluesky app password |

## Design Decisions
- **Text-only posts** with URL — platforms unfurl the OG card automatically from `og:image` meta tags
- **Random order** via pre-shuffled queue for variety across categories
- **Reshuffles** when all 112 patterns are exhausted
- **Post script uses `curl`** for both APIs (no extra dependencies)
- **Queue state** tracked via a pointer file containing the current line number
