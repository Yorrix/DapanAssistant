import json
import pytest
from fastapi.testclient import TestClient
from app import create_app
from reset_radar import validate, cached

def test_status_missing_is_explicit(tmp_path):
    client = TestClient(create_app(tmp_path, mode='demo'))
    response = client.get('/reset-radar')
    assert response.status_code == 200
    assert response.json()['data'] is None
    assert response.json()['stale'] is True

def test_cache_preserves_source_and_marks_age(tmp_path):
    path = tmp_path/'reset-radar.json'
    payload = dict(data=dict(latest_reset=None, scheduled_reset=None, active_watch=None), fetched_at='2026-10-26T00:00:00Z', dapan_fetched_at=1000, stale=False, source_url='https://tibo.cc/')
    path.write_text(json.dumps(payload))
    assert cached(path, now=1200)['stale'] is False
    assert cached(path, now=1700)['stale'] is True
    assert cached(path, now=1700)['source_url'] == payload['source_url']

def test_invalid_payload_does_not_look_like_no_forecast():
    for value in ({}, dict(data={}), dict(data=None), []):
        with pytest.raises(ValueError): validate(value)
