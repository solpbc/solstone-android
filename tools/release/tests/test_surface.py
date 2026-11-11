# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from surface import MAX_CODE, next_code, validate_code


class VersionAllocationTest(unittest.TestCase):
    def test_migration_and_independent_sequences_do_not_collide(self):
        phone = next_code("phone", 50)
        wear = next_code("wear", 1)
        seen = set(range(1, 51))
        for _ in range(100):
            self.assertNotIn(phone, seen)
            seen.add(phone)
            self.assertNotIn(wear, seen)
            seen.add(wear)
            phone = next_code("phone", phone)
            wear = next_code("wear", wear)

    def test_wrong_parity_and_exhaustion_refuse(self):
        for surface, current in (("phone", 53), ("wear", 52), ("phone", MAX_CODE), ("wear", MAX_CODE - 1)):
            with self.subTest(surface=surface, current=current), self.assertRaises(ValueError):
                next_code(surface, current)

    def test_unexpected_historical_code_refuses(self):
        for surface in ("phone", "wear"):
            with self.assertRaises(ValueError):
                next_code(surface, 20)


class PlayBundleBindingTest(unittest.TestCase):
    def test_play_prepare_refuses_a_different_universal_apk(self):
        import tempfile
        import aab_fixture
        from play import binding
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            bundle = aab_fixture.make_aab(root / "release.aab")
            apk = aab_fixture.make_universal_apk(root / "universal.apk")
            binding(bundle, apk)
            changed = dict(aab_fixture.PAYLOAD)
            changed["dex/classes.dex"] = b"different code"
            wrong = aab_fixture.make_universal_apk(root / "wrong.apk", payload=changed)
            with self.assertRaisesRegex(ValueError, "bundle binding failed"):
                binding(bundle, wrong)


class WearManifestFactsTest(unittest.TestCase):
    def test_captured_apk_and_bundle_require_watch_hardware(self):
        from apk_facts import parse_manifest
        from aab_facts import parse_proto_manifest
        fixtures = pathlib.Path(__file__).parent / "fixtures"
        apk = parse_manifest((fixtures / "wear-1.0.0-AndroidManifest.xml").read_bytes())
        bundle = parse_proto_manifest((fixtures / "wear-1.0.0-aab-AndroidManifest.pb").read_bytes())
        self.assertTrue(apk["watch_required"])
        self.assertTrue(bundle["watch_required"])
        apk["permissions"] = sorted(apk["permissions"])
        self.assertEqual(apk, bundle)


class PublishedCodeAllocationTest(unittest.TestCase):
    def test_publishers_refuse_the_other_surfaces_code(self):
        validate_code("phone", 52)
        validate_code("wear", 51)
        for surface, code in (("phone", 51), ("wear", 52), ("wear", 1)):
            with self.assertRaises(ValueError):
                validate_code(surface, code)


class WearInternalUploadPolicyTest(unittest.TestCase):
    def test_internal_screenshots_can_reach_the_bundle_gate_without_a_published_phone(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import play
        args = SimpleNamespace(surface="wear", track="wear:internal", require_phone_code=None,
                               listing_json=None, wear_screenshots=pathlib.Path("approved-images"),
                               aab=pathlib.Path("bundle.aab"), apk=pathlib.Path("universal.apk"))
        with patch.object(play, "binding", side_effect=ValueError("fixture bundle refused")) as gate:
            with self.assertRaisesRegex(ValueError, "fixture bundle refused"):
                play.prepare(None, args)
            gate.assert_called_once_with(args.aab, args.apk)

    def test_review_tracks_cannot_reach_an_upload_without_the_phone(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import play
        for track, listing in (("wear:production", None), ("wear:beta", None),
                               ("wear:closed", None)):
            args = SimpleNamespace(surface="wear", track=track, require_phone_code=None, listing_json=listing)
            with self.subTest(track=track, listing=listing), patch.object(play, "binding") as gate:
                with self.assertRaisesRegex(ValueError, "published courier phone"):
                    play.prepare(None, args)
                gate.assert_not_called()

    def test_internal_listing_patch_is_refused_even_with_a_phone_code(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import play
        for code in (None, 52):
            args = SimpleNamespace(surface="wear", track="wear:internal", require_phone_code=code,
                                   listing_json=pathlib.Path("listing.json"))
            with self.subTest(phone_code=code), patch.object(play, "binding") as gate:
                with self.assertRaisesRegex(ValueError, "wear:production release edit"):
                    play.prepare(None, args)
                gate.assert_not_called()
