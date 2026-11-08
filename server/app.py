"""Optional authenticated V-Get engine. Run behind HTTPS; never expose without a token."""
from __future__ import annotations

import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError

MAX_BODY = 16 * 1024
MAX_MEDIA = 1024 * 1024 * 1024
MAX_METADATA = 8 * 1024 * 1024
SLOTS = threading.BoundedSemaphore(2)
SOURCES = {
    'facebook': ('facebook.com', 'fb.com', 'fb.watch'),
    'youtube': ('youtube.com', 'youtu.be', 'youtube-nocookie.com'),
    'instagram': ('instagram.com',),
    'threads': ('threads.com', 'threads.net'),
    'xiaohongshu': ('xiaohongshu.com', 'rednote.com', 'xhslink.cn', 'xhslink.com'),
}
CDNS = {'facebook': ('fbcdn.net', 'facebook.com'), 'threads': ('fbcdn.net', 'cdninstagram.com'),
        'xiaohongshu': ('xhscdn.com',)}
REFERERS = {'facebook': 'https://www.facebook.com/', 'threads': 'https://www.threads.com/',
            'xiaohongshu': 'https://www.xiaohongshu.com/'}
AGENT = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'


def matches(host: str, domains: tuple[str, ...]) -> bool:
    return any(host == domain or host.endswith('.' + domain) for domain in domains)


def parse_https(value: object):
    if not isinstance(value, str) or len(value) > 8192 or any(ord(c) < 32 for c in value) or '\\' in value:
        raise ValueError('Invalid URL')
    url = urlsplit(value)
    if url.scheme != 'https' or not url.hostname or url.username is not None or url.password is not None or url.port not in (None, 443):
        raise ValueError('HTTPS URL required')
    return url


def source_platform(value: object) -> str:
    url = parse_https(value)
    platform = next((key for key, domains in SOURCES.items() if matches(url.hostname.lower(), domains)), None)
    if platform is None or url.path in ('', '/') or url.fragment:
        raise ValueError('Unsupported source')
    if platform == 'youtube':
        if url.hostname in ('youtu.be', 'www.youtu.be'):
            identity = url.path.strip('/')
        elif url.path == '/watch':
            identity = parse_qs(url.query).get('v', [''])[0]
        else:
            match = re.fullmatch(r'/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})/?', url.path)
            identity = match.group(1) if match else ''
        if not re.fullmatch(r'[A-Za-z0-9_-]{11}', identity):
            raise ValueError('Single YouTube video required')
    if platform == 'instagram' and not re.fullmatch(r'/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+/?', url.path):
        raise ValueError('Instagram post required')
    return platform


def validate_media(value: str, platform: str, *, resolve: bool = True) -> str:
    url = parse_https(value)
    if not matches(url.hostname.lower(), CDNS.get(platform, ())):
        raise ValueError('Unsupported media host')
    if url.path.lower().endswith(('.m3u8', '.mpd')):
        raise ValueError('Progressive media required')
    if resolve:
        addresses = socket.getaddrinfo(url.hostname, 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
            raise ValueError('Public media host required')
    return value


def selector(value: object, *, required: bool = True) -> str | None:
    if value is None and not required:
        return None
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+(?:\+[A-Za-z0-9_.-]+)?', value) or len(value) > 200:
        raise ValueError('Exact format selector required')
    if any(part.lower() in ('best', 'worst', 'bestvideo', 'bestaudio', 'worstvideo', 'worstaudio', 'b', 'w', 'bv', 'ba', 'wv', 'wa', 'all', 'mergeall') for part in value.split('+')):
        raise ValueError('Automatic selectors are not accepted')
    return value


def engine_args(url: str) -> list[str]:
    source_platform(url)
    return [sys.executable, '-m', 'yt_dlp', '--ignore-config', '--no-playlist', '--playlist-items', '1',
            '--no-cache-dir', '--no-progress', '--no-warnings', '--socket-timeout', '30',
            '--retries', '1', '--fragment-retries', '1', '--max-filesize', str(MAX_MEDIA)]


def inspect_video(url: str) -> bytes:
    # A subprocess bounds lifetime and keeps engine state isolated between requests.
    result = subprocess.run(engine_args(url) + ['--dump-single-json', '--skip-download', '--', url],
                            capture_output=True, timeout=90, check=True)
    if len(result.stdout) > MAX_METADATA:
        raise ValueError('Metadata exceeds limit')
    json.loads(result.stdout)
    return result.stdout


class MediaRedirect(HTTPRedirectHandler):
    def __init__(self, platform: str):
        self.platform = platform

    def redirect_request(self, request, response, code, message, headers, newurl):
        validate_media(newurl, self.platform)
        return super().redirect_request(request, response, code, message, headers, newurl)


def download_direct(candidates: list[str], platform: str, output: Path) -> None:
    opener = build_opener(MediaRedirect(platform))
    for index, url in enumerate(candidates):
        validate_media(url, platform)
        try:
            request = Request(url, headers={'User-Agent': AGENT, 'Referer': REFERERS[platform], 'Accept-Encoding': 'identity'})
            with opener.open(request, timeout=30) as response, output.open('wb') as file:
                mime = response.headers.get_content_type()
                if mime.startswith('text/') or 'json' in mime:
                    raise ValueError('Source did not return media')
                total = 0
                prefix = bytearray()
                while chunk := response.read(64 * 1024):
                    total += len(chunk)
                    if total > MAX_MEDIA:
                        raise ValueError('Media exceeds limit')
                    if len(prefix) < 16:
                        prefix.extend(chunk[:16 - len(prefix)])
                    file.write(chunk)
                if prefix[4:8] != b'ftyp':
                    raise ValueError('Invalid MP4')
                length = response.headers.get('Content-Length')
                if length is not None and int(length) != total:
                    raise ValueError('Incomplete media')
            return
        except (OSError, HTTPError):
            output.unlink(missing_ok=True)
            # Never retry login/rate-limit responses; only explicit replicas are eligible.
            error = sys.exception()
            if isinstance(error, HTTPError) and error.code in (401, 429):
                raise
            if index == len(candidates) - 1:
                raise


def download_video(payload: dict, directory: Path) -> Path:
    url = payload.get('url')
    platform = source_platform(url)
    mode = payload.get('format')
    if mode not in ('video', 'mp3'):
        raise ValueError('Invalid output format')
    candidates = payload.get('directCandidates')
    if candidates is not None:
        if mode != 'mp3' or platform not in CDNS or not isinstance(candidates, list) or not 1 <= len(candidates) <= 8:
            raise ValueError('Invalid direct media request')
        for candidate in candidates:
            validate_media(candidate, platform, resolve=False)
        source = directory / 'source.mp4'
        download_direct(candidates, platform, source)
        output = directory / 'media.mp3'
        # Restrict demuxer protocols so a supplied media container cannot fetch URLs/files.
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-protocol_whitelist', 'file,pipe',
                        '-i', str(source), '-map', '0:a:0', '-vn', '-c:a', 'libmp3lame', '-b:a', '192k',
                        '-fs', str(MAX_MEDIA + 1), str(output)], capture_output=True, timeout=600, check=True)
    else:
        if platform not in ('youtube', 'instagram'):
            raise ValueError('Direct sources require selected media')
        chosen = selector(payload.get('selector'))
        args = engine_args(url) + ['--no-continue', '-f', chosen, '-o', str(directory / 'media.%(ext)s')]
        if mode == 'mp3':
            args += ['--extract-audio', '--audio-format', 'mp3', '--audio-quality', '192K']
        subprocess.run(args + ['--', url], capture_output=True, timeout=600, check=True)
        outputs = [p for p in directory.glob('media.*') if p.suffix in ('.mp4', '.webm', '.mkv', '.mp3')]
        if len(outputs) != 1:
            raise ValueError('No complete output')
        output = outputs[0]
    if not output.is_file() or not 0 < output.stat().st_size <= MAX_MEDIA:
        raise ValueError('Invalid output size')
    if mode == 'mp3' and output.suffix != '.mp3':
        raise ValueError('MP3 conversion failed')
    return output


class Handler(BaseHTTPRequestHandler):
    # Do not log URLs, access tokens, engine stderr, or media signatures.
    def log_message(self, *_):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def reply(self, status: int, body: bytes, mime: str = 'application/json'):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(200 if self.path == '/health' else 404, b'{"service":"v-get"}')

    def do_POST(self):
        token = os.environ.get('VGET_API_TOKEN', '')
        supplied = self.headers.get('Authorization', '')
        if len(token) < 32 or not hmac.compare_digest(supplied.encode(), ('Bearer ' + token).encode()):
            self.reply(401, b'{"error":"Unauthorized"}')
            return
        if self.path not in ('/v1/inspect', '/v1/download'):
            self.reply(404, b'{"error":"Not found"}')
            return
        if not SLOTS.acquire(blocking=False):
            self.reply(429, b'{"error":"Busy"}')
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= MAX_BODY:
                raise ValueError('Invalid body length')
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict):
                raise ValueError('Invalid payload')
            platform = source_platform(payload.get('url'))
            if self.path == '/v1/inspect':
                if platform not in ('youtube', 'instagram'):
                    raise ValueError('Use on-device inspection for this source')
                self.reply(200, inspect_video(payload['url']))
            else:
                with tempfile.TemporaryDirectory(prefix='vget-') as temporary:
                    output = download_video(payload, Path(temporary))
                    mime = {'.mp3': 'audio/mpeg', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska'}[output.suffix]
                    self.send_response(200)
                    self.send_header('Content-Type', mime)
                    self.send_header('Content-Length', str(output.stat().st_size))
                    self.send_header('X-VGet-Extension', output.suffix[1:])
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    with output.open('rb') as file:
                        shutil.copyfileobj(file, self.wfile, length=64 * 1024)
        except (ValueError, TypeError, KeyError):
            self.reply(400, b'{"error":"Invalid request or media"}')
        except subprocess.TimeoutExpired:
            self.reply(504, b'{"error":"Engine timeout"}')
        except subprocess.CalledProcessError:
            self.reply(422, b'{"error":"Source unavailable or selected format failed"}')
        except (BrokenPipeError, ConnectionResetError):
            pass
        except OSError:
            self.reply(502, b'{"error":"Network or storage failure"}')
        finally:
            SLOTS.release()


if __name__ == '__main__':
    if len(os.environ.get('VGET_API_TOKEN', '')) < 32:
        raise SystemExit('Set VGET_API_TOKEN to at least 32 characters before starting.')
    ThreadingHTTPServer((os.environ.get('VGET_HOST', '127.0.0.1'), int(os.environ.get('PORT', '8080'))), Handler).serve_forever()
