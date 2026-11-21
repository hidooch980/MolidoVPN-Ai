import importlib.util
import os
import unittest
import zipfile
from io import BytesIO
from urllib.request import Request

os.environ.setdefault("GITHUB_REPOSITORY", "owner/repo")
os.environ.setdefault("GITHUB_TOKEN", "test-token")
os.environ.setdefault("BUGFIX_RUN_ID", "1")

SPEC = importlib.util.spec_from_file_location(
    "bugfix_agent", "scripts/automation/bugfix_agent.py"
)
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)


class BugFixAgentClassificationTests(unittest.TestCase):
    def test_maven_rate_limit_is_transient(self):
        self.assertEqual(
            agent.classify_log("Could not GET artifact: HTTP 429 Too Many Requests"),
            "transient_infrastructure",
        )

    def test_dns_and_timeout_are_transient(self):
        self.assertEqual(
            agent.classify_log("Temporary failure in name resolution"),
            "transient_infrastructure",
        )

    def test_assertion_failure_is_not_retried(self):
        self.assertEqual(
            agent.classify_log("java.lang.AssertionError: expected true"),
            "code_or_test_failure",
        )

    def test_compilation_failure_is_not_retried(self):
        self.assertEqual(
            agent.classify_log("e: file: MainActivity.kt: Unresolved reference"),
            "code_or_test_failure",
        )

    def test_unknown_failure_fails_closed(self):
        self.assertEqual(agent.classify_log("Process exited with code 1"), "unknown")

    def test_code_failure_takes_precedence_over_network_noise(self):
        self.assertEqual(
            agent.classify_log("HTTP 429\nAssertionError: bad result"),
            "code_or_test_failure",
        )


    def test_private_key_is_redacted_from_issue_excerpt(self):
        sample = "-----BEGIN PRIVATE KEY-----\nsecret-material\n-----END PRIVATE KEY-----"
        result = agent.redact_secrets(sample)
        self.assertNotIn("secret-material", result)
        self.assertIn("[REDACTED PRIVATE KEY]", result)

    def test_token_and_password_are_redacted(self):
        sample = "token=abc123 password: super-secret"
        result = agent.redact_secrets(sample)
        self.assertNotIn("abc123", result)
        self.assertNotIn("super-secret", result)


    def test_emulator_failure_gets_specific_diagnosis(self):
        result = agent.diagnose_log("Emulator did not complete boot; /dev/kvm missing")
        self.assertEqual(result["category"], "emulator_boot_or_kvm")
        self.assertIn("KVM", result["recommended_action"])

    def test_wireguard_native_failure_gets_specific_diagnosis(self):
        result = agent.diagnose_log("ld: undefined reference while linking libwg-go.so")
        self.assertEqual(result["category"], "wireguard_native_build")

    def test_signing_failure_never_suggests_key_rotation(self):
        result = agent.diagnose_log("Signer #1 certificate mismatch")
        self.assertEqual(result["category"], "signing_or_keystore")
        self.assertIn("Do not rotate", result["recommended_action"])

    def test_unknown_diagnosis_does_not_invent_root_cause(self):
        result = agent.diagnose_log("unexpected process exit 17")
        self.assertEqual(result["category"], "unknown")
        self.assertIn("not established", result["diagnosis"])


    def test_retry_allowed_for_all_transient_failures_on_first_attempt(self):
        reports = [{"classification": "transient_infrastructure"}]
        self.assertTrue(agent.should_retry_failed_jobs(reports, 1))

    def test_retry_blocked_after_first_attempt(self):
        reports = [{"classification": "transient_infrastructure"}]
        self.assertFalse(agent.should_retry_failed_jobs(reports, 2))

    def test_retry_blocked_for_mixed_or_unknown_failures(self):
        mixed = [
            {"classification": "transient_infrastructure"},
            {"classification": "code_or_test_failure"},
        ]
        unknown = [{"classification": "unknown"}]
        self.assertFalse(agent.should_retry_failed_jobs(mixed, 1))
        self.assertFalse(agent.should_retry_failed_jobs(unknown, 1))
        self.assertFalse(agent.should_retry_failed_jobs([], 1))


    def test_url_credentials_and_query_tokens_are_redacted(self):
        sample = "https://user:pass@example.test/path?access_token=secret-value&x=1"
        result = agent.redact_secrets(sample)
        self.assertNotIn("pass@", result)
        self.assertNotIn("secret-value", result)

    def test_aws_access_key_is_redacted(self):
        result = agent.redact_secrets("AWS_ACCESS_KEY_ID=AKIA1234567890ABCDEF")
        self.assertNotIn("AKIA1234567890ABCDEF", result)
        self.assertIn("[REDACTED AWS KEY]", result)

    def test_signed_cloud_url_query_credentials_are_redacted(self):
        sample = (
            "https://storage.example/log?X-Amz-Credential=aws-secret"
            "&X-Amz-Signature=signature-secret&X-Amz-Security-Token=session-secret"
            "&X-Goog-Signature=google-secret&sig=azure-secret"
        )
        result = agent.redact_secrets(sample)
        for secret in ("aws-secret", "signature-secret", "session-secret",
                       "google-secret", "azure-secret"):
            self.assertNotIn(secret, result)
        self.assertEqual(result.count("[REDACTED]"), 5)

    def test_log_download_response_is_bounded_before_extraction(self):
        class FakeResponse:
            def __init__(self):
                self.requested = None
            def read(self, size=-1):
                self.requested = size
                return b"x" * size
        response = FakeResponse()
        with self.assertRaises(ValueError):
            agent.read_bounded_log_response(response)
        self.assertEqual(response.requested, agent.MAX_LOG_ARCHIVE_BYTES + 1)


    def test_log_archive_extracts_only_text_logs(self):
        buffer = BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("job.txt", "AssertionError: sample")
            archive.writestr("metadata.json", "{secret: should-not-be-read}")
            archive.writestr("nested/runner.log", "runner finished")
        extracted = agent.extract_job_log(buffer.getvalue())
        self.assertIn("AssertionError: sample", extracted)
        self.assertIn("runner finished", extracted)
        self.assertNotIn("should-not-be-read", extracted)

    def test_log_archive_rejects_oversized_member(self):
        buffer = BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("huge.log", "x" * (2 * 1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            agent.extract_job_log(buffer.getvalue())

    def test_log_archive_rejects_too_many_members(self):
        buffer = BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for index in range(101):
                archive.writestr(f"log-{index}.txt", "ok")
        with self.assertRaises(ValueError):
            agent.extract_job_log(buffer.getvalue())

    def test_log_archive_rejects_suspicious_compression_ratio(self):
        buffer = BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("ratio.log", "a" * 100_000)
        with self.assertRaises(ValueError):
            agent.extract_job_log(buffer.getvalue())

    def test_cross_host_log_redirect_drops_authorization(self):
        request = Request("https://api.github.com/logs")
        request.add_header("Authorization", "Bearer sensitive-token")
        handler = agent.SafeRedirectHandler()
        redirected = handler.redirect_request(
            request, None, 302, "Found", {}, "https://storage.example/logs.zip"
        )
        self.assertIsNotNone(redirected)
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_same_host_redirect_preserves_authorization(self):
        request = Request("https://api.github.com/logs")
        request.add_header("Authorization", "Bearer sensitive-token")
        handler = agent.SafeRedirectHandler()
        redirected = handler.redirect_request(
            request, None, 302, "Found", {}, "https://api.github.com/next"
        )
        self.assertIsNotNone(redirected)
        self.assertEqual(
            redirected.get_header("Authorization"), "Bearer sensitive-token"
        )


if __name__ == "__main__":
    unittest.main()


class PatchPolicyTests(unittest.TestCase):
    def _plan(self, path="scripts/automation/fix_rule.py", additions=3, deletions=1):
        return {
            "files": [{"path": path, "additions": additions, "deletions": deletions}],
            "diff": (f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n"
                     + "".join("+added\n" for _ in range(additions))
                     + "".join("-removed\n" for _ in range(deletions))),
        }

    def test_allows_small_allowlisted_automation_proposal(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan()),
                         (True, "metadata_policy_passed_draft_only"))

    def test_rejects_application_source(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan("android/app/src/Main.kt"))[1],
                         "path_not_allowlisted")

    def test_rejects_sensitive_named_path(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan("scripts/automation/keystore/fix.py"))[1],
                         "sensitive_path")

    def test_rejects_parent_traversal(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan("scripts/automation/../secrets.txt"))[1],
                         "path_traversal")

    def test_rejects_absolute_and_backslash_paths(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan("/scripts/automation/x.py"))[1],
                         "path_traversal")
        self.assertEqual(validate_patch_plan(self._plan("scripts\\\\automation\\\\x.py"))[1],
                         "invalid_path")

    def test_rejects_too_many_files(self):
        from scripts.automation.patch_policy import validate_patch_plan
        p = self._plan()
        p["files"] = [p["files"][0], {"path": "docs/automation/a.md", "additions": 1, "deletions": 0},
                      {"path": "docs/automation/b.md", "additions": 1, "deletions": 0}]
        self.assertEqual(validate_patch_plan(p)[1], "invalid_file_count")

    def test_rejects_change_limit(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan(self._plan(additions=201))[1],
                         "change_limit_exceeded")

    def test_rejects_diff_path_mismatch(self):
        from scripts.automation.patch_policy import validate_patch_plan
        p = self._plan()
        p["diff"] = "diff --git a/docs/automation/other.md b/docs/automation/other.md\n--- a/docs/automation/other.md\n+++ b/docs/automation/other.md\n"
        self.assertEqual(validate_patch_plan(p)[1], "diff_paths_mismatch")

    def test_rejects_binary_diff(self):
        from scripts.automation.patch_policy import validate_patch_plan
        p = self._plan()
        p["diff"] = "GIT binary patch"
        self.assertEqual(validate_patch_plan(p)[1], "binary_or_nul_diff")

    def test_rejects_diff_line_count_mismatch(self):
        from scripts.automation.patch_policy import validate_patch_plan
        p = self._plan()
        p["diff"] = p["diff"].replace("+added\n", "", 1)
        self.assertEqual(validate_patch_plan(p)[1], "diff_line_count_mismatch")

    def test_rejects_malformed_diff_headers(self):
        from scripts.automation.patch_policy import validate_patch_plan
        p = self._plan()
        p["diff"] = p["diff"].replace("--- a/", "--- x/", 1)
        self.assertEqual(validate_patch_plan(p)[1], "malformed_diff_headers")

    def test_rejects_unknown_fields_and_non_object(self):
        from scripts.automation.patch_policy import validate_patch_plan
        self.assertEqual(validate_patch_plan([])[1], "plan_must_be_object")
        p = self._plan()
        p["approved"] = True
        self.assertEqual(validate_patch_plan(p)[1], "unexpected_or_missing_fields")
