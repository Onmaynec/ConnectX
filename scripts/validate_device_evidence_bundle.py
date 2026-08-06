#!/usr/bin/env python3
"""Validate a privacy-safe ConnectX alpha.7 physical-device readiness bundle."""

from __future__ import annotations

import re
import sys
from pathlib import Path

HEADER = "ConnectX v0.3.0-alpha.7 — physical device evidence bundle"
ALLOWED_KEYS = [
    "schema_version",
    "source_commit",
    "apk_sha256",
    "device_class",
    "android_api",
    "abi_family",
    "native_lifecycle",
    "external_evidence_loopback",
    "fd_budget_gate",
    "restricted_network_manual",
    "claim",
]
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IPV4 = re.compile(r"(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])")
HOSTNAME = re.compile(r"(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\b")
FORBIDDEN = (
    "serial",
    "model",
    "manufacturer",
    "fingerprint",
    "ssid",
    "http://",
    "https://",
    "authorization",
    "bearer ",
    "cookie",
    "token=",
    "@",
)


def validate(text: str) -> list[str]:
    errors: list[str] = []
    if "\r" in text or "\x00" in text:
        errors.append("invalid-control-character")
    lines = text.splitlines()
    if not lines or lines[0] != HEADER:
        errors.append("invalid-header")
        return errors
    entries: dict[str, str] = {}
    order: list[str] = []
    for line in lines[1:]:
        if "=" not in line:
            errors.append("invalid-line")
            continue
        key, value = line.split("=", 1)
        order.append(key)
        if key not in ALLOWED_KEYS:
            errors.append(f"unknown-key:{key}")
        if key in entries:
            errors.append(f"duplicate-key:{key}")
        entries[key] = value
    if order != ALLOWED_KEYS:
        errors.append("schema-order-mismatch")
    if entries.get("schema_version") != "1":
        errors.append("invalid-schema-version")
    if not SHA40.fullmatch(entries.get("source_commit", "")):
        errors.append("invalid-source-commit")
    if not SHA256.fullmatch(entries.get("apk_sha256", "")):
        errors.append("invalid-apk-sha256")
    if entries.get("device_class") != "PHYSICAL":
        errors.append("device-not-physical")
    api = entries.get("android_api", "")
    if not api.isdigit() or not 29 <= int(api) <= 99:
        errors.append("invalid-android-api")
    if entries.get("abi_family") != "ARM64":
        errors.append("device-not-arm64")
    for key in ("native_lifecycle", "external_evidence_loopback", "fd_budget_gate"):
        if entries.get(key) != "PASS":
            errors.append(f"gate-not-pass:{key}")
    if entries.get("restricted_network_manual") != "REQUIRED":
        errors.append("manual-restricted-network-gate-missing")
    if entries.get("claim") != "readiness-only-not-restricted-network-proof":
        errors.append("invalid-claim")
    if IPV4.search(text):
        errors.append("raw-ipv4")
    if HOSTNAME.search(text):
        errors.append("raw-hostname")
    lowered = text.lower()
    for fragment in FORBIDDEN:
        if fragment in lowered:
            errors.append(f"forbidden-fragment:{fragment}")
    return sorted(set(errors))


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: validate_device_evidence_bundle.py BUNDLE", file=sys.stderr)
        return 2
    text = Path(argv[1]).read_text(encoding="utf-8")
    errors = validate(text)
    if errors:
        print("device evidence bundle rejected: " + ", ".join(errors), file=sys.stderr)
        return 2
    print("physical device evidence bundle accepted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
