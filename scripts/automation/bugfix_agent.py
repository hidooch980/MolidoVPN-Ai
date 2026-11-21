#!/usr/bin/env python3
"""Bounded, evidence-driven CI recovery agent for MolidoVPN-Ai.

Never edits application source, changes signing material, merges PRs, publishes
artifacts, or spends money. Retries only first-attempt runs whose failed jobs
are all positively classified as transient infrastructure failures. Other
failures become a deduplicated GitHub issue with run/job evidence.
"""
from __future__ import annotations
import json, os, re, sys, urllib.error, urllib.request, zipfile
from io import BytesIO
from typing import Any

API = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GITHUB_TOKEN"]
RUN_ID = int(os.environ["BUGFIX_RUN_ID"])
RUN_ATTEMPT = int(os.environ.get("BUGFIX_RUN_ATTEMPT", "1"))
TRANSIENT = re.compile(
    r"HTTP\s*429|status code[: ]+429|rate.?limit|HTTP\s*5\d\d|"
    r"status code[: ]+5\d\d|Temporary failure in name resolution|"
    r"UnknownHostException|Connection reset|Connection timed out|"
    r"Read timed out|Could not resolve host|TLS handshake timeout|"
    r"503 Service Unavailable|502 Bad Gateway|504 Gateway Time-out|"
    r"runner.*(lost|disconnected)|The runner has received a shutdown signal",
    re.IGNORECASE,
)
CODE_FAILURE = re.compile(
    r"AssertionError|AssertionFailedError|Tests? failed|"
    r"Compilation error|Compilation failed|Unresolved reference|"
    r"e: file:.*\.kt:|FAILED\s*$|Fatal signal|FATAL EXCEPTION|"
    r"java\.lang\.(NullPointerException|IllegalStateException|"
    r"IndexOutOfBoundsException)|pytest.*failed|FAILED \(failures=",
    re.IGNORECASE | re.MULTILINE,
)

def api(method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "MolidoVPN-Autonomous-BugFix-Agent",
    })
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def download_job_log(job_id: int) -> str:
    req = urllib.request.Request(
        f"{API}/repos/{REPO}/actions/jobs/{job_id}/logs",
        headers={"Authorization": f"Bearer {TOKEN}",
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "MolidoVPN-Autonomous-BugFix-Agent"},
    )
    with urllib.request.urlopen(req, timeout=60) as response:
        archive = response.read()
    with zipfile.ZipFile(BytesIO(archive)) as zipped:
        return "\n".join(
            zipped.read(name).decode("utf-8", errors="replace")
            for name in zipped.namelist()
            if name.lower().endswith((".txt", ".log"))
        )

def classify_log(log: str) -> str:
    if CODE_FAILURE.search(log):
        return "code_or_test_failure"
    if TRANSIENT.search(log):
        return "transient_infrastructure"
    return "unknown"

def issue_title(run_id: int) -> str:
    return f"[BugFix Agent] CI failure in run #{run_id}"

def create_or_find_issue(run: dict[str, Any], reports: list[dict[str, Any]]) -> str:
    title = issue_title(RUN_ID)
    existing = api("GET", f"/repos/{REPO}/issues?state=open&per_page=100")
    for item in existing:
        if item.get("title") == title and "pull_request" not in item:
            return item["html_url"]
    lines = [
        "## Automated diagnosis", "",
        f"- Workflow: **{run.get('name', 'unknown')}**",
        f"- Run: [#{RUN_ID}]({run.get('html_url', '')})",
        f"- Commit: {run.get('head_sha', 'unknown')}",
        f"- Attempt: {RUN_ATTEMPT}", "",
        "The agent did not apply an unverified source-code patch. Review the failed jobs, fix the root cause, and add a regression test before closing this issue.", "",
    ]
    for report in reports:
        lines += [
            f"### {report['name']}",
            f"- Classification: {report['classification']}",
            f"- Job: [#{report['id']}]({report['url']})", "",
            "Log excerpt:", report["excerpt"][:5000], "",
        ]
    created = api("POST", f"/repos/{REPO}/issues", {
        "title": title, "body": "\n".join(lines), "labels": ["bug"],
    })
    return created["html_url"]

def main() -> int:
    run = api("GET", f"/repos/{REPO}/actions/runs/{RUN_ID}")
    jobs_data = api("GET", f"/repos/{REPO}/actions/runs/{RUN_ID}/jobs?per_page=100")
    failed = [job for job in jobs_data.get("jobs", []) if job.get("conclusion") == "failure"]
    if not failed:
        print(f"BugFix Agent: run #{RUN_ID} has no failed jobs; nothing to repair.")
        return 0
    reports = []
    for job in failed:
        try:
            log = download_job_log(job["id"])
            classification, excerpt = classify_log(log), log[-12000:]
        except Exception as exc:
            classification = "unknown"
            excerpt = f"Unable to retrieve job log: {type(exc).__name__}: {exc}"
        reports.append({
            "id": job["id"], "name": job.get("name", "unnamed job"),
            "url": job.get("html_url", ""), "classification": classification,
            "excerpt": excerpt,
        })
    all_transient = all(r["classification"] == "transient_infrastructure" for r in reports)
    if all_transient and RUN_ATTEMPT < 2:
        api("POST", f"/repos/{REPO}/actions/runs/{RUN_ID}/rerun-failed-jobs", {})
        print(f"BugFix Agent: safely retried transient infrastructure failures once in run #{RUN_ID}.")
        return 0
    issue_url = create_or_find_issue(run, reports)
    print(f"BugFix Agent: created or reused evidence issue: {issue_url}")
    print("No application source was modified. Code/test/security failures require reviewed repair.")
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as exc:
        print(f"BugFix Agent API error: HTTP {exc.code}: {exc.read().decode(errors='replace')[:2000]}", file=sys.stderr)
        sys.exit(1)
    except Exception as exc:
        print(f"BugFix Agent failed closed: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
