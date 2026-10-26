"""Authenticated continuous PCM relay with prepaid rolling usage; no stored audio."""
from __future__ import annotations

import asyncio
import contextlib
import json
import math
import os
import time
import uuid

from fastapi import HTTPException, WebSocket, WebSocketDisconnect
from shared_ai import MESSAGES

BYTES_PER_SECOND = 32_000
MAX_FRAME = 16_384
MICRO_CNY_PER_SECOND = 330
RESERVATION_SECONDS = 10
RESERVATION = RESERVATION_SECONDS * MICRO_CNY_PER_SECOND
UPSTREAM = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference'
ERRORS = {
    'disabled': '公用语音暂未开通，请稍后再试，或切换到自备 API。',
    'invalid': '语音请求无效，请重新按住说话。',
    'idle': '语音连接已超时，请重新按住说话。',
    'upstream': '公用语音暂时不可用，本次没有发送，请稍后再试。',
    'interrupted': '语音连接中断，本次没有发送，请重试。',
}


class SpeechError(Exception):
    def __init__(self, code):
        self.code = code


def start_payload(raw):
    """Rebuild a fixed request rather than passing client-controlled payloads."""
    try:
        header = raw['header']
        if header['action'] != 'run-task':
            raise ValueError()
        identifier = str(uuid.UUID(header['task_id']))
        if header['task_id'] != identifier:
            raise ValueError()
        source = raw.get('payload', {})
        supplied = source.get('parameters', {})
        languages = supplied.get('language_hints', [])
        supported = {'zh','en','ja','ko','vi','th','id','ms','tl','hi','ar','fr','de','es','pt','ru','it','nl','sv','da','fi','no','el','pl','cs','hu','ro','bg','hr','sk'}
        if not isinstance(languages, list) or len(languages) > 1 or any(x not in supported for x in languages):
            raise ValueError()
        parameters = {'format': 'pcm', 'sample_rate': 16000, 'heartbeat': True}
        if languages:
            parameters['language_hints'] = list(dict.fromkeys(languages))
        for name in ('semantic_punctuation_enabled', 'multi_threshold_mode_enabled'):
            if name in supplied:
                if type(supplied[name]) is not bool:
                    raise ValueError()
                parameters[name] = supplied[name]
        if 'max_sentence_silence' in supplied:
            silence = supplied['max_sentence_silence']
            if type(silence) is not int or not 200 <= silence <= 6000:
                raise ValueError()
            parameters['max_sentence_silence'] = silence
        words = source.get('dapan_hotwords', [])
        if not isinstance(words, list) or len(words) > 60 or any(
            not isinstance(w, str) or not w.strip() or len(w) > 80 or
            (not w.isascii() and len(w) > 15) or (w.isascii() and len(w.split()) > 7) for w in words
        ) or len('、'.join(words)) > 220:
            raise ValueError()
        context = source.get('input', {}).get('context', [])
        if not isinstance(context, list) or len(context) > 1:
            raise ValueError()
        input_value = {}
        if context:
            content = context[0].get('content', [])
            if not isinstance(content, list) or len(content) != 1:
                raise ValueError()
            hint = content[0].get('text', '')
            if not isinstance(hint, str) or len(hint) > 400:
                raise ValueError()
            input_value['context'] = [{'role': 'user', 'content': [{'type': 'input_text', 'text': hint}]}]
        return identifier, {
            'header': {'action': 'run-task', 'task_id': identifier, 'streaming': 'duplex'},
            'payload': {'task_group': 'audio', 'task': 'asr', 'function': 'recognition',
                        'model': 'fun-asr-realtime', 'parameters': parameters,
                        'dapan_hotwords': list(dict.fromkeys(w.strip() for w in words)), 'input': input_value}}
    except (KeyError, ValueError, TypeError, AttributeError):
        raise SpeechError('invalid') from None


def event(identifier, kind, **header):
    return {'header': {'task_id': identifier, 'event': kind, **header}, 'payload': {}}


def settle_cost(forwarded, provider_seconds):
    # Provider usage is cumulative and emitted only for completed sentences.
    # Account for a disconnected/unfinalized tail using bytes already submitted.
    seconds = max(math.ceil(forwarded / BYTES_PER_SECOND), provider_seconds)
    return seconds * MICRO_CNY_PER_SECOND


class SharedSpeech:
    def __init__(self, ai, connector=None):
        self.ai = ai
        self.connector = connector
        self.session_timeout = None
        self.audio_idle_timeout = 15
        self.finish_timeout = 12
        from speech_vocabulary import SpeechVocabulary
        self.vocabulary = SpeechVocabulary(ai.budget)

    @property
    def enabled(self):
        return os.environ.get('SHARED_SPEECH_ENABLED') == '1' and bool(os.environ.get('ALIYUN_API_KEY', '').strip())

    def connect(self):
        if self.connector:
            return self.connector()
        from websockets.asyncio.client import connect
        return connect(UPSTREAM, additional_headers={'Authorization': 'Bearer ' + os.environ['ALIYUN_API_KEY']},
                       open_timeout=12, close_timeout=2, ping_interval=20, ping_timeout=10,
                       max_size=262_144, max_queue=8)

    async def handle(self, ws: WebSocket):
        await ws.accept()
        identifier = ''
        reserved = False
        settled = False
        forwarded = 0
        provider_seconds = 0
        uncertain = False
        vocabulary_id = None
        reserved_cost = RESERVATION
        finished = asyncio.Event()

        def settle():
            nonlocal settled
            if reserved and not settled:
                self.ai.budget.settle_amount(identifier, settle_cost(forwarded, provider_seconds),
                                            state='uncertain' if uncertain else 'charged')
                settled = True

        try:
            user = self.ai.user(ws.headers.get('authorization'))
            if not self.enabled:
                raise SpeechError('disabled')
            self.ai.service.rate('shared-speech:' + user['id'], 60, 60)
            first = await asyncio.wait_for(ws.receive(), 5)
            text = first.get('text', '')
            if first.get('type') == 'websocket.disconnect':
                return
            if not isinstance(text, str) or not text or len(text.encode('utf-8')) > 8192:
                raise SpeechError('invalid')
            identifier, body = start_payload(json.loads(text))
            self.ai.budget.reserve(identifier, user['id'], RESERVATION, kind='asr')
            reserved = True
            vocabulary_id = await self.vocabulary.acquire(body['payload'].pop('dapan_hotwords', []))
            if vocabulary_id:
                body['payload']['parameters']['vocabulary_id'] = vocabulary_id
            async with asyncio.timeout(self.session_timeout):
                async with self.connect() as upstream:
                    await upstream.send(json.dumps(body, ensure_ascii=False))
                    raw_ready = await asyncio.wait_for(upstream.recv(), 12)
                    ready = json.loads(raw_ready)
                    if ready.get('header', {}).get('event') != 'task-started':
                        raise SpeechError('upstream')
                    await ws.send_json(event(identifier, 'task-started'))

                    async def send_audio():
                        nonlocal forwarded, reserved_cost
                        while True:
                            message = await asyncio.wait_for(ws.receive(), self.audio_idle_timeout)
                            if message['type'] == 'websocket.disconnect':
                                raise WebSocketDisconnect()
                            pcm = message.get('bytes')
                            if pcm is not None:
                                if not pcm or len(pcm) > MAX_FRAME or len(pcm) % 2:
                                    raise SpeechError('invalid')
                                needed = (math.ceil((forwarded + len(pcm)) / BYTES_PER_SECOND) + 1) * MICRO_CNY_PER_SECOND
                                if needed > reserved_cost:
                                    larger = max(needed, reserved_cost + RESERVATION)
                                    try:
                                        self.ai.budget.extend(identifier, larger)
                                        reserved_cost = larger
                                    except HTTPException as exc:
                                        if exc.status_code != 429:
                                            raise
                                        # Spend the usable remainder instead of requiring another full block.
                                        self.ai.budget.extend(identifier, needed)
                                        reserved_cost = needed
                                # Count before sending: a send failure may still have delivered bytes.
                                forwarded += len(pcm)
                                await upstream.send(pcm)
                            else:
                                command = message.get('text', '')
                                if len(command) > 1024:
                                    raise SpeechError('invalid')
                                stop = json.loads(command)
                                header = stop.get('header', {})
                                if header.get('action') != 'finish-task' or header.get('task_id') != identifier:
                                    raise SpeechError('invalid')
                                finished.set()
                                await upstream.send(json.dumps({'header': {
                                    'action': 'finish-task', 'task_id': identifier, 'streaming': 'duplex'},
                                    'payload': {'input': {}}}))
                                return

                    async def receive_results():
                        nonlocal provider_seconds, uncertain
                        while True:
                            # Silence still sends PCM; sender/absolute deadlines own idle limits.
                            raw = await upstream.recv()
                            data = json.loads(raw)
                            kind = data.get('header', {}).get('event')
                            usage = data.get('payload', {}).get('usage')
                            if isinstance(usage, dict):
                                seconds = usage.get('duration')
                                if isinstance(seconds, int) and not isinstance(seconds, bool) and seconds >= 0:
                                    if seconds > math.ceil(forwarded / BYTES_PER_SECOND) + 1:
                                        # Never silently hide an unexpected supplier charge.
                                        provider_seconds = seconds
                                        uncertain = True
                                        raise SpeechError('upstream')
                                    provider_seconds = max(provider_seconds, seconds)
                            if kind == 'result-generated':
                                sentence = data.get('payload', {}).get('output', {}).get('sentence')
                                if not isinstance(sentence, dict):
                                    continue
                                if sentence.get('heartbeat'):
                                    # A silent classroom still needs downstream traffic through the reverse proxy.
                                    await ws.send_json({'header': {'task_id': identifier, 'event': kind},
                                                       'payload': {'output': {'sentence': {'heartbeat': True}}}})
                                    continue
                                # Strip provider metadata and enforce a bounded result surface.
                                clean = {k: sentence[k] for k in ('text', 'sentence_id', 'sentence_end', 'begin_time', 'end_time') if k in sentence}
                                if not isinstance(clean.get('text'), str) or len(clean['text']) > 16_000:
                                    raise SpeechError('upstream')
                                await ws.send_json({'header': {'task_id': identifier, 'event': kind},
                                                    'payload': {'output': {'sentence': clean}}})
                            elif kind == 'task-finished':
                                if not finished.is_set():
                                    raise SpeechError('upstream')
                                settle()  # Release the hold before the client starts its AI request.
                                await ws.send_json(event(identifier, kind))
                                return
                            elif kind == 'task-failed':
                                raise SpeechError('upstream')

                    sender = asyncio.create_task(send_audio())
                    receiver = asyncio.create_task(receive_results())
                    try:
                        done, _ = await asyncio.wait({sender, receiver}, return_when=asyncio.FIRST_COMPLETED)
                        for task in done:
                            task.result()
                        if receiver not in done:
                            await asyncio.wait_for(receiver, self.finish_timeout)
                    finally:
                        sender.cancel()
                        receiver.cancel()
                        await asyncio.gather(sender, receiver, return_exceptions=True)
        except WebSocketDisconnect:
            pass
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if isinstance(exc, HTTPException):
                detail = exc.detail if isinstance(exc.detail, dict) else {}
                fallback = 'login_required' if exc.status_code in (401, 403) else 'busy' if exc.status_code == 429 else 'interrupted'
                code = detail.get('code', fallback)
                message = MESSAGES.get(code, '公用语音请求未完成，请稍后再试。')
            else:
                code = exc.code if isinstance(exc, SpeechError) else 'idle' if isinstance(exc, TimeoutError) else 'interrupted'
                message = ERRORS.get(code, ERRORS['interrupted'])
            settle()
            with contextlib.suppress(Exception):
                await ws.send_json(event(identifier, 'task-failed', error_code=code, error_message=message))
        finally:
            settle()
            if vocabulary_id:
                self.vocabulary.release(vocabulary_id)
            with contextlib.suppress(Exception):
                await ws.close()


def install_shared_speech(app, connector=None):
    relay = SharedSpeech(app.state.shared_ai, connector)
    app.state.shared_speech = relay

    @app.websocket('/ai/speech')
    async def speech(ws: WebSocket):
        await relay.handle(ws)

    return relay
