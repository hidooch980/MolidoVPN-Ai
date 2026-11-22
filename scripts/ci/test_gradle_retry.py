import unittest

from scripts.ci.gradle_retry import classify_failure


class GradleRetryClassificationTests(unittest.TestCase):
    def test_unknown_host_is_transient(self):
        self.assertEqual(
            classify_failure("Could not GET https://repo.maven.apache.org/x\nUnknownHostException"),
            "TRANSIENT_NETWORK",
        )

    def test_rate_limit_is_transient(self):
        self.assertEqual(
            classify_failure("Could not GET https://repo.maven.apache.org/x: status code 429"),
            "TRANSIENT_NETWORK",
        )

    def test_connection_reset_is_transient(self):
        self.assertEqual(
            classify_failure("Read timed out while downloading dependency"),
            "TRANSIENT_NETWORK",
        )

    def test_gradle_failure_summary_does_not_hide_network_root_cause(self):
        self.assertEqual(
            classify_failure(
                "Could not GET https://repo.maven.apache.org/x: status code 503\\n"
                "BUILD FAILED in 22s"
            ),
            "TRANSIENT_NETWORK",
        )

    def test_assertion_failure_is_never_retried(self):
        self.assertEqual(
            classify_failure(
                "Could not GET https://repo.maven.apache.org/x\n"
                "There were failing tests.\nAssertionError: expected true"
            ),
            "DETERMINISTIC",
        )

    def test_kotlin_compile_failure_is_never_retried(self):
        self.assertEqual(
            classify_failure("e: file:///src/Main.kt: Unresolved reference: missingSymbol"),
            "DETERMINISTIC",
        )

    def test_unknown_failure_is_not_retried(self):
        self.assertEqual(
            classify_failure("Execution failed for task ':app:testDebugUnitTest'."),
            "DETERMINISTIC_OR_UNKNOWN",
        )


if __name__ == "__main__":
    unittest.main()
