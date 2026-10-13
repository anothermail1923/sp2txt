#!/usr/bin/env python3
"""Developer test client: streams a WAV file to /ws/speech in real time and
prints the transcript events, simulating what a mobile app does with its mic.

The WAV must be mono, 16-bit PCM, 16 kHz, e.g. convert with:
    ffmpeg -i input.m4a -ac 1 -ar 16000 -sample_fmt s16 out.wav

Usage:
    pip install websockets
    python3 scripts/stream_wav.py out.wav [ws://localhost:8080/ws/speech] [--token TOKEN]
"""
import argparse
import asyncio
import json
import wave

import websockets

CHUNK_MS = 100


async def stream(path, url, token):
    headers = {"Authorization": f"Bearer {token}"} if token else None
    async with websockets.connect(url, additional_headers=headers) as ws:

        async def receive():
            async for raw in ws:
                event = json.loads(raw)
                print(f"{event['type']:>8}: {event.get('text', '')}")
                if event["type"] == "stopped":
                    return

        receiver = asyncio.create_task(receive())

        with wave.open(path, "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise SystemExit("WAV must be mono, 16-bit, 16 kHz")
            frames = wav.getframerate() * CHUNK_MS // 1000
            while chunk := wav.readframes(frames):
                await ws.send(chunk)
                await asyncio.sleep(CHUNK_MS / 1000)

        await ws.send("STOP")
        await receiver


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("wav")
    parser.add_argument("url", nargs="?", default="ws://localhost:8080/ws/speech")
    parser.add_argument("--token")
    args = parser.parse_args()
    asyncio.run(stream(args.wav, args.url, args.token))
