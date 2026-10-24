"""Verify recovery into an isolated directory without changing live data."""
import pathlib,tempfile,tarfile,sqlite3,hmac
root=pathlib.Path('/var/backups/dapan')
archive=max(root.glob('dapan-*.tar.gz'))
with tempfile.TemporaryDirectory(prefix='restore-check-',dir=root) as temp:
 with tarfile.open(archive) as tar:tar.extractall(temp,filter='data')
 folder=pathlib.Path(temp)
 with sqlite3.connect(f'file:{folder}/community.db?mode=ro',uri=True) as db:
  assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  image_ids=[row[0] for row in db.execute('SELECT id FROM images')]
  assert all((folder/'images'/f'{identifier}.jpg').is_file() for identifier in image_ids)
  print('Recovered database integrity: ok; referenced image files: ok')
 assert hmac.compare_digest((folder/'secret.key').read_bytes(),pathlib.Path('/opt/dapan/data/secret.key').read_bytes())
 print('Recovered authentication key: matches; live database untouched')
