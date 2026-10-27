#!/usr/bin/env python3
"""Upload the backend code and restart the service (config file is preserved)."""
import os
import sys

import paramiko

HOST = "9.247.112.23"
PASSWORD = os.environ.get("SRV_PASS", "")

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PASSWORD, timeout=30, banner_timeout=45,
          auth_timeout=45, allow_agent=False, look_for_keys=False)
sftp = c.open_sftp()
sftp.put("server/app.py", "/opt/una/app.py")
sftp.close()

for cmd in [
    "systemctl restart una-backend && sleep 2 && systemctl is-active una-backend",
    "ls -l /opt/una/config.json",
    "TOKEN=$(cat /opt/una/token); curl -sS -m 5 -H \"X-Una-Token: $TOKEN\" http://127.0.0.1:8090/config | head -c 120; echo",
    "curl -sS -m 8 -o /dev/null -w 'https:%{http_code}\\n' https://9.247.112.23.sslip.io/health",
]:
    _, out, err = c.exec_command(cmd, timeout=120)
    print(f"### {cmd}\n{out.read().decode('utf-8', 'replace').strip()}")
    e = err.read().decode("utf-8", "replace").strip()
    if e:
        print("[stderr]", e)
c.close()
