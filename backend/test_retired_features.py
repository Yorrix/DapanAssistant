import pytest
from deploy.retire_radar import without_radar_route


def test_retirement_patch_keeps_unrelated_routes_and_is_idempotent():
    source = '''def create_app():
 @app.get('/notices')
 def notices():
  return {'items': []}
 @app.get('/reset-radar')
 def reset_radar():
  file = root/'reset-radar.json'
  return file.read_text()
 @app.post('/auth/code')
 def code(body):
  return body
'''
    updated = without_radar_route(source)
    assert '/reset-radar' not in updated
    assert " @app.get('/notices')\n def notices():\n  return {'items': []}\n" in updated
    assert " @app.post('/auth/code')\n def code(body):\n  return body\n" in updated
    assert without_radar_route(updated) == updated


def test_retirement_patch_refuses_a_route_shared_with_another_feature():
    source = '''@app.get('/notices')
@app.get('/reset-radar')
def reset_radar():
 return []
'''
    with pytest.raises(ValueError, match='shared route'):
        without_radar_route(source)
