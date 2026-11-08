#!/usr/bin/env python3
"""
Minimal OpenID Connect provider (Keycloak URL layout) for testing Supabase Auth
identity linking locally. NEVER use outside tests: it signs in whoever asks.

  GET  /realms/loopa/protocol/openid-connect/auth?redirect_uri=..&state=..&fake_user=alice
       -> 302 redirect_uri?code=alice.<n>&state=..
  POST /realms/loopa/protocol/openid-connect/token      (code=alice.<n>) -> access token
  GET  /realms/loopa/protocol/openid-connect/userinfo   (Bearer tok-alice) -> claims
"""
import itertools, json, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

counter = itertools.count(1)
PREFIX = "/realms/loopa/protocol/openid-connect"


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(url.query))
        if url.path == PREFIX + "/auth":
            user = q.get("fake_user", "alice")
            if user == "cancel":
                target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"error": "access_denied", "state": q.get("state", "")})
            else:
                target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": f"{user}.{next(counter)}", "state": q.get("state", "")})
            self.send_response(302); self.send_header("Location", target); self.end_headers()
        elif url.path == PREFIX + "/userinfo":
            token = self.headers.get("Authorization", "").split(" ")[-1]
            user = token.removeprefix("tok-")
            self._json(200, {"sub": f"sub-{user}", "email": f"{user}@loopa.test", "email_verified": True,
                             "name": f"{user.capitalize()} Test", "preferred_username": user})
        else:
            self._json(404, {"error": "not_found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        form = dict(urllib.parse.parse_qsl(self.rfile.read(length).decode()))
        if self.path.startswith(PREFIX + "/token"):
            user = form.get("code", "x.0").split(".")[0]
            self._json(200, {"access_token": f"tok-{user}", "token_type": "Bearer", "expires_in": 3600, "refresh_token": "r"})
        else:
            self._json(404, {"error": "not_found"})

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-oidc: " + fmt % args + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
