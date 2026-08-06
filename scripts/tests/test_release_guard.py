from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_guard import (  # noqa: E402
    GuardError,
    github_outputs,
    manifest_from_mapping,
    validate_repository,
    validate_workflow_run,
)


VALID = {
    "schema_version": 1,
    "publish": True,
    "prerelease": True,
    "version_name": "0.3.0-alpha.7",
    "version_code": 14,
    "tag": "v0.3.0-alpha.7",
    "title": "ConnectX v0.3.0-alpha.7 — Unified Guarded Prerelease",
    "notes_path": "docs/releases/v0.3.0-alpha.7.md",
    "scope_path": "docs/roadmap/alpha7-scope.md",
    "expected_ci_workflow": "Android CI",
    "expected_ci_event": "push",
    "expected_branch": "main",
}
SHA = "a" * 40


class ManifestValidationTest(unittest.TestCase):
    def test_valid_manifest_emits_deterministic_asset_names(self) -> None:
        manifest = manifest_from_mapping(VALID)
        outputs = github_outputs(manifest)
        self.assertEqual(outputs["tag"], "v0.3.0-alpha.7")
        self.assertEqual(outputs["apk_asset_name"], "ConnectX-v0.3.0-alpha.7-debug.apk")
        self.assertEqual(outputs["native_asset_name"], "ConnectX-v0.3.0-alpha.7-native.zip")

    def test_unknown_key_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            manifest_from_mapping({**VALID, "source_run_id": 123})

    def test_tag_mismatch_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            manifest_from_mapping({**VALID, "tag": "v0.3.0-alpha.4"})

    def test_unsafe_notes_path_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            manifest_from_mapping({**VALID, "notes_path": "../release.md"})

    def test_non_prerelease_channel_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            manifest_from_mapping({**VALID, "prerelease": False})


class ProvenanceValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = manifest_from_mapping(VALID)
        self.run = {
            "name": "Android CI",
            "status": "completed",
            "conclusion": "success",
            "event": "push",
            "headSha": SHA,
            "headBranch": "main",
            "databaseId": 42,
        }

    def test_exact_successful_main_push_is_accepted(self) -> None:
        validate_workflow_run(self.run, self.manifest, SHA)

    def test_pull_request_run_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            validate_workflow_run({**self.run, "event": "pull_request"}, self.manifest, SHA)

    def test_wrong_branch_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            validate_workflow_run({**self.run, "headBranch": "feature/test"}, self.manifest, SHA)

    def test_failed_run_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            validate_workflow_run({**self.run, "conclusion": "failure"}, self.manifest, SHA)

    def test_different_sha_is_rejected(self) -> None:
        with self.assertRaises(GuardError):
            validate_workflow_run({**self.run, "headSha": "b" * 40}, self.manifest, SHA)


class RepositoryConsistencyTest(unittest.TestCase):
    def _write_repo(self, root: Path, readme_version: str = "0.3.0-alpha.7") -> None:
        files = {
            "app/build.gradle.kts": 'versionCode = 14\nversionName = "0.3.0-alpha.7"\n',
            "README.md": f"## Текущая версия разработки: v{readme_version}\n",
            "CHANGELOG.md": "## [0.3.0-alpha.7]\n",
            "engine/go/bridge/session.go": 'const bridgeReleaseVersion = "0.3.0-alpha.7"\n',
            "docs/releases/v0.3.0-alpha.7.md": "notes\n",
            "docs/roadmap/alpha7-scope.md": "scope\n",
            "THIRD_PARTY_NOTICES.md": "notice\n",
            "licenses/tun2socks-MIT.txt": "license\n",
            "licenses/gvisor-LICENSE.txt": "license\n",
            ".github/workflows/publish-prerelease.yml": "name: publish\n",
            "docs/operations/guarded-prerelease.md": "operations\n",
        }
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    def test_repository_metadata_matches_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._write_repo(root)
            validate_repository(root, manifest_from_mapping(VALID))

    def test_stale_readme_version_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._write_repo(root, readme_version="0.3.0-alpha.4")
            with self.assertRaises(GuardError):
                validate_repository(root, manifest_from_mapping(VALID))

    def test_legacy_release_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._write_repo(root)
            legacy = root / ".github/workflows/release-v0.3.0-alpha5.yml"
            legacy.write_text("name: legacy\n", encoding="utf-8")
            with self.assertRaises(GuardError):
                validate_repository(root, manifest_from_mapping(VALID))


if __name__ == "__main__":
    unittest.main()
