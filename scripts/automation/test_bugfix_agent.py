import importlib.util
import os
import unittest

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
        sample = "-----BEGIN PRIVATE KEY-----\\nsecret-material\\n-----END PRIVATE KEY-----"
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


if __name__ == "__main__":
    unittest.main()
