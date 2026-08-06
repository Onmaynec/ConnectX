#!/usr/bin/env python3
"""Strict, dependency-free validation for ConnectX guarded prereleases."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Mapping

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+-alpha\.\d+$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
VERSION_CODE_RE = re.compile(r"versionCode\s*=\s*(\d+)")
VERSION_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')
README_VERSION_RE = re.compile(r"^## Текущая версия разработки: v([^\s]+)$", re.MULTILINE)
BRIDGE_VERSION_RE = re.compile(r'bridgeReleaseVersion\s*=\s*"([^"]+)"')

REQUIRED_KEYS = {
    "schema_version",
    "publish",
    "prerelease",
    "version_name",
    "version_code",
    "tag",
    "title",
    "notes_path",
    "scope_path",
    "expected_ci_workflow",
    "expected_ci_event",
    "expected_branch",
}


class GuardError(ValueError):
    """Raised when release provenance or metadata is unsafe."""


@dataclass(frozen=True)
class ReleaseManifest:
    schema_version: int
    publish: bool
    prerelease: bool
    version_name: str
    version_code: int
    tag: str
    title: str
    notes_path: str
    scope_path: str
    expected_ci_workflow: str
    expected_ci_event: str
    expected_branch: str

    @property
    def apk_asset_name(self) -> str:
        return f"ConnectX-v{self.version_name}-debug.apk"

    @property
    def native_asset_name(self) -> str:
        return f"ConnectX-v{self.version_name}-native.zip"

    @property
    def notices_asset_name(self) -> str:
        return f"THIRD_PARTY_NOTICES-v{self.version_name}.md"


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise GuardError(message)


def _safe_repo_path(value: Any, field: str, prefix: str) -> str:
    _require(isinstance(value, str) and value.strip() == value and value, f"{field} must be a non-empty string")
    path = PurePosixPath(value)
    _require(not path.is_absolute(), f"{field} must be repository-relative")
    _require(".." not in path.parts, f"{field} must not escape the repository")
    _require(value.startswith(prefix), f"{field} must start with {prefix}")
    _require("\n" not in value and "\r" not in value, f"{field} must be one line")
    return value


def manifest_from_mapping(raw: Mapping[str, Any]) -> ReleaseManifest:
    keys = set(raw)
    _require(keys == REQUIRED_KEYS, f"manifest keys mismatch: missing={sorted(REQUIRED_KEYS - keys)} extra={sorted(keys - REQUIRED_KEYS)}")

    schema_version = raw["schema_version"]
    publish = raw["publish"]
    prerelease = raw["prerelease"]
    version_name = raw["version_name"]
    version_code = raw["version_code"]
    tag = raw["tag"]
    title = raw["title"]

    _require(schema_version == 1, "schema_version must be 1")
    _require(type(publish) is bool, "publish must be boolean")
    _require(prerelease is True, "prerelease must be true for alpha releases")
    _require(isinstance(version_name, str) and VERSION_RE.fullmatch(version_name) is not None, "version_name must match X.Y.Z-alpha.N")
    _require(type(version_code) is int and version_code > 0, "version_code must be a positive integer")
    _require(tag == f"v{version_name}", "tag must equal v + version_name")
    _require(isinstance(title, str) and title.startswith(f"ConnectX v{version_name} — "), "title must start with the exact ConnectX version")
    _require("\n" not in title and "\r" not in title, "title must be one line")

    notes_path = _safe_repo_path(raw["notes_path"], "notes_path", "docs/releases/")
    scope_path = _safe_repo_path(raw["scope_path"], "scope_path", "docs/roadmap/")

    expected_ci_workflow = raw["expected_ci_workflow"]
    expected_ci_event = raw["expected_ci_event"]
    expected_branch = raw["expected_branch"]
    _require(expected_ci_workflow == "Android CI", "expected_ci_workflow must be Android CI")
    _require(expected_ci_event == "push", "expected_ci_event must be push")
    _require(expected_branch == "main", "expected_branch must be main")

    return ReleaseManifest(
        schema_version=schema_version,
        publish=publish,
        prerelease=prerelease,
        version_name=version_name,
        version_code=version_code,
        tag=tag,
        title=title,
        notes_path=notes_path,
        scope_path=scope_path,
        expected_ci_workflow=expected_ci_workflow,
        expected_ci_event=expected_ci_event,
        expected_branch=expected_branch,
    )


def load_manifest(path: Path) -> ReleaseManifest:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GuardError(f"cannot read manifest {path}: {exc}") from exc
    _require(isinstance(raw, dict), "manifest root must be an object")
    return manifest_from_mapping(raw)


def validate_workflow_run(run: Mapping[str, Any], manifest: ReleaseManifest, target_sha: str) -> None:
    _require(SHA_RE.fullmatch(target_sha) is not None, "target_sha must be a lowercase 40-character SHA")
    expected = {
        "name": manifest.expected_ci_workflow,
        "status": "completed",
        "conclusion": "success",
        "event": manifest.expected_ci_event,
        "headSha": target_sha,
        "headBranch": manifest.expected_branch,
    }
    for key, value in expected.items():
        _require(run.get(key) == value, f"workflow run {key} mismatch: expected {value!r}, got {run.get(key)!r}")
    database_id = run.get("databaseId")
    _require(type(database_id) is int and database_id > 0, "workflow run databaseId must be a positive integer")


def _single_match(pattern: re.Pattern[str], text: str, field: str) -> str:
    matches = pattern.findall(text)
    _require(len(matches) == 1, f"expected exactly one {field}, found {len(matches)}")
    return str(matches[0])


def validate_repository(root: Path, manifest: ReleaseManifest) -> None:
    root = root.resolve()
    build_gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    readme = (root / "README.md").read_text(encoding="utf-8")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    bridge = (root / "engine/go/bridge/session.go").read_text(encoding="utf-8")

    version_code = int(_single_match(VERSION_CODE_RE, build_gradle, "versionCode"))
    version_name = _single_match(VERSION_NAME_RE, build_gradle, "versionName")
    readme_version = _single_match(README_VERSION_RE, readme, "README current version heading")
    bridge_version = _single_match(BRIDGE_VERSION_RE, bridge, "native bridge release version")

    _require(version_code == manifest.version_code, "app versionCode does not match manifest")
    _require(version_name == manifest.version_name, "app versionName does not match manifest")
    _require(readme_version == manifest.version_name, "README version does not match manifest")
    _require(bridge_version == manifest.version_name, "native bridge version does not match manifest")
    _require(f"## [{manifest.version_name}]" in changelog, "CHANGELOG entry is missing")

    for relative in (
        manifest.notes_path,
        manifest.scope_path,
        "THIRD_PARTY_NOTICES.md",
        "licenses/tun2socks-MIT.txt",
        "licenses/gvisor-LICENSE.txt",
        ".github/workflows/publish-prerelease.yml",
        "docs/operations/guarded-prerelease.md",
    ):
        path = root / relative
        _require(path.is_file() and path.stat().st_size > 0, f"required file is missing or empty: {relative}")

    legacy = sorted(
        path.relative_to(root).as_posix()
        for path in (root / ".github/workflows").glob("release-v*.yml")
    )
    _require(not legacy, f"legacy version-specific release workflows are still active: {legacy}")


def github_outputs(manifest: ReleaseManifest) -> dict[str, str]:
    return {
        "publish": str(manifest.publish).lower(),
        "prerelease": str(manifest.prerelease).lower(),
        "version_name": manifest.version_name,
        "version_code": str(manifest.version_code),
        "tag": manifest.tag,
        "title": manifest.title,
        "notes_path": manifest.notes_path,
        "scope_path": manifest.scope_path,
        "expected_ci_workflow": manifest.expected_ci_workflow,
        "expected_ci_event": manifest.expected_ci_event,
        "expected_branch": manifest.expected_branch,
        "apk_asset_name": manifest.apk_asset_name,
        "native_asset_name": manifest.native_asset_name,
        "notices_asset_name": manifest.notices_asset_name,
    }


def _command_validate_repo(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    validate_repository(Path(args.root), manifest)
    print(f"release manifest and repository are consistent for {manifest.tag}")
    return 0


def _command_validate_run(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    try:
        run = json.loads(Path(args.run_json).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GuardError(f"cannot read workflow run JSON: {exc}") from exc
    _require(isinstance(run, dict), "workflow run JSON root must be an object")
    validate_workflow_run(run, manifest, args.target_sha)
    print(f"trusted Android CI run {run['databaseId']} verified for {args.target_sha}")
    return 0


def _command_outputs(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    for key, value in github_outputs(manifest).items():
        print(f"{key}={value}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_repo = subparsers.add_parser("validate-repo")
    validate_repo.add_argument("--manifest", default="release/prerelease.json")
    validate_repo.add_argument("--root", default=".")
    validate_repo.set_defaults(func=_command_validate_repo)

    validate_run = subparsers.add_parser("validate-run")
    validate_run.add_argument("--manifest", default="release/prerelease.json")
    validate_run.add_argument("--run-json", required=True)
    validate_run.add_argument("--target-sha", required=True)
    validate_run.set_defaults(func=_command_validate_run)

    outputs = subparsers.add_parser("github-output")
    outputs.add_argument("--manifest", default="release/prerelease.json")
    outputs.set_defaults(func=_command_outputs)
    return parser


def main(argv: list[str] | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
        return int(args.func(args))
    except GuardError as exc:
        print(f"release guard rejected input: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
