#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
import argparse
import json
import ssl
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class UploadHandler(BaseHTTPRequestHandler):
    server_version = "RogbidUploadSpike/1.0"

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        body = b"ok\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_POST(self):
        if self.path != "/upload":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            self.send_error(400, "empty upload")
            return
        if length > self.server.max_bytes:
            self.send_error(413, "upload too large")
            return

        body = self.rfile.read(length)
        received_at = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        index = self.server.next_index()
        upload_path = self.server.out_dir / f"upload-{received_at}-{index:03d}.json"
        headers_path = self.server.out_dir / f"upload-{received_at}-{index:03d}.headers.json"
        upload_path.write_bytes(body)
        headers_path.write_text(json.dumps(dict(self.headers), indent=2, sort_keys=True) + "\n")

        response = {
            "ok": True,
            "bytes": len(body),
            "path": str(upload_path),
            "received_at": received_at,
        }
        response_bytes = json.dumps(response, sort_keys=True).encode("utf-8")
        print(f"UPLOAD_RECEIVED bytes={len(body)} path={upload_path}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(response_bytes)))
        self.end_headers()
        self.wfile.write(response_bytes)

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}", flush=True)


class UploadServer(ThreadingHTTPServer):
    def __init__(self, server_address, handler_class, out_dir, max_bytes):
        super().__init__(server_address, handler_class)
        self.out_dir = out_dir
        self.max_bytes = max_bytes
        self._index = 0

    def next_index(self):
        self._index += 1
        return self._index


def main():
    parser = argparse.ArgumentParser(description="Tiny HTTPS receiver for the Rogbid upload spike.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--max-bytes", type=int, default=10 * 1024 * 1024)
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    server = UploadServer((args.host, args.port), UploadHandler, out_dir, args.max_bytes)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)

    print(f"HTTPS_UPLOAD_SERVER https://{args.host}:{args.port}/upload out={out_dir}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
