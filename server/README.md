# Optional V-Get engine

This small authenticated service supplies the Android-only engine capabilities to the iOS app. It runs yt-dlp and FFmpeg on a host you control. Public Facebook/Threads/Xiaohongshu video inspection and direct downloads remain on the phone; their MP3 conversion uses the exact selected media URL.

## Run locally

Use Python 3.11+, FFmpeg (with libmp3lame), and Deno for yt-dlp's JavaScript challenges. `requirements.txt` pins yt-dlp; review and update it when upstream platform changes require a newer version.

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
# Set VGET_API_TOKEN to a private random token of at least 32 characters.
# Keep it out of source control, shell transcripts and public logs.
python app.py
```

The default bind address is 127.0.0.1:8080. Put an HTTPS reverse proxy in front of it, with a 16 KiB request-body limit, rate limits, an allowlist/VPN where practical, and at least a 600-second upstream timeout. The iOS app accepts HTTPS endpoints on port 443. Leave request bodies and Authorization headers out of proxy access logs. Do not use a public untrusted extraction endpoint.

Docker is also supported:

```sh
docker build -t vget-engine server
# Supply VGET_API_TOKEN through your deployment's secret store/environment.
docker run --rm -p 127.0.0.1:8080:8080 --env VGET_API_TOKEN \
  --memory=1g --cpus=2 --tmpfs /tmp:rw,size=2g vget-engine
```

Container storage is temporary. Give the deployment an outbound-network policy denying private/internal addresses; yt-dlp follows platform-controlled URLs while extracting. The service is intended for your own authenticated devices, not as an unrestricted public multi-tenant download API. No cookies or account login are imported.

## API

Requests use `Authorization: Bearer <your-token>`. Redirects from the service are rejected by the client to avoid forwarding this token.

- `GET /health`: health response, no source information.
- `POST /v1/inspect`: `{"url":"https://www.youtube.com/watch?v=abcdefghijk"}`. Returns yt-dlp JSON. YouTube/Instagram only; URLs are validated before invoking the engine.
- `POST /v1/download`: `{"url":"…","format":"video","selector":"137+140"}`. Downloads exactly the selected format IDs and returns the completed file; no fallback quality selection.
- MP3: use `"format":"mp3"` and the selected audio format ID as `selector`. For native page sources, send `directCandidates` containing only the selected quality's explicit CDN URLs. The server restricts them to that source's media domains and uses libmp3lame at 192 kbps.

Responses include Content-Length, Content-Type and X-VGet-Extension. The phone picks a collision-safe filename using the page title. Limits: two simultaneous requests, 1 GiB media, 8 MiB metadata, 90 seconds for inspection and 600 seconds for engine/conversion work. The HTTP response begins once the output is ready, so preparation has indeterminate progress. Cancellation stops the phone's transfer; server-side work is bounded by timeout and temporary files are removed afterward.

The server never returns raw yt-dlp/FFmpeg stderr or logs source URLs/tokens. HTTP 401 indicates authentication, 429 means busy, 422 indicates engine/format failure, and 504 indicates timeout. A source's own login/rate limit may be reported as a generic engine failure.

## Tests

```sh
python -m unittest discover -s server/tests -v
```

Tests use mocks for engine calls; a real end-to-end test with your HTTPS deployment and public videos is still required.
