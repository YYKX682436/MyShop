from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path
from unittest import mock

path = Path(__file__).resolve().parents[1] / "ci-release-scope.py"
spec = importlib.util.spec_from_file_location("ci_release_scope", path)
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ReleaseScopeTests(unittest.TestCase):
    def test_web_only_and_documentation_changes(self):
        self.assertEqual(scope.classify(["apps/web/app/page.tsx", "docs/README.md"]),
                         {"api": False, "web": True, "compose": False, "runtime": True})
        self.assertEqual(scope.classify(["docs/example.md", "README.md"]),
                         {"api": False, "web": False, "compose": False, "runtime": False})

    def test_payment_delivery_refund_and_schema_cannot_skip_api(self):
        for name in ("PaymentService.java", "DeliveryService.java", "RefundService.java"):
            with self.subTest(name=name):
                self.assertTrue(scope.classify([f"apps/api/src/main/java/{name}"])["api"])
        migration = scope.classify(["apps/api/src/main/resources/db/migration/V56__example.sql"])
        self.assertTrue(migration["api"] and migration["web"] and migration["compose"])

    def test_unknown_or_ci_files_require_all_checks(self):
        for name in ("unexpected.conf", "other/readme.md", ".github/workflows/cicd.yml", ".dockerignore", "scripts/ci-release-scope.py"):
            with self.subTest(name=name):
                self.assertTrue(all(scope.classify([name]).values()))

    def test_deployment_only_runs_deployment_checks(self):
        self.assertEqual(scope.classify(["scripts/deploy-production.sh"]),
                         {"api": False, "web": False, "compose": True, "runtime": True})

    def test_test_only_and_download_helper_changes_do_not_publish_images(self):
        for path in ("scripts/tests/test_fetch_release_archives.py", "scripts/fetch-release-archives.py", "scripts/test-deployment-transaction.sh"):
            with self.subTest(path=path):
                self.assertEqual(scope.classify([path]),
                                 {"api": False, "web": False, "compose": True, "runtime": False})
        self.assertEqual(scope.classify(["scripts/test-web-image-host-routing.sh"]),
                         {"api": False, "web": True, "compose": True, "runtime": True})

    def test_no_baseline_and_api_failure_fall_back_to_all_checks(self):
        for side_effect in (None, OSError("unavailable")):
            with self.subTest(side_effect=side_effect), \
                 mock.patch.dict(scope.os.environ, {"GITHUB_SHA": "a" * 40, "GITHUB_REPOSITORY": "owner/repo", "GITHUB_RUN_ID": "1"}, clear=True), \
                 mock.patch.object(scope, "command", return_value="a" * 40), \
                 mock.patch.object(scope, "prior_release", return_value=None, side_effect=side_effect), \
                 mock.patch("sys.stdout", new_callable=__import__("io").StringIO) as out:
                scope.main()
                for key in ("api", "web", "compose", "runtime"):
                    self.assertIn(f"{key}=true", out.getvalue())

    def test_only_successful_published_ancestor_is_a_baseline(self):
        runs = {"workflow_runs": [{"head_sha": "b" * 40, "id": 2}]}
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(scope, "command", side_effect=[json.dumps(runs), json.dumps({"jobs": [{"name": scope.RELEASE_JOB, "conclusion": "skipped"}]})]):
            self.assertIsNone(scope.prior_release("owner/repo", "a" * 40, "1"))
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(scope, "command", side_effect=[json.dumps(runs), json.dumps({"jobs": [{"name": scope.RELEASE_JOB, "conclusion": "success"}]})]):
            self.assertEqual(scope.prior_release("owner/repo", "a" * 40, "1"), ("b" * 40, 2))
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=1)), \
             mock.patch.object(scope, "command", return_value=json.dumps(runs)):
            self.assertIsNone(scope.prior_release("owner/repo", "a" * 40, "1"))

    def test_app_test_only_changes_run_affected_checks_without_release(self):
        self.assertEqual(scope.classify(["apps/api/src/test/java/PaymentTest.java"]),
                         {"api": True, "web": False, "compose": False, "runtime": False})
        self.assertEqual(scope.classify(["apps/web/lib/cart.test.mjs"]),
                         {"api": False, "web": True, "compose": False, "runtime": False})
        self.assertFalse(any(scope.classify(["nested/AGENTS.md"]).values()))


if __name__ == "__main__":
    unittest.main()
