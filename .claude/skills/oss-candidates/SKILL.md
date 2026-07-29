---
name: oss-candidates
description: Use when picking the next open-source issue(s) to work on for the ML-Sys OSS contribution track, or when asked to refresh/find new OSS candidate issues from the vetted repo shortlist (vllm, sglang, peft, ray, accelerate, pytorch, xgboost, dynamo).
---

# OSS Candidates

## Overview

Finds the next batch of open-source issues worth attempting, drawn from the vetted repo
shortlist in `experiments/open-source/oss_contribution_targets.md`. Run manually — there's no
schedule enforcing cadence, run it whenever you want a fresh batch (weekly is reasonable).

## When to use

- You've finished (or given up on) your current OSS issue and want the next one.
- It's been a while and you want to check what's newly available.
- Not for finding issues outside the 8 vetted repos — to add a 9th repo, update
  `REPO_LABELS` in `scripts/fetch_candidates.py` and `oss_contribution_targets.md` first.

## Workflow

### 1. Run the mechanical fetch/filter script

```
python3 .claude/skills/oss-candidates/scripts/fetch_candidates.py
```

Requires the `gh` CLI, already authenticated as this user. It queries `gh search issues`
across all 8 repos for both label variants (`good first issue`/`help wanted`, or the repo's
actual label names — see the script for the map), then mechanically drops:

- issues already logged in a prior run (reads `experiments/open-source/weekly_candidates.md`)
- assigned issues
- issues with more than 10 comments (cheap low-competition proxy)
- issues opened in the last 24h (avoid the initial rush of interest)

For everything that survives, it fetches the full issue body plus up to 10 comments and
prints one JSON array to stdout — that payload has everything needed for step 2 without
further GitHub calls.

### 2. Apply judgment filters the script can't make

From the JSON, narrow to the best candidates using:

- **Scope**: reads like roughly 1-2 weeks of solo work for someone new to that codebase but
  comfortable with Python and ML-serving/training internals. Skip one-liners/typo fixes and
  skip open-ended/multi-month asks ("rewrite X", pure research questions).
- **Actually unclaimed**: scan the `comments` text for claiming language ("I'll take this",
  "working on it", "assigned to me") — the mechanical `assignees` filter misses claims made
  only in a comment.
- **No PR already open**: scan `comments`/`body` for a link to an already-open PR fixing it.
- **Has real scoping**: prefer issues where a maintainer already sketched the intended
  approach over bare "PRs welcome" placeholders with no detail — those are usually harder
  than they look.
- **Diversity**: avoid picking every result from a single repo when good options exist
  elsewhere, but don't force spread over fit.

Pick up to 5 (fewer is fine if fewer qualify — say so rather than padding the list).

### 3. Log the picks

Prepend a new dated section to the TOP of `experiments/open-source/weekly_candidates.md`
(create the file if it doesn't exist — most-recent-first):

```markdown
## YYYY-MM-DD
- **[repo#number] issue title](url)** — labels: `x`, `y`. N comments, unassigned.
  Rationale: <1-2 sentences tying it to the scope/competition criteria above>
```

Use today's date. Don't truncate or reorder earlier sections — the script's dedup depends
on every previously-logged issue URL still being present somewhere in this file.

### 4. Report to the user

Summarize the picks directly in chat: repo, title, URL, one-line rationale each. This is a
local interactive run — no push notification or email needed.

## Common mistakes

- Trusting `commentsCount` alone as "unclaimed" — always read the comment text too.
- Truncating/overwriting `weekly_candidates.md` instead of prepending — breaks the dedup
  the next time this skill runs.
- Picking issues with zero maintainer scoping — these read as approachable but are often
  underspecified and turn into a design negotiation before any code gets written.
