"""Small persistent Fun-ASR vocabulary cache for the single-worker relay.

Only hashes and provider vocabulary IDs are stored; no keys or transcripts.
Busy vocabularies are immutable. Failed/slow vocabulary setup must not stop speech.
API: https://help.aliyun.com/zh/model-studio/vocabulary-http-api (2026-10-26).
"""
import asyncio
import hashlib
import json
import os
import time

import httpx


class SpeechVocabulary:
    def __init__(self, budget, call=None, clock=time.time):
        self.budget, self.call, self.clock = budget, call or self._call, clock
        self.lock = asyncio.Lock()
        self.active = {}
        self.failed_until = {}
        with budget.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS speech_vocabulary(
                key_hash TEXT NOT NULL, word_hash TEXT NOT NULL, provider_id TEXT NOT NULL,
                touched INTEGER NOT NULL, PRIMARY KEY(key_hash,word_hash))''')

    async def _call(self, action, **arguments):
        async with httpx.AsyncClient(timeout=2.5, follow_redirects=False) as client:
            response = await client.post('https://dashscope.aliyuncs.com/api/v1/services/audio/asr/customization',
                headers={'Authorization': 'Bearer ' + os.environ.get('ALIYUN_API_KEY', '')},
                json={'model': 'speech-biasing', 'input': {'action': action, **arguments}})
            response.raise_for_status()
            result = response.json().get('output')
            if not isinstance(result, dict):
                raise ValueError('invalid vocabulary result')
            return result

    async def acquire(self, words):
        if not words:
            return None
        key = hashlib.sha256(os.environ.get('ALIYUN_API_KEY', '').encode()).hexdigest()
        digest = hashlib.sha256(json.dumps(words, ensure_ascii=False).encode()).hexdigest()
        cache_key = (key, digest)
        if self.failed_until.get(cache_key, 0) > self.clock():
            return None
        try:
            async with asyncio.timeout(7):
                async with self.lock:
                    with self.budget.db() as db:
                        rows = [dict(r) for r in db.execute('SELECT * FROM speech_vocabulary WHERE key_hash=? ORDER BY touched', (key,))]
                    known = next((r for r in rows if r['word_hash'] == digest), None)
                    if known:
                        identifier = known['provider_id']
                        if known['touched'] < self.clock() - 86400:
                            result = await self.call('query_vocabulary', vocabulary_id=identifier)
                            if result.get('status') != 'OK':
                                return None
                    else:
                        if len(rows) >= 5:
                            idle = next((r for r in rows if not self.active.get(r['provider_id']) and r['touched'] < self.clock() - 300), None)
                            if not idle:
                                return None
                            await self.call('delete_vocabulary', vocabulary_id=idle['provider_id'])
                            with self.budget.db() as db:
                                db.execute('DELETE FROM speech_vocabulary WHERE key_hash=? AND word_hash=?', (key, idle['word_hash']))
                        # Deterministic prefix allows recovery if creation succeeded but its response was lost.
                        prefix = 'dp' + digest[:8]
                        existing = await self.call('list_vocabulary', prefix=prefix, page_index=0, page_size=10)
                        identifier = None
                        for item in existing.get('vocabulary_list', []):
                            if item.get('status') != 'OK':
                                continue
                            candidate = item.get('vocabulary_id', '')
                            queried = await self.call('query_vocabulary', vocabulary_id=candidate)
                            if queried.get('target_model') == 'fun-asr-realtime' and [x.get('text') for x in queried.get('vocabulary', [])] == words:
                                identifier = candidate
                                break
                        if not identifier:
                            result = await self.call('create_vocabulary', target_model='fun-asr-realtime', prefix=prefix,
                                                     vocabulary=[{'text': word, 'weight': 3} for word in words])
                            identifier = result.get('vocabulary_id')
                            if not isinstance(identifier, str) or not identifier.startswith('vocab-') or len(identifier) > 160:
                                raise ValueError('invalid vocabulary ID')
                            # Persist before readiness query so a delayed deployment will be reused.
                            with self.budget.db() as db:
                                db.execute('INSERT OR REPLACE INTO speech_vocabulary VALUES(?,?,?,?)', (key, digest, identifier, 0))
                            result = await self.call('query_vocabulary', vocabulary_id=identifier)
                            if result.get('status') != 'OK':
                                return None
                    with self.budget.db() as db:
                        db.execute('INSERT OR REPLACE INTO speech_vocabulary VALUES(?,?,?,?)', (key, digest, identifier, int(self.clock())))
                    self.active[identifier] = self.active.get(identifier, 0) + 1
                    return identifier
        except asyncio.CancelledError:
            raise
        except Exception:
            # No provider exception text, key, or user vocabulary is written to logs.
            self.failed_until[cache_key] = self.clock() + 300
            return None

    def release(self, identifier):
        self.active[identifier] = max(0, self.active.get(identifier, 1) - 1)
        with self.budget.db() as db:
            db.execute('UPDATE speech_vocabulary SET touched=? WHERE provider_id=?', (int(self.clock()), identifier))
