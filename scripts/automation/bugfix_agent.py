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
from urllib.parse import urlparse

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

class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected:
            source = urlparse(req.full_url)
            target = urlparse(newurl)
            # Never forward a bearer token across hosts, ports, or an HTTPS
            # downgrade. Signed storage redirects are expected to be separate
            # hosts and must receive no GitHub Authorization header.
            if target.netloc != source.netloc or target.scheme.lower() != "https":
                redirected.remove_header("Authorization")
        return redirected


MAX_LOG_ARCHIVE_BYTES = 20 * 1024 * 1024
MAX_LOG_FILES = 100
MAX_LOG_MEMBER_BYTES = 2 * 1024 * 1024
MAX_LOG_TOTAL_BYTES = 10 * 1024 * 1024
MAX_LOG_COMPRESSION_RATIO = 200


def extract_job_log(archive: bytes) -> str:
    """Extract bounded text logs only; reject oversized or suspicious ZIP payloads."""
    if not isinstance(archive, bytes) or len(archive) > MAX_LOG_ARCHIVE_BYTES:
        raise ValueError("log archive exceeds size limit")
    chunks: list[str] = []
    total = 0
    with zipfile.ZipFile(BytesIO(archive)) as zipped:
        members = [
            info for info in zipped.infolist()
            if not info.is_dir() and info.filename.lower().endswith((".txt", ".log"))
        ]
        if len(members) > MAX_LOG_FILES:
            raise ValueError("log archive contains too many text files")
        for info in members:
            if info.flag_bits & 0x1:
                raise ValueError("encrypted log archive member is not supported")
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                raise ValueError("symbolic-link log member is not supported")
            if info.file_size > MAX_LOG_MEMBER_BYTES:
                raise ValueError("log archive member exceeds size limit")
            if info.file_size and (
                info.compress_size == 0
                or info.file_size / info.compress_size > MAX_LOG_COMPRESSION_RATIO
            ):
                raise ValueError("suspicious log compression ratio")
            if total + info.file_size > MAX_LOG_TOTAL_BYTES:
                raise ValueError("expanded log archive exceeds total size limit")
            with zipped.open(info, "r") as member:
                data = member.read(MAX_LOG_MEMBER_BYTES + 1)
            if len(data) != info.file_size or len(data) > MAX_LOG_MEMBER_BYTES:
                raise ValueError("log archive member size does not match metadata")
            total += len(data)
            chunks.append(data.decode("utf-8", errors="replace"))
    return "\n".join(chunks)


def read_bounded_log_response(response: Any) -> bytes:
    """Read at most the configured archive limit plus one detection byte."""
    archive = response.read(MAX_LOG_ARCHIVE_BYTES + 1)
    if len(archive) > MAX_LOG_ARCHIVE_BYTES:
        raise ValueError("log archive exceeds size limit")
    return archive


def download_job_log(job_id: int) -> str:
    req = urllib.request.Request(
        f"{API}/repos/{REPO}/actions/jobs/{job_id}/logs",
        headers={"Authorization": f"Bearer {TOKEN}",
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "MolidoVPN-Autonomous-BugFix-Agent"},
    )
    opener = urllib.request.build_opener(SafeRedirectHandler())
    with opener.open(req, timeout=60) as response:
        archive = read_bounded_log_response(response)
    return extract_job_log(archive)


def redact_secrets(text: str) -> str:
    text = re.sub(r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
                  "[REDACTED PRIVATE KEY]", text, flags=re.DOTALL)
    text = re.sub(r"(?i)\b(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b",
                  "[REDACTED TOKEN]", text)
    text = re.sub(r"(?i)(authorization\s*[:=]\s*bearer\s+)\S+",
                  r"\1[REDACTED]", text)
    text = re.sub(
        r"(?i)\b([A-Z0-9_.-]*(?:password|passwd|token|secret|api[_-]?key|private[_-]?key|"
        r"signing[_-]?key|keystore[_-]?(?:password|base64)|aws[_-]?(?:secret[_-]?access[_-]?key|session[_-]?token))[A-Z0-9_.-]*)"
        r"[\"']?(\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;]+)",
        r"\1\2[REDACTED]", text,
    )
    text = re.sub(r"(?i)(https?://[^:/\s]+:)[^@/\s]+@", r"\1[REDACTED]@", text)
    text = re.sub(
        r"(?i)([?&](?:access_token|refresh_token|token|api_key|password|secret|"
        r"x-amz-(?:credential|signature|security-token)|"
        r"x-goog-(?:credential|signature)|sig|signature)=)[^&\s]+",
        r"\1[REDACTED]", text,
    )
    text = re.sub(r"(?i)\b((?:AKIA|ASIA)[0-9A-Z]{16})\b", "[REDACTED AWS KEY]", text)
    return text

def classify_log(log: str) -> str:
    if CODE_FAILURE.search(log):
        return "code_or_test_failure"
    if TRANSIENT.search(log):
        return "transient_infrastructure"
    return "unknown"

DIAGNOSTIC_RULES = [
    ("signing_or_keystore", re.compile(
        r"keystore|signing config|signer #1 certificate|certificate.*mismatch|"
        r"ANDROID_KEYSTORE|key alias|failed to load.*key",
        re.IGNORECASE),
     "Signing or keystore configuration failure",
     "Do not rotate or replace signing keys. Verify secret names, alias, keystore format, and certificate fingerprint against the approved release identity."),
    ("wireguard_native_build", re.compile(
        r"libwg-go|wireguard-go|NDK|CMake|jni\.c|GOARCH|gomobile|"
        r"native.*(build|link)|undefined reference",
        re.IGNORECASE),
     "WireGuard native toolchain or source-build failure",
     "Keep the upstream commit pin unchanged. Inspect the first compiler/linker error, ABI target, NDK/CMake version, and produced AAR contents before changing build logic."),
    ("emulator_boot_or_kvm", re.compile(
        r"/dev/kvm|emulator.*(boot|launch|exit)|sys\.boot_completed|"
        r"PackageManager never|adb wait-for-device|hardware acceleration",
        re.IGNORECASE),
     "Android Emulator or virtualization startup failure",
     "Inspect runner KVM availability, AVD image, emulator process log, ADB readiness, and PackageManager readiness; do not classify this as an application regression without device evidence."),
    ("android_instrumentation", re.compile(
        r"INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT|"
        r"instrumentation.*(failed|crash)|FATAL EXCEPTION|"
        r"Process .* has died|Test failed",
        re.IGNORECASE),
     "Android instrumentation or runtime test failure",
     "Inspect the failing test name and filtered logcat stack trace. Reproduce on the same API level and add a regression test before changing runtime code."),
    ("kotlin_or_java_compile", re.compile(
        r"Unresolved reference|Compilation error|Compilation failed|"
        r"e: file:.*\.(kt|java):|cannot find symbol|type mismatch",
        re.IGNORECASE),
     "Kotlin or Java compilation failure",
     "Use the first compiler diagnostic and exact file/line. Make the smallest source-compatible change and rerun unit tests, lint, and Android assembly."),
    ("unit_test_assertion", re.compile(
        r"AssertionError|AssertionFailedError|"
        r"expected:.*but was:|Tests? failed|FAILED\s+\(",
        re.IGNORECASE),
     "Unit or regression test failure",
     "Treat this as a real regression until proven otherwise. Inspect assertion context and recent behavior changes; do not retry as an infrastructure failure."),
    ("gradle_dependency_resolution", re.compile(
        r"Could not (find|GET|HEAD)|Could not resolve|"
        r"Temporary failure|UnknownHostException|"
        r"Connection reset|Read timed out|HTTP\s*429|"
        r"status code[: ]+5\d\d",
        re.IGNORECASE),
     "Gradle dependency or repository transport failure",
     "Check repository reachability and dependency coordinates. Retry only the bounded transient path; do not change dependency versions to mask a transport outage."),
    ("android_lint", re.compile(
        r"lintVital|lintDebug|Lint found|"
        r"NewApi|MissingPermission|SecurityException",
        re.IGNORECASE),
     "Android lint or platform compatibility finding",
     "Inspect the exact lint issue ID and source location. Fix behavior or add a justified narrow suppression; never suppress an entire lint task."),
]

def diagnose_log(log: str) -> dict[str, str]:
    """Return a conservative, evidence-linked remediation category."""
    for category, pattern, title, action in DIAGNOSTIC_RULES:
        match = pattern.search(log)
        if match:
            return {
                "category": category,
                "diagnosis": title,
                "evidence": redact_secrets(match.group(0)[:240]),
                "recommended_action": action,
            }
    return {
        "category": "unknown",
        "diagnosis": "Unclassified failure; root cause not established",
        "evidence": "No known diagnostic signature matched",
        "recommended_action": "Preserve the full job log, inspect the first causal error and surrounding stack trace, then add a targeted regression test. Do not apply an automated source patch.",
    }


def should_retry_failed_jobs(reports: list[dict[str, Any]], run_attempt: int) -> bool:
    """Retry only a non-empty all-transient failure set, once."""
    return bool(reports) and run_attempt < 2 and all(
        report.get("classification") == "transient_infrastructure"
        for report in reports
    )


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
            f"- Diagnosis: {report['diagnosis']['diagnosis']} ({report['diagnosis']['category']})",
            f"- Evidence: {report['diagnosis']['evidence']}",
            f"- Recommended next action: {report['diagnosis']['recommended_action']}",
            f"- Job: [#{report['id']}]({report['url']})", "",
            "Log excerpt:", report["excerpt"][:5000], "",
        ]
    created = api("POST", f"/repos/{REPO}/issues", {
        "title": title, "body": "\n".join(lines),
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
        diagnostic_text = ""
        try:
            log = download_job_log(job["id"])
            diagnostic_text = log
            classification, excerpt = classify_log(log), log[-12000:]
        except Exception as exc:
            classification = "unknown"
            excerpt = f"Unable to retrieve job log: {type(exc).__name__}: {exc}"
            diagnostic_text = excerpt
        reports.append({
            "id": job["id"], "name": job.get("name", "unnamed job"),
            "url": job.get("html_url", ""), "classification": classification,
            "excerpt": redact_secrets(excerpt),
            "diagnosis": diagnose_log(diagnostic_text),
        })
    if should_retry_failed_jobs(reports, RUN_ATTEMPT):
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
