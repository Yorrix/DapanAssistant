#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y nginx python3-venv
id dapan >/dev/null 2>&1 || useradd --system --home-dir /opt/dapan --shell /usr/sbin/nologin dapan
install -d -m 755 /opt/dapan
install -d -o dapan -g dapan -m 700 /opt/dapan/data
install -d -o dapan -g dapan -m 700 /var/backups/dapan
install -d -m 755 /var/www/dapan-acme/.well-known/acme-challenge
python3 -m venv /opt/dapan/venv
/opt/dapan/venv/bin/pip install --index-url https://pypi.org/simple --timeout 20 --retries 1 -r /opt/dapan/backend/requirements.lock.txt
python3 -m venv /opt/dapan/certbot
/opt/dapan/certbot/bin/pip install --index-url https://pypi.org/simple --timeout 20 --retries 1 'certbot>=5.4,<6'
if [ ! -f /etc/dapan.env ]; then
 python3 - <<'PY'
import os,secrets
fd=os.open('/etc/dapan.env',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:
 f.write('DAPAN_MODE=production\nDAPAN_DATA=/opt/dapan/data\nSMTP_HOST=\nSMTP_PORT=465\nSMTP_USER=\nSMTP_PASSWORD=\nSMTP_FROM=\nADMIN_TOKEN='+secrets.token_urlsafe(40)+'\n')
PY
fi
chown -R root:root /opt/dapan/backend
chmod -R go-w /opt/dapan/backend
install -m 644 /opt/dapan/backend/dapan-api.service /etc/systemd/system/
install -m 644 /opt/dapan/backend/dapan-notices.service /etc/systemd/system/
install -m 644 /opt/dapan/backend/dapan-notices.timer /etc/systemd/system/
install -m 644 /opt/dapan/backend/deploy/dapan-backup.service /etc/systemd/system/
install -m 644 /opt/dapan/backend/deploy/dapan-backup.timer /etc/systemd/system/
install -m 644 /opt/dapan/backend/deploy/dapan-cert-renew.service /etc/systemd/system/
install -m 644 /opt/dapan/backend/deploy/dapan-cert-renew.timer /etc/systemd/system/
if [ ! -f /etc/nginx/conf.d/dapan.conf ]; then
 install -m 644 /opt/dapan/backend/deploy/nginx-bootstrap.conf /etc/nginx/conf.d/dapan.conf
fi
nginx -t
ufw allow 80/tcp comment 'Dapan certificate validation'
ufw allow 443/tcp comment 'Dapan HTTPS API'
systemctl daemon-reload
systemctl enable --now nginx dapan-api.service dapan-notices.timer dapan-backup.timer
systemctl reload nginx
systemctl start --no-block dapan-notices.service
curl --fail --silent --retry 10 --retry-connrefused --retry-delay 1 --max-time 3 http://127.0.0.1:8765/health
echo
/opt/dapan/certbot/bin/certbot --version
