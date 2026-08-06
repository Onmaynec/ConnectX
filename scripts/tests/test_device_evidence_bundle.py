from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_device_evidence_bundle import validate  # noqa: E402

VALID = """ConnectX v0.3.0-alpha.7 — physical device evidence bundle
schema_version=1
source_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
apk_sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
device_class=PHYSICAL
android_api=35
abi_family=ARM64
native_lifecycle=PASS
external_evidence_loopback=PASS
fd_budget_gate=PASS
restricted_network_manual=REQUIRED
claim=readiness-only-not-restricted-network-proof
"""


class DeviceEvidenceBundleTest(unittest.TestCase):
    def test_valid_bundle_is_accepted(self) -> None:
        self.assertEqual([], validate(VALID))

    def test_emulator_and_non_arm64_are_rejected(self) -> None:
        text = VALID.replace("device_class=PHYSICAL", "device_class=EMULATOR")
        text = text.replace("abi_family=ARM64", "abi_family=X86_64")
        errors = validate(text)
        self.assertIn("device-not-physical", errors)
        self.assertIn("device-not-arm64", errors)

    def test_unknown_field_is_rejected(self) -> None:
        errors = validate(VALID + "extra=value\n")
        self.assertTrue(any(error.startswith("unknown-key:") for error in errors))

    def test_identifiers_targets_and_credentials_are_rejected(self) -> None:
        mutations = (
            VALID.replace("claim=", "serial=ABC123\nclaim="),
            VALID.replace("claim=", "model=Pixel\nclaim="),
            VALID.replace("claim=", "target=93.184.216.34\nclaim="),
            VALID.replace("claim=", "target=blocked.example.org\nclaim="),
            VALID.replace("claim=", "authorization=Bearer secret\nclaim="),
        )
        for text in mutations:
            self.assertNotEqual([], validate(text))


if __name__ == "__main__":
    unittest.main()
