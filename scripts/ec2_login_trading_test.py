#!/usr/bin/env python3
"""Interactive read-only-session login for the separate Trading test SmartAPI app.

Run ONLY on the registered EC2 host. Password/PIN/TOTP and JWT never enter the shell
history, command line, logs, GitHub, or Mac. The login endpoint issues a session;
this script does not place, modify, or cancel broker orders.
"""
from __future__ import annotations

import getpass
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

EXPECTED_EGRESS = os.environ.get("ANGEL_ONE_REGISTERED_PUBLIC_IP", "13.202.233.29")
LOGIN_URL = "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword"
OUTPUT = Path.home() / ".config" / "quant-engine-test" / "trading-test.env"
JWT_PATTERN = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
MAC_PATTERN = re.compile(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")


class LoginError(Exception):
    pass


def public_ip() -> str:
    try:
        with urllib.request.urlopen("https://api.ipify.org", timeout=12) as response:
            value = response.read(64).decode("ascii").strip()
        ipaddress.IPv4Address(value)
    except (OSError, ValueError, UnicodeError) as exc:
        raise LoginError("Cannot verify outbound IPv4; refusing to authenticate") from exc
    if value != EXPECTED_EGRESS:
        raise LoginError("Outbound IP does not match Trading test registered IP; refusing authentication")
    return value


def network_identity() -> tuple[str, str]:
    try:
        run = subprocess.run(["ip", "-4", "route", "get", "1.1.1.1"],
                             check=True, capture_output=True, text=True, timeout=8)
        match = re.search(r"\bdev\s+([A-Za-z0-9_.-]+).*?\bsrc\s+(\d+\.\d+\.\d+\.\d+)\b",
                          run.stdout)
        if not match:
            raise LoginError("Could not resolve EC2 private IP and network interface")
        interface, local = match.group(1), match.group(2)
        ipaddress.IPv4Address(local)
        mac = (Path("/sys/class/net") / interface / "address").read_text().strip()
        if not MAC_PATTERN.fullmatch(mac):
            raise LoginError("Could not validate EC2 interface MAC address")
        return local, mac
    except (OSError, ValueError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as exc:
        raise LoginError("Could not determine EC2 local IP or MAC address") from exc


def login(api_key: str, client_code: str, pin: str, totp: str, local_ip: str, mac: str) -> str:
    payload = json.dumps({"clientcode": client_code, "password": pin, "totp": totp}).encode("utf-8")
    req = urllib.request.Request(
        LOGIN_URL,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-PrivateKey": api_key,
            "X-ClientLocalIP": local_ip,
            "X-ClientPublicIP": EXPECTED_EGRESS,
            "X-MACAddress": mac,
            "X-UserType": "USER",
            "X-SourceID": "WEB",
            "User-Agent": "quant-engine-test-credential-bootstrap/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = json.load(response)
    except urllib.error.HTTPError as exc:
        raise LoginError(f"SmartAPI login declined (HTTP {exc.code}); check app key, PIN, TOTP and entitlement") from exc
    except (OSError, ValueError) as exc:
        raise LoginError("SmartAPI login failed or returned invalid JSON") from exc
    if not isinstance(body, dict) or body.get("status") is not True:
        error_code = str(body.get("errorcode", "unknown")) if isinstance(body, dict) else "unknown"
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,25}", error_code):
            error_code = "unknown"
        raise LoginError(f"SmartAPI login rejected (code {error_code}); no secret data printed")
    data = body.get("data")
    jwt = data.get("jwtToken") if isinstance(data, dict) else None
    if not isinstance(jwt, str) or not JWT_PATTERN.fullmatch(jwt.removeprefix("Bearer ").strip()):
        raise LoginError("SmartAPI returned no structurally valid JWT")
    return jwt.removeprefix("Bearer ").strip()


def save_private_credentials(api_key: str, jwt: str, local_ip: str, mac: str,
                             target: Path = OUTPUT, client_code: str = "") -> None:
    if client_code and not re.fullmatch(r"[a-zA-Z0-9_-]+", client_code):
        raise LoginError("Invalid client code")
    for field in (api_key, jwt, local_ip, mac):
        if not field or "\n" in field or "\r" in field:
            raise LoginError("Invalid credential value; refusing to write")
    directory = target.parent
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.is_symlink() or directory.stat().st_uid != os.geteuid():
        raise LoginError("Credential directory is not owned by the current user")
    directory.chmod(0o700)
    if target.is_symlink() or (target.exists() and target.stat().st_uid != os.geteuid()):
        raise LoginError("Unexpected credential file owner or symlink; refusing to replace")
    contents = (
        f"ANGEL_ONE_API_KEY={api_key}\n"
        f"ANGEL_ONE_JWT_TOKEN={jwt}\n"
        f"ANGEL_ONE_LOCAL_IP={local_ip}\n"
        f"ANGEL_ONE_CLIENT_LOCAL_IP={local_ip}\n"
        f"ANGEL_ONE_CLIENT_PUBLIC_IP={EXPECTED_EGRESS}\n"
        f"ANGEL_ONE_CLIENT_CODE={client_code}\n"
        f"ANGEL_ONE_MAC_ADDRESS={mac}\n"
    )
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\n",
                                         prefix=".trading-test.", dir=directory,
                                         delete=False) as file:
            temp_path = Path(file.name)
            os.chmod(temp_path, 0o600)
            file.write(contents)
        os.replace(temp_path, target)
    finally:
        if temp_path:
            temp_path.unlink(missing_ok=True)


def main() -> int:
    try:
        os.umask(0o077)
        public_ip()
        local_ip, mac = network_identity()
        print("Verified Trading test registered EC2 egress. This login creates no orders.")
        print("Supply the Trading test API key, Angel One client code, PIN, and current authenticator TOTP.")
        api_key = getpass.getpass("Trading test API key (hidden): ").strip()
        client_code = getpass.getpass("Angel One client code (hidden): ").strip()
        pin = getpass.getpass("Angel One PIN/password (hidden): ")
        totp = getpass.getpass("Current 6-digit TOTP (hidden): ").strip()
        if not api_key or not client_code or not pin or not re.fullmatch(r"\d{6}", totp):
            raise LoginError("Missing key/client code/PIN or invalid six-digit TOTP")
        jwt = login(api_key, client_code, pin, totp, local_ip, mac)
        save_private_credentials(api_key, jwt, local_ip, mac, client_code=client_code)
        print("Trading test session saved to private EC2 file (mode 0600).")
        print("Session expires per Angel One policy; repeat this login when necessary.")
        return 0
    except (LoginError, EOFError, KeyboardInterrupt) as exc:
        print(f"Trading test login not configured: {str(exc) if isinstance(exc, LoginError) else 'Input cancelled'}",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
