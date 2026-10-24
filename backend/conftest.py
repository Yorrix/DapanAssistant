"""Keep tests independent of developer/production databases, mail and network settings."""
import ipaddress
import os
from pathlib import Path
import smtplib
import socket
import tempfile

import pytest


_runs = Path(__file__).resolve().parent / 'test-runs'
_runs.mkdir(exist_ok=True)
_store = tempfile.TemporaryDirectory(prefix='isolated-', dir=_runs, ignore_cleanup_errors=True)
_previous = {key: os.environ.get(key) for key in ('DAPAN_DATA', 'DAPAN_MODE')}
# app.py creates its ASGI application at import time, before per-test fixtures run.
os.environ['DAPAN_DATA'] = _store.name
os.environ['DAPAN_MODE'] = 'test'


def pytest_sessionfinish(session, exitstatus):
    for key, value in _previous.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value
    _store.cleanup()


def _loopback(host):
    if host in (None, '', 'localhost', b'localhost'):
        return True
    try:
        return ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host).is_loopback
    except ValueError:
        return False


@pytest.fixture(autouse=True)
def isolated_external_services(monkeypatch):
    """TestClient uses an in-process transport. Local event-loop sockets remain available."""
    for key in ('SMTP_HOST', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_FROM', 'SMTP_PORT',
                'DEEPSEEK_API_KEY', 'SHARED_AI_ENABLED', 'ADMIN_TOKEN'):
        monkeypatch.delenv(key, raising=False)
    original_connect = socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    original_getaddrinfo = socket.getaddrinfo

    def connect(sock, address):
        if sock.family in (socket.AF_INET, socket.AF_INET6):
            assert _loopback(address[0]), 'Backend tests cannot connect to external services'
        return original_connect(sock, address)

    def connect_ex(sock, address):
        if sock.family in (socket.AF_INET, socket.AF_INET6):
            assert _loopback(address[0]), 'Backend tests cannot connect to external services'
        return original_connect_ex(sock, address)

    def getaddrinfo(host, *args, **kwargs):
        assert _loopback(host), 'Backend tests cannot perform external DNS lookups'
        return original_getaddrinfo(host, *args, **kwargs)

    def no_mail(*args, **kwargs):
        raise AssertionError('Backend tests must use the injected in-memory mail sender')

    monkeypatch.setattr(socket.socket, 'connect', connect)
    monkeypatch.setattr(socket.socket, 'connect_ex', connect_ex)
    monkeypatch.setattr(socket, 'getaddrinfo', getaddrinfo)
    monkeypatch.setattr(smtplib, 'SMTP', no_mail)
    monkeypatch.setattr(smtplib, 'SMTP_SSL', no_mail)
