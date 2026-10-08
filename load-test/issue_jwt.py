#!/usr/bin/env python3
"""Mint a local HS256 JWT. Usage: issue_jwt.py <secret> <subject> [role]"""
import base64
import hashlib
import hmac
import json
import sys
import time


def b64url(data: bytes) -> str:
	return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main() -> None:
	if len(sys.argv) < 3:
		sys.stderr.write("usage: issue_jwt.py <secret> <subject> [role]\n")
		sys.exit(2)
	secret, subject = sys.argv[1], sys.argv[2]
	role = sys.argv[3] if len(sys.argv) > 3 else "BUYER"
	now = int(time.time())
	header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
	payload = b64url(json.dumps({"sub": subject, "role": role, "iat": now, "exp": now + 86400},
			separators=(",", ":")).encode())
	sig = b64url(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
	print(f"{header}.{payload}.{sig}")


if __name__ == "__main__":
	main()
