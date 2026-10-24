#!/bin/bash
set -euo pipefail
/opt/dapan/certbot/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email --preferred-profile shortlived --webroot --webroot-path /var/www/dapan-acme --ip-address 39.105.161.86 --cert-name dapan-ip
cp /etc/nginx/conf.d/dapan.conf /root/dapan-nginx-before-https.conf
install -m 644 /opt/dapan/backend/deploy/nginx-https.conf /etc/nginx/conf.d/dapan.conf
if ! nginx -t; then
 cp /root/dapan-nginx-before-https.conf /etc/nginx/conf.d/dapan.conf
 exit 1
fi
systemctl reload nginx
systemctl enable --now dapan-cert-renew.timer
systemctl start dapan-backup.service
/usr/bin/python3 /opt/dapan/backend/deploy/verify_backup.py
