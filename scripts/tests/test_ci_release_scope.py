from __future__ import annotations

import json
import importlib.util
import unittest
from pathlib import Path
from unittest import mock

path = Path(__file__).resolve().parents[1] / "ci-release-scope.py"
spec = importlib.util.spec_from_file_location("ci_release_scope", path)
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ReleaseScopeTests(unittest.TestCase):
    def test_web_only_changes_build_only_web(self):
        result = scope.classify(["apps/web/app/page.tsx", "docs/README.md"])
        self.assertTrue(result["web_test"] and result["web_image"])
        self.assertFalse(result["api_test"] or result["api_image"] or result["compose_test"])

    def test_static_web_changes_build_without_optional_test_suites(self):
        result = scope.classify(["apps/web/app/globals.css", "apps/web/public/banner.webp"])
        self.assertTrue(result["web_image"] and result["runtime"])
        self.assertFalse(result["web_test"] or result["web_full_test"] or result["api_image"])

    def test_core_changes_keep_full_related_tests(self):
        api = scope.classify(["apps/api/src/main/java/com/myshop/order/OrderService.java"])
        self.assertTrue(api["api_full_test"] and api["web_full_test"])
        web = scope.classify(["apps/web/app/payment/page.tsx"])
        self.assertTrue(web["web_full_test"] and web["web_test"])

    def test_targeted_api_selection_never_silently_skips_tests(self):
        with mock.patch.object(scope.Path, "is_dir", return_value=False):
            self.assertEqual(scope.targeted_api_tests(["apps/api/src/main/java/com/myshop/Foo.java"]), "")

    def test_api_changes_run_web_compatibility_without_web_image(self):
        result = scope.classify(["apps/api/src/main/java/com/myshop/PaymentService.java"])
        self.assertTrue(result["api_test"] and result["api_image"] and result["web_test"])
        self.assertFalse(result["web_image"] or result["compose_test"])

    def test_api_migrations_run_deployment_contract_without_building_web(self):
        result = scope.classify(["apps/api/src/main/resources/db/migration/V66__example.sql"])
        self.assertTrue(result["api_test"] and result["web_test"] and result["compose_test"])
        self.assertTrue(result["api_image"] and result["migration"])
        self.assertFalse(result["web_image"])

    def test_frontend_dependency_and_build_inputs_build_only_web(self):
        for path_name in ("pnpm-lock.yaml", "package.json", "Dockerfile.web", "apps/web/next.config.mjs"):
            with self.subTest(path=path_name):
                result = scope.classify([path_name])
                self.assertTrue(result["web_test"] and result["web_image"])
                self.assertFalse(result["api_image"])

    def test_backend_pom_and_runtime_dockerfile_build_only_api(self):
        for path_name in ("apps/api/pom.xml", "Dockerfile.api"):
            with self.subTest(path=path_name):
                result = scope.classify([path_name])
                self.assertTrue(result["api_test"] and result["api_image"])
                self.assertFalse(result["web_image"])

    def test_document_and_test_only_changes_do_not_build_images(self):
        self.assertFalse(any(scope.classify(["docs/example.md", "README.md"]).values()))
        result = scope.classify(["apps/api/src/test/java/PaymentServiceTest.java"])
        self.assertTrue(result["api_test"])
        self.assertFalse(result["api_image"] or result["web_image"] or result["runtime"])
        result = scope.classify(["apps/web/lib/cart.test.mjs"])
        self.assertTrue(result["web_test"])
        self.assertFalse(result["api_image"] or result["web_image"] or result["runtime"])

    def test_deployment_scripts_run_contract_checks_without_app_images(self):
        result = scope.classify(["scripts/deploy-production.sh"])
        self.assertTrue(result["compose_test"] and result["runtime"])
        self.assertFalse(result["api_image"] or result["web_image"])

    def test_unknown_ci_or_dockerignore_changes_fail_closed(self):
        for path_name in ("unexpected.conf", "other/readme.md", ".github/workflows/cicd.yml", ".dockerignore"):
            with self.subTest(path=path_name):
                self.assertTrue(all(scope.classify([path_name]).values()))

    def test_manual_release_duplicate_stale_and_rerun_guards(self):
        sha = "a" * 40
        runs = {"workflow_runs": [{"head_sha": sha, "id": 2, "conclusion": "success"}]}
        with mock.patch.object(scope, "command", side_effect=[
            json.dumps({"object": {"sha": sha}}), json.dumps(runs),
            json.dumps({"jobs": [{"name": scope.RELEASE_JOB, "conclusion": "success"}]}),
        ]):
            with self.assertRaisesRegex(SystemExit, "already succeeded"):
                scope.validate_manual_release("owner/repo", sha, "3", "refs/heads/main")
        with mock.patch.object(scope, "command", return_value=json.dumps({"object": {"sha": "b" * 40}})):
            with self.assertRaisesRegex(SystemExit, "Refusing stale release SHA"):
                scope.validate_manual_release("owner/repo", sha, "3", "refs/heads/main")
        with self.assertRaisesRegex(SystemExit, "fresh workflow dispatch"):
            scope.validate_manual_release("owner/repo", sha, "3", "refs/heads/main", attempt="2")

    def test_only_successful_manifest_release_is_a_baseline(self):
        sha = "b" * 40
        runs = {"workflow_runs": [{"head_sha": sha, "head_branch": "main", "id": 2}]}
        artifact = {"name": "myshop-release-manifest-" + sha, "expired": False,
                    "workflow_run": {"head_sha": sha, "head_branch": "main", "id": 2}}
        def release_context_command(*args):
            if "actions/artifacts?" in args[-1]:
                return json.dumps({"artifacts": [artifact]})
            return json.dumps(runs)
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(scope, "command", side_effect=release_context_command) as command:
            self.assertEqual(scope.prior_release("owner/repo", "a" * 40, "1"), (sha, 2))
            self.assertEqual(command.call_count, 2)
        def expired_context_command(*args):
            if "actions/artifacts?" in args[-1]:
                return json.dumps({"artifacts": [{**artifact, "expired": True}]})
            return json.dumps(runs)
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(scope, "command", side_effect=expired_context_command):
            self.assertIsNone(scope.prior_release("owner/repo", "a" * 40, "1"))
        legacy_artifact = {**artifact, "name": "myshop-release-" + sha}
        def legacy_context_command(*args):
            if "actions/artifacts?" in args[-1]:
                return json.dumps({"artifacts": [legacy_artifact]})
            return json.dumps(runs)
        with mock.patch.object(scope.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(scope, "command", side_effect=legacy_context_command):
            self.assertIsNone(scope.prior_release("owner/repo", "a" * 40, "1"))

    def test_manual_workflow_is_serialized_and_does_not_run_on_push(self):
        workflow = (path.parents[1] / ".github/workflows/cicd.yml").read_text(encoding="utf-8")
        self.assertRegex(workflow, r"(?m)^on:\n  workflow_dispatch:")
        self.assertNotRegex(workflow, r"(?m)^  (push|pull_request|schedule|workflow_run):")
        self.assertIn("group: myshop-manual-release", workflow)
        self.assertIn("queue: max", workflow)
        self.assertIn("cancel-in-progress: false", workflow)


if __name__ == "__main__":
    unittest.main()
