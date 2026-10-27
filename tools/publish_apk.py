#!/usr/bin/env python3
"""Publish an APK to the Una server so that installed apps can self-update."""
import hashlib
import json
import os
import sys

import paramiko

HOST = "9.247.112.23"
PASSWORD = os.environ.get("SRV_PASS", "")
APK = sys.argv[1] if len(sys.argv) > 1 else "Una-1.6.0.apk"
VERSION_CODE = int(sys.argv[2]) if len(sys.argv) > 2 else 16
VERSION_NAME = sys.argv[3] if len(sys.argv) > 3 else "1.6.0-una"

digest = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print(f"APK: {APK}  {os.path.getsize(APK) / 1024 / 1024:.1f} MB")
print(f"sha256: {digest}")

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, username="root", password=PASSWORD, timeout=30, banner_timeout=45,
               auth_timeout=45, allow_agent=False, look_for_keys=False)

sftp = client.open_sftp()
client.exec_command("mkdir -p /opt/una/download")
import time as _time

_time.sleep(1)
sftp.put(APK, "/opt/una/download/una.apk")
version = {
    "versionCode": VERSION_CODE,
    "versionName": VERSION_NAME,
    "url": "https://9.247.112.23.sslip.io/download/una.apk",
    "sha256": digest,
    "size": os.path.getsize(APK),
}
with sftp.open("/opt/una/version.json", "w") as handle:
    handle.write(json.dumps(version, ensure_ascii=False, indent=2))
sftp.close()


def run(cmd: str, timeout: int = 300) -> str:
    _, out, err = client.exec_command(cmd, timeout=timeout)
    return out.read().decode("utf-8", "replace").strip()


print(run("ls -l /opt/una/download/una.apk /opt/una/version.json"))
print(run("curl -sS -m 15 https://9.247.112.23.sslip.io/version"))
print(run("curl -sS -m 60 -o /dev/null -w 'apk: %{http_code} %{size_download} bytes\\n' "
          "https://9.247.112.23.sslip.io/download/una.apk"))
client.close()
