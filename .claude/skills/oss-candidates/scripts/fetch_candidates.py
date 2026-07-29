#!/usr/bin/env python3
"""Mechanical fetch+filter step for the oss-candidates skill.

Pulls open "good first issue"/"help wanted"-equivalent issues from the vetted
repo list via `gh search issues`, drops issues that are mechanically
disqualified (assigned, too many comments, <24h old, already logged in
weekly_candidates.md), then fetches full body+comments for the survivors so
the calling agent can do the qualitative judgment pass (scope, claiming
comments, linked PRs) without re-querying GitHub per candidate.

Requires: `gh` CLI, authenticated (`gh auth status`).
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_LABELS = {
    "vllm-project/vllm": ["good first issue", "help wanted"],
    "sgl-project/sglang": ["good first issue", "help wanted"],
    "huggingface/peft": ["good first issue", "help wanted"],
    "ray-project/ray": ["good-first-issue", "contribution-welcome"],
    "huggingface/accelerate": ["good first issue", "help wanted"],
    "pytorch/pytorch": ["good first issue", "help wanted"],
    "dmlc/xgboost": ["good first issue", "help wanted"],
    "ai-dynamo/dynamo": ["good first issue", "help wanted"],
}

MAX_COMMENTS = 10          # mechanical low-competition cutoff (agent still judges the rest)
MIN_AGE_HOURS = 24         # skip brand-new issues likely to attract a rush
LOG_FILE = Path(__file__).resolve().parents[4] / "experiments" / "open-source" / "weekly_candidates.md"


def gh_json(args: list[str]):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"warning: gh {' '.join(args)} failed: {result.stderr.strip()}", file=sys.stderr)
        return []
    return json.loads(result.stdout or "[]")


def already_logged_urls() -> set[str]:
    if not LOG_FILE.exists():
        return set()
    text = LOG_FILE.read_text()
    return set(re.findall(r"https://github\.com/\S+/issues/\d+", text))


def search_repo(repo: str, labels: list[str]) -> dict[str, dict]:
    found = {}
    for label in labels:
        issues = gh_json([
            "search", "issues",
            "--repo", repo,
            "--label", label,
            "--state", "open",
            "--json", "number,title,url,labels,commentsCount,assignees,createdAt",
            "--limit", "50",
        ])
        for issue in issues:
            found[issue["url"]] = issue
    return found


def main():
    seen = already_logged_urls()
    cutoff = datetime.now(timezone.utc) - timedelta(hours=MIN_AGE_HOURS)

    survivors = []
    for repo, labels in REPO_LABELS.items():
        for url, issue in search_repo(repo, labels).items():
            if url in seen:
                continue
            if issue["assignees"]:
                continue
            if issue["commentsCount"] > MAX_COMMENTS:
                continue
            created = datetime.strptime(issue["createdAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
            if created > cutoff:
                continue
            issue["repo"] = repo
            survivors.append(issue)

    survivors.sort(key=lambda i: i["commentsCount"])

    enriched = []
    for issue in survivors:
        number = issue["number"]
        repo = issue["repo"]
        detail = gh_json([
            "issue", "view", str(number),
            "--repo", repo,
            "--json", "title,url,body,comments,createdAt,labels",
        ])
        if not detail:
            continue
        enriched.append({
            "repo": repo,
            "number": number,
            "title": detail.get("title", issue["title"]),
            "url": issue["url"],
            "labels": [l["name"] for l in issue["labels"]],
            "commentsCount": issue["commentsCount"],
            "createdAt": issue["createdAt"],
            "body": (detail.get("body") or "")[:1500],
            "comments": [
                {"author": c.get("author", {}).get("login", "?"), "body": (c.get("body") or "")[:500]}
                for c in (detail.get("comments") or [])[:10]
            ],
        })

    print(json.dumps(enriched, indent=2))
    print(
        f"\n// fetched {len(enriched)} mechanically-eligible candidates across {len(REPO_LABELS)} repos "
        f"(already-logged issues and assigned/high-comment/brand-new issues excluded)",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
