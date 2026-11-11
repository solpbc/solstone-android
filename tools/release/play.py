#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
"""Controller-side Play release rail. Requires requests, PyJWT, and PLAY_SERVICE_ACCOUNT.

prepare uploads a bound bundle and stages one track, with optional approved
listing patches and Wear screenshots. It never commits. commit validates the
saved edit and refuses to cancel an existing review. Every API reply is saved
before the next call. Keep credentials on the controller, never the build host.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import quote

from aab_facts import aab_facts
from surface import validate_code

PACKAGE = "app.solstone.observer.phone"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def published(reply, code):
    return any(r.get("releaseLifecycleState") == "RELEASE_LIFECYCLE_STATE_PUBLISHED"
               and any(str(a.get("versionCode")) == str(code) for a in r.get("activeArtifacts", []))
               for r in reply.get("releases", []))


class Client:
    def __init__(self, out):
        import jwt
        import requests
        self.out = out
        self.out.mkdir(parents=True, exist_ok=True)
        self.http = requests.Session()
        credential = Path(os.environ["PLAY_SERVICE_ACCOUNT"])
        sa = json.loads(credential.read_text())
        now = int(time.time())
        assertion = jwt.encode({"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
                                "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600},
                               sa["private_key"], algorithm="RS256")
        response = self.http.post("https://oauth2.googleapis.com/token", data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}, timeout=30)
        response.raise_for_status()
        self.http.headers["Authorization"] = f"Bearer {response.json()['access_token']}"

    def save(self, name, value):
        destination = self.out / f"{name}.json"
        with destination.open("x") as handle:
            json.dump(value, handle, indent=2)
            handle.write("\n")

    def call(self, name, method, url, **kwargs):
        response = self.http.request(method, url, timeout=600, **kwargs)
        try:
            value = response.json() if response.content else {}
        except ValueError:
            value = {"body": response.text}
        self.save(name, {"at": datetime.now(timezone.utc).isoformat(), "status": response.status_code, "body": value})
        if not response.ok:
            raise RuntimeError(f"{name}: HTTP {response.status_code}; see {self.out / (name + '.json')}. "
                               "Resolve the refusal before another attempt; never cancel a review to retry.")
        return value

    def lifecycle(self, name, track):
        return self.call(name, "GET", f"{BASE}/tracks/{quote(track, safe='')}/releases")

    def require_phone(self, name, code):
        reply = self.lifecycle(name, "production")
        if not published(reply, code):
            raise ValueError(f"phone {code} is not PUBLISHED; Wear publication must wait")


def binding(aab, apk):
    spec = importlib.util.spec_from_file_location("bundle_binding", Path(__file__).with_name("verify-bundle-binding.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    findings, receipt = module.verify(aab, apk)
    if findings:
        raise ValueError("bundle binding failed: " + "; ".join(findings))
    return receipt


def prepare(client, args):
    if (args.surface == "wear") != args.track.startswith("wear:"):
        raise ValueError("Wear requires a wear: track; phone requires a phone track")
    if args.surface == "wear" and args.listing_json and args.track != "wear:production":
        raise ValueError("Wear listing patches accompany the wear:production release edit")
    internal_wear = args.track == "wear:internal"
    if args.surface == "wear" and not internal_wear and args.require_phone_code is None:
        raise ValueError("Wear review or listing changes require --require-phone-code for the published courier phone")
    receipt = binding(args.aab, args.apk)
    client.save("bundle-binding", receipt)
    facts = aab_facts(args.aab)
    if facts["package"] != PACKAGE or facts["watch_required"] != (args.surface == "wear"):
        raise ValueError("bundle package or required watch feature differs from selected surface")
    if args.require_phone_code is not None:
        client.require_phone("phone-before", args.require_phone_code)
    validate_code(args.surface, facts["version_code"])
    bundle = args.aab.read_bytes()
    notes = args.notes.read_text().strip()
    if not notes or len(notes) > 500:
        raise ValueError("Play release notes must contain 1–500 characters")
    edit = client.call("edit", "POST", f"{BASE}/edits", json={})
    edit_url = f"{BASE}/edits/{edit['id']}"
    upload_url = f"{UPLOAD}/edits/{edit['id']}"
    tracks = client.call("tracks-before", "GET", f"{edit_url}/tracks")["tracks"]
    if not any(t["track"] == args.track for t in tracks):
        if not args.track.startswith("wear:") or args.track in ("wear:production", "wear:beta", "wear:internal", "wear:qa"):
            raise ValueError("target track does not exist")
        client.call("track-created", "POST", f"{edit_url}/tracks", json={
            "track": args.track, "type": "CLOSED_TESTING", "formFactor": "WEAR"})
    existing = client.call("bundles-before", "GET", f"{edit_url}/bundles").get("bundles", [])
    match = next((b for b in existing if int(b["versionCode"]) == facts["version_code"]), None)
    if match is None:
        match = client.call("bundle-uploaded", "POST", f"{upload_url}/bundles?uploadType=media",
                            data=bundle, headers={"Content-Type": "application/octet-stream"})
    if match.get("sha256") != digest(bundle) or int(match["versionCode"]) != facts["version_code"]:
        raise ValueError("Play bundle digest/version differs from the bound artifact")
    listings = client.call("listings-before", "GET", f"{edit_url}/listings").get("listings", [])
    if args.listing_json:
        patches = json.loads(args.listing_json.read_text())
        known = {item["language"]: item for item in listings}
        for language, patch in patches.items():
            if language not in known or set(patch) - {"title", "shortDescription", "fullDescription", "video"}:
                raise ValueError("listing patch has an unknown language or field")
            client.call(f"listing-{language}", "PUT", f"{edit_url}/listings/{quote(language, safe='')}",
                        json={**known[language], **patch})
    if args.wear_screenshots:
        if args.surface != "wear":
            raise ValueError("Wear screenshots require the Wear surface")
        images = sorted(args.wear_screenshots.glob("wear-[1-8]-*.png"))
        if not 1 <= len(images) <= 8:
            raise ValueError("expected 1–8 ordered wear-N-*.png screenshots")
        for listing in listings:
            language = listing["language"]
            image_url = f"listings/{quote(language, safe='')}/wearScreenshots"
            current = client.call(f"images-before-{language}", "GET", f"{edit_url}/{image_url}").get("images", [])
            if current:
                raise ValueError("Wear screenshots already exist; review them before replacing the set")
            for index, image in enumerate(images, 1):
                data = image.read_bytes()
                reply = client.call(f"image-{language}-{index}", "POST", f"{upload_url}/{image_url}?uploadType=media",
                                    data=data, headers={"Content-Type": "image/png"})
                if reply.get("image", {}).get("sha256") != digest(data):
                    raise ValueError("Play screenshot digest differs from supplied bytes")
            client.call(f"images-after-{language}", "GET", f"{edit_url}/{image_url}")
    body = {"track": args.track, "releases": [{"name": facts["version_name"],
            "versionCodes": [str(facts["version_code"])], "status": "completed",
            "releaseNotes": [{"language": "en-US", "text": notes}]}]}
    track = client.call("track-prepared", "PUT", f"{edit_url}/tracks/{quote(args.track, safe='')}", json=body)
    client.call("validation", "POST", f"{edit_url}:validate")
    client.save("prepared", {"edit": edit["id"], "track": args.track, "surface": args.surface,
                             "version": facts["version_name"], "code": facts["version_code"],
                             "aab_sha256": digest(bundle), "track_body": track,
                             "require_phone_code": args.require_phone_code})
    print(json.dumps({"edit": edit["id"], "track": args.track, "code": facts["version_code"], "committed": False}))


def commit(client):
    prepared = json.loads((client.out / "prepared.json").read_text())
    if prepared["require_phone_code"] is not None:
        client.require_phone("phone-precommit", prepared["require_phone_code"])
    edit_url = f"{BASE}/edits/{prepared['edit']}"
    track = client.call("track-precommit", "GET", f"{edit_url}/tracks/{quote(prepared['track'], safe='')}")
    if track != prepared["track_body"]:
        raise ValueError("prepared track changed; review the edit before committing")
    client.call("validation-precommit", "POST", f"{edit_url}:validate")
    client.call("committed", "POST", f"{edit_url}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW")
    print(json.dumps({"edit": prepared["edit"], "committed": True}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="private evidence directory; replies are never overwritten")
    commands = parser.add_subparsers(dest="command", required=True)
    read = commands.add_parser("releases", help="GET-only lifecycle, saved separately per track")
    read.add_argument("tracks", nargs="+", help="production, internal, wear:production, or a Wear testing track")
    build = commands.add_parser("prepare", help="stage and validate an edit without committing")
    build.add_argument("--surface", choices=("phone", "wear"), required=True)
    build.add_argument("--track", required=True)
    build.add_argument("--aab", type=Path, required=True)
    build.add_argument("--apk", type=Path, required=True, help="universal APK bound to --aab")
    build.add_argument("--notes", type=Path, required=True)
    build.add_argument("--require-phone-code", type=int)
    build.add_argument("--listing-json", type=Path, help="approved language-to-listing-field patches")
    build.add_argument("--wear-screenshots", type=Path)
    commands.add_parser("commit", help="commit saved prepared.json with ERROR_IF_IN_REVIEW")
    args = parser.parse_args()
    import requests
    if args.command == "prepare" and args.out.exists() and any(args.out.iterdir()):
        parser.error("prepare needs a fresh evidence directory")
    os.umask(0o077)
    try:
        client = Client(args.out)
        if args.command == "releases":
            for track in args.tracks:
                if not re.fullmatch(r"[A-Za-z0-9_:-]+", track):
                    raise ValueError("invalid track name")
                print(json.dumps({track: client.lifecycle(f"lifecycle-{track.replace(':', '-')}", track)}))
        elif args.command == "prepare":
            prepare(client, args)
        else:
            commit(client)
    except (KeyError, ValueError, RuntimeError, OSError, requests.RequestException) as error:
        parser.exit(1, f"Play release rail: {error}\n")


if __name__ == "__main__":
    main()
