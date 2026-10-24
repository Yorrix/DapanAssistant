"""Private local snapshot, consistent SQLite copy, 7 daily backups retained."""
import pathlib,sqlite3,shutil,tempfile,tarfile,os
from datetime import datetime,timezone
ROOT=pathlib.Path('/opt/dapan/data')
BACKUPS=pathlib.Path('/var/backups/dapan')
def main():
 os.umask(0o077)
 BACKUPS.mkdir(exist_ok=True,parents=True)
 with tempfile.TemporaryDirectory(prefix='snapshot-',dir=BACKUPS) as temp:
  target=pathlib.Path(temp)
  with sqlite3.connect(f'file:{ROOT}/community.db?mode=ro',uri=True) as source, sqlite3.connect(target/'community.db') as dest:
   source.backup(dest)
   assert dest.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  shutil.copy2(ROOT/'secret.key',target/'secret.key')
  if (ROOT/'shared-ai.db').exists():
   with sqlite3.connect(f'file:{ROOT}/shared-ai.db?mode=ro',uri=True) as source, sqlite3.connect(target/'shared-ai.db') as dest:
    source.backup(dest)
    assert dest.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  for name in ('images','notices.json'):
   source=ROOT/name
   if source.is_dir():shutil.copytree(source,target/name)
   elif source.exists():shutil.copy2(source,target/name)
  stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
  archive=BACKUPS/f'dapan-{stamp}.tar.gz'
  partial=archive.with_suffix('.partial')
  with tarfile.open(partial,'w:gz') as tar:
   for path in target.iterdir():tar.add(path,arcname=path.name,recursive=True)
  partial.replace(archive)
 for old in sorted(BACKUPS.glob('dapan-*.tar.gz'),reverse=True)[7:]:
  if old.is_file() and not old.is_symlink() and old.resolve().parent==BACKUPS.resolve():old.unlink()
 print('Backup created; SQLite integrity checked.')
if __name__=='__main__':main()
