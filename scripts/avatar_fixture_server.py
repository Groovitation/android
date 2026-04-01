#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import html
import json
import re
import threading
import urllib.parse
from dataclasses import dataclass, field
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FIXTURE_EMAIL = "fixture-user@groovitation.test"
FIXTURE_PASSWORD = "fixture-password"
SESSION_COOKIE = "groovitation_fixture_session"
SESSION_VALUE = "fixture"
INITIAL_AVATAR_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Z4V0AAAAASUVORK5CYII="
)


@dataclass
class AvatarState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    filename: str = "seeded-avatar.png"
    content_type: str = "image/png"
    payload: bytes = INITIAL_AVATAR_PNG
    version: int = 0
    upload_bytes: int = 0

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            return {
                "filename": self.filename,
                "content_type": self.content_type,
                "payload": self.payload,
                "version": self.version,
                "upload_bytes": self.upload_bytes,
            }

    def update(self, filename: str, content_type: str, payload: bytes) -> None:
        safe_name = Path(filename or "avatar-upload.png").name
        safe_type = content_type or "application/octet-stream"
        with self.lock:
            self.filename = safe_name
            self.content_type = safe_type
            self.payload = payload
            self.version += 1
            self.upload_bytes = len(payload)


def render_login_page(error: bool) -> bytes:
    error_html = (
        '<p id="login-error" class="error">Use the fixture credentials to sign in.</p>'
        if error
        else ""
    )
    return f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Groovitation Fixture Login</title>
    <style>
      body {{
        font-family: sans-serif;
        margin: 0;
        padding: 24px;
        background: #f6f4ef;
        color: #1d1d1d;
      }}
      main {{
        max-width: 480px;
        margin: 32px auto;
        background: white;
        border-radius: 16px;
        padding: 24px;
        box-shadow: 0 12px 30px rgba(0, 0, 0, 0.08);
      }}
      label {{
        display: block;
        margin-top: 16px;
        font-weight: 600;
      }}
      input, button {{
        width: 100%;
        margin-top: 8px;
        min-height: 48px;
        font-size: 16px;
      }}
      button {{
        border: 0;
        border-radius: 12px;
        background: #1257d6;
        color: white;
        font-weight: 700;
      }}
      .hint, .error {{
        margin-top: 16px;
      }}
      .error {{
        color: #a52020;
      }}
    </style>
  </head>
  <body>
    <main>
      <h1>Fixture Sign In</h1>
      <p class="hint">Use the seeded account to reach the avatar upload page.</p>
      {error_html}
      <form id="login-form" action="/session" method="post">
        <label for="login-email">Email</label>
        <input id="login-email" name="email" type="email" value="" autocomplete="username" />
        <label for="login-password">Password</label>
        <input id="login-password" name="password" type="password" value="" autocomplete="current-password" />
        <button id="login-submit" type="submit">Sign In</button>
      </form>
    </main>
  </body>
</html>
""".encode("utf-8")


def render_account_page(state: dict[str, object]) -> bytes:
    filename = html.escape(str(state["filename"]))
    version = int(state["version"])
    upload_bytes = int(state["upload_bytes"])
    if version == 0:
        status = "Seeded avatar ready for upload replacement."
    else:
        status = f"Uploaded {filename} ({upload_bytes} bytes)"

    payload = {
        "version": version,
        "filename": state["filename"],
        "uploadBytes": upload_bytes,
    }
    payload_json = json.dumps(payload).replace("</", "<\\/")
    return f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Groovitation Fixture Account</title>
    <style>
      body {{
        font-family: sans-serif;
        margin: 0;
        padding: 24px;
        background: linear-gradient(180deg, #f7f1e5 0%, #f2f6ff 100%);
        color: #1d1d1d;
      }}
      main {{
        max-width: 560px;
        margin: 24px auto;
        background: white;
        border-radius: 20px;
        padding: 24px;
        box-shadow: 0 16px 40px rgba(18, 87, 214, 0.12);
      }}
      img {{
        width: 128px;
        height: 128px;
        border-radius: 999px;
        display: block;
        object-fit: cover;
        border: 4px solid #dce7ff;
      }}
      .meta {{
        margin-top: 16px;
      }}
      .picker-button {{
        display: inline-flex;
        align-items: center;
        justify-content: center;
        margin-top: 20px;
        min-height: 52px;
        padding: 0 20px;
        border-radius: 14px;
        background: #1257d6;
        color: white;
        font-weight: 700;
      }}
      #avatar-input {{
        position: absolute;
        left: -9999px;
        width: 1px;
        height: 1px;
      }}
      .hint {{
        color: #5f5f5f;
      }}
    </style>
  </head>
  <body>
    <main id="account-page">
      <h1>Account Settings</h1>
      <p id="signed-in-user">fixture-user@groovitation.test</p>
      <img id="avatar-image" alt="Current avatar" src="/uploads/current-avatar.png?version={version}" />
      <div class="meta">
        <p id="avatar-status">{html.escape(status)}</p>
        <p id="avatar-version">{version}</p>
        <p class="hint">Choose a supported image file to replace the avatar.</p>
      </div>
      <form id="avatar-form" action="/users/avatar" method="post" enctype="multipart/form-data">
        <input id="avatar-input" name="avatar" type="file" accept="image/*" />
        <button id="avatar-picker-button" class="picker-button" type="button">Choose avatar image</button>
      </form>
    </main>
    <script>
      window.__avatarFixture = {payload_json};
      const input = document.getElementById("avatar-input");
      const pickerButton = document.getElementById("avatar-picker-button");
      const form = document.getElementById("avatar-form");
      const status = document.getElementById("avatar-status");

      pickerButton.addEventListener("click", function() {{
        input.click();
      }});

      input.addEventListener("change", function() {{
        if (!input.files || input.files.length === 0) return;
        status.textContent = "Uploading " + input.files[0].name + "...";
        form.requestSubmit();
      }});
    </script>
  </body>
</html>
""".encode("utf-8")


class FixtureHandler(BaseHTTPRequestHandler):
    server_version = "GroovitationAvatarFixture/1.0"

    @property
    def fixture_state(self) -> AvatarState:
        return self.server.fixture_state  # type: ignore[attr-defined]

    @property
    def fixture_port(self) -> int:
        return self.server.server_port

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/healthz":
            self.send_bytes(HTTPStatus.OK, b"ok", content_type="text/plain; charset=utf-8")
            return

        if path == "/":
            self.redirect("/users/edit" if self.is_authenticated() else "/login")
            return

        if path == "/login":
            query = urllib.parse.parse_qs(parsed.query)
            self.send_bytes(
                HTTPStatus.OK,
                render_login_page(error=query.get("error", ["0"])[0] == "1"),
                content_type="text/html; charset=utf-8",
            )
            return

        if path == "/users/edit":
            if not self.is_authenticated():
                self.redirect("/login")
                return
            self.send_bytes(
                HTTPStatus.OK,
                render_account_page(self.fixture_state.snapshot()),
                content_type="text/html; charset=utf-8",
            )
            return

        if path == "/uploads/current-avatar.png":
            snapshot = self.fixture_state.snapshot()
            self.send_bytes(
                HTTPStatus.OK,
                snapshot["payload"],
                content_type=str(snapshot["content_type"]),
            )
            return

        if path == "/android/version.json":
            body = json.dumps(
                {
                    "latest_version_name": "0.0.0",
                    "latest_version_code": 0,
                    "download_url": f"http://10.0.2.2:{self.fixture_port}/android/groovitation.apk",
                }
            ).encode("utf-8")
            self.send_bytes(HTTPStatus.OK, body, content_type="application/json")
            return

        if path == "/android/groovitation.apk":
            self.send_bytes(
                HTTPStatus.OK,
                b"fixture-apk-placeholder",
                content_type="application/octet-stream",
            )
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/session":
            params = urllib.parse.parse_qs(self.read_request_body().decode("utf-8"))
            email = params.get("email", [""])[0]
            password = params.get("password", [""])[0]
            if email == FIXTURE_EMAIL and password == FIXTURE_PASSWORD:
                self.redirect("/users/edit", set_cookie=True)
            else:
                self.redirect("/login?error=1")
            return

        if path == "/users/avatar":
            if not self.is_authenticated():
                self.redirect("/login")
                return

            filename, content_type, payload = self.read_avatar_upload()
            self.fixture_state.update(filename=filename, content_type=content_type, payload=payload)
            self.redirect("/users/edit")
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def read_request_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length)

    def read_avatar_upload(self) -> tuple[str, str, bytes]:
        content_type = self.headers.get("Content-Type", "")
        match = re.search(r"boundary=(?:\"([^\"]+)\"|([^;]+))", content_type)
        if not match:
            return ("avatar-upload.png", "application/octet-stream", b"")

        boundary = match.group(1) or match.group(2) or ""
        body = self.read_request_body()
        delimiter = f"--{boundary}".encode("utf-8")
        for part in body.split(delimiter):
            if b"Content-Disposition" not in part:
                continue

            candidate = part
            if candidate.startswith(b"\r\n"):
                candidate = candidate[2:]
            if candidate.endswith(b"--\r\n"):
                candidate = candidate[:-4]
            elif candidate.endswith(b"--"):
                candidate = candidate[:-2]
            elif candidate.endswith(b"\r\n"):
                candidate = candidate[:-2]
            if not candidate or candidate == b"--":
                continue

            header_block, separator, payload = candidate.partition(b"\r\n\r\n")
            if not separator:
                continue

            headers_text = header_block.decode("utf-8", "replace")
            if "name=\"avatar\"" not in headers_text:
                continue

            filename_match = re.search(r"filename=\"([^\"]*)\"", headers_text)
            mime_match = re.search(r"Content-Type:\s*([^\r\n]+)", headers_text, re.IGNORECASE)
            clean_payload = payload[:-2] if payload.endswith(b"\r\n") else payload
            return (
                filename_match.group(1) if filename_match else "avatar-upload.png",
                mime_match.group(1).strip() if mime_match else "application/octet-stream",
                clean_payload,
            )

        return ("avatar-upload.png", "application/octet-stream", b"")

    def is_authenticated(self) -> bool:
        cookie = SimpleCookie(self.headers.get("Cookie"))
        session = cookie.get(SESSION_COOKIE)
        return session is not None and session.value == SESSION_VALUE

    def redirect(self, location: str, set_cookie: bool = False) -> None:
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", location)
        if set_cookie:
            self.send_header("Set-Cookie", f"{SESSION_COOKIE}={SESSION_VALUE}; Path=/; SameSite=Lax")
        self.end_headers()

    def send_bytes(self, status: HTTPStatus, payload: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[fixture] {self.address_string()} - {fmt % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Groovitation avatar upload fixture server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3000)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), FixtureHandler)
    server.fixture_state = AvatarState()  # type: ignore[attr-defined]
    print(f"fixture server listening on http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
