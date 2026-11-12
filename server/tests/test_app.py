import importlib.util
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json

spec = importlib.util.spec_from_file_location('engine', Path(__file__).parents[1] / 'app.py')
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)


class PolicyTests(unittest.TestCase):
    def test_rejects_foreign_hosts_credentials_ports_and_non_https(self):
        for url in ['http://youtube.com/watch?v=test', 'https://youtube.com.evil.test/watch?v=test',
                    'https://user:secret@youtube.com/watch?v=test', 'https://youtube.com:444/watch?v=test',
                    'https://127.0.0.1/a', 'file:///etc/passwd', 'https://youtube.com\\@evil.test/a']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                app.source_platform(url)

    def test_exact_selector_cannot_be_expression_or_option(self):
        self.assertEqual('137+140', app.selector('137+140'))
        for value in [None, '', 'best', '137/22', '-o hacked', '137;id', 'best[height=720]']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                app.selector(value)

    def test_direct_cdn_matches_source_and_preserves_signed_query(self):
        value = 'https://video.fbcdn.net/a.mp4?sig=A%2FB+C'
        self.assertEqual(value, app.validate_media(value, 'threads', resolve=False))
        for value in ['https://fbcdn.net.evil.test/a.mp4', 'https://xhscdn.com/a.mp4', 'https://fbcdn.net/a.m3u8']:
            with self.assertRaises(ValueError):
                app.validate_media(value, 'threads', resolve=False)

    def test_private_dns_cannot_be_downloaded(self):
        with patch.object(app.socket, 'getaddrinfo', return_value=[(2, 1, 6, '', ('127.0.0.1', 443))]):
            with self.assertRaises(ValueError):
                app.validate_media('https://video.fbcdn.net/a.mp4', 'facebook')

    def test_direct_mp3_uses_exact_selected_replica_and_real_conversion(self):
        with tempfile.TemporaryDirectory() as temporary:
            def convert(args, **_):
                self.assertIn('libmp3lame', args)
                self.assertIn('192k', args)
                Path(args[-1]).write_bytes(b'ID3audio')
            with patch.object(app, 'download_direct') as direct, patch.object(app.subprocess, 'run', side_effect=convert):
                result = app.download_video({'url': 'https://threads.com/@u/post/abc', 'format': 'mp3',
                    'directCandidates': ['https://video.fbcdn.net/selected.mp4']}, Path(temporary))
                self.assertEqual('.mp3', result.suffix)
                self.assertEqual(['https://video.fbcdn.net/selected.mp4'], direct.call_args.args[0])

    def test_video_download_passes_exact_format_without_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            def run(args, **_):
                self.assertEqual('137+140', args[args.index('-f') + 1])
                self.assertEqual(['--', 'https://youtube.com/watch?v=abcdefghijk'], args[-2:])
                Path(temporary, 'media.mp4').write_bytes(b'video')
            with patch.object(app.subprocess, 'run', side_effect=run):
                result = app.download_video({'url': 'https://youtube.com/watch?v=abcdefghijk',
                    'format': 'video', 'selector': '137+140'}, Path(temporary))
                self.assertEqual('media.mp4', result.name)


class AuthenticationTests(unittest.TestCase):
    def test_unauthenticated_request_never_starts_engine(self):
        server = app.ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with patch.dict(os.environ, {'VGET_API_TOKEN': 'x' * 32}), patch.object(app, 'inspect_video') as inspect:
                request = Request(f'http://127.0.0.1:{server.server_port}/v1/inspect', data=b'{}', method='POST')
                with self.assertRaises(HTTPError) as result:
                    urlopen(request)
                self.assertEqual(401, result.exception.code)
                inspect.assert_not_called()
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__':
    unittest.main()
