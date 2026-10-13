# sp2txt — live speech-to-text backend

A real-time speech-to-text service built with **Spring Boot + WebSocket + Vosk**.
A mobile app (Android, iOS, or anything that can stream PCM over a WebSocket)
captures microphone audio, streams raw PCM chunks to this server, and receives
**partial** transcripts while the user speaks and **final** transcripts when a
phrase is recognized.

Everything runs locally: no external transcription API, no cloud speech
recognition, no audio-file uploads. Audio is processed in memory as it arrives
and is never stored.

```
┌──────────────┐  binary PCM chunks   ┌──────────────────────────┐
│  Mobile app  │ ───────────────────▶ │  Spring Boot             │
│  mic capture │                      │  /ws/speech              │
│  transcript  │ ◀─────────────────── │  per-session Recognizer  │
│  UI          │  JSON events         │  shared Vosk Model       │
└──────────────┘                      └──────────────────────────┘
```

| Component   | Responsibility                                                        |
|-------------|-----------------------------------------------------------------------|
| Mobile app  | Microphone permission, audio capture, streaming, transcript UI        |
| Spring Boot | WebSocket connections, sessions, limits, auth, response events        |
| Vosk        | Local speech recognition, partial and final results                   |
| REST        | `GET /api/health`, `GET /api/status` (model, audio format, sessions)  |

## Requirements

- Java 17+
- Maven 3.9+
- A Vosk model (default: `vosk-model-small-en-us-0.15`, ~40 MB)

## Quick start

```bash
# 1. Download and extract the model into ./models
./scripts/download-model.sh
#    (or download https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
#     and extract it so models/vosk-model-small-en-us-0.15/{am,conf,graph,ivector} exist)

# 2. Run
mvn spring-boot:run

# 3. Check
curl http://localhost:8080/api/status
```

The model is loaded once at startup and shared by all sessions; startup fails
with a clear message if the model directory is missing.

To build a runnable jar: `mvn package` → `java -jar target/live-speech-0.0.1-SNAPSHOT.jar`
(run it from the project root, or set `SPEECH_MODEL_PATH` to an absolute path).

## WebSocket API

**Endpoint:** `ws://<host>:8080/ws/speech` (use `wss://` when TLS is enabled)

### Lifecycle

1. **Connect.** The client opens the WebSocket. If an auth token is configured,
   send `Authorization: Bearer <token>` on the handshake (or `?token=<token>`).
2. **Ready.** The server creates a recognizer for this session and sends a
   `ready` event. Start streaming once it arrives.
3. **Stream audio.** Send **binary** messages containing raw audio:
   - mono, signed 16-bit little-endian PCM (`pcm_s16le`), **16 kHz**
   - no WAV header; any chunk size up to 64 KB, even byte length
   - 100–250 ms per chunk (3 200–8 000 bytes) is a good balance of latency and overhead
4. **Receive transcripts.** The server sends `partial` and `final` events.
5. **Stop.** Send the text message `STOP` (or `{"type":"stop"}`). The server
   flushes remaining audio, sends any last `final`, releases the recognizer and
   sends `stopped`. Close the socket, or send `START` (`{"type":"start"}`) to
   begin a new recognition on the same connection.

Closing the socket without `STOP` is also safe: the recognizer is released, but
the trailing words that were not yet finalized are discarded.

### Server → client events

All events are JSON text messages with a `type` and `text`:

| type      | Meaning                                                             |
|-----------|---------------------------------------------------------------------|
| `ready`   | Recognizer created; also carries `sampleRate` and `model`           |
| `partial` | Provisional text of the phrase in progress; replaces the previous partial. Empty text clears it |
| `final`   | A completed phrase; append it to the transcript and clear the partial |
| `stopped` | Recognition stopped after `STOP`                                    |
| `error`   | Something was wrong (bad chunk, unknown command, server busy); `text` explains |

```json
{"type":"ready","text":"","sampleRate":16000,"model":"vosk-model-small-en-us-0.15"}
{"type":"partial","text":"hello how"}
{"type":"partial","text":"hello how are you"}
{"type":"final","text":"hello how are you"}
{"type":"stopped","text":""}
```

Partials are only sent when the text changes. The small Vosk models output
lowercase text without punctuation.

### Close codes

| Code | When                                                              |
|------|-------------------------------------------------------------------|
| 1008 | Session limit reached (an `error` event "Server busy" is sent first) |
| 1009 | Audio chunk larger than `speech.max-binary-message-bytes`         |
| 1011 | Recognizer could not be created                                   |
| 1001 | Server shutting down                                              |

A handshake with a missing or wrong token is rejected with HTTP 401.

## REST endpoints

- `GET /api/health` → `{"status":"UP"}`
- `GET /api/status` →

```json
{
  "status": "UP",
  "engine": "vosk",
  "model": "vosk-model-small-en-us-0.15",
  "endpoint": "/ws/speech",
  "audio": {"encoding": "pcm_s16le", "sampleRate": 16000, "channels": 1},
  "maxChunkBytes": 65536,
  "activeSessions": 0,
  "maxSessions": 20,
  "authRequired": false
}
```

## Configuration

Set in `src/main/resources/application.yml`, with command-line flags
(`--speech.max-sessions=50`) or environment variables:

| Property                          | Env var                  | Default                               |
|-----------------------------------|--------------------------|---------------------------------------|
| `server.port`                     | `PORT`                   | `8080`                                |
| `speech.model-path`               | `SPEECH_MODEL_PATH`      | `models/vosk-model-small-en-us-0.15`  |
| `speech.sample-rate`              |                          | `16000`                               |
| `speech.max-sessions`             | `SPEECH_MAX_SESSIONS`    | `20`                                  |
| `speech.max-binary-message-bytes` |                          | `65536`                               |
| `speech.idle-timeout`             |                          | `60s`                                 |
| `speech.allowed-origin-patterns`  | `SPEECH_ALLOWED_ORIGINS` | empty (same-origin browsers only)     |
| `speech.auth-token`               | `SPEECH_AUTH_TOKEN`      | empty (no auth)                       |

`allowed-origin-patterns` only affects browser clients; native mobile apps
send no `Origin` header. Each recognizer holds a few tens of MB with the small
model, so size `max-sessions` to the server's memory and CPU.

Other models from https://alphacephei.com/vosk/models work by pointing
`speech.model-path` at them (e.g. the larger `vosk-model-en-us-0.22` for better
accuracy, or `vosk-model-small-en-in-0.4` for Indian English).

## Integrating a mobile app

### Android (Kotlin, OkHttp + AudioRecord)

Requires `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`
(requested at runtime) and `INTERNET`.

```kotlin
val sampleRate = 16_000
val chunkBytes = sampleRate * 2 / 10  // 100 ms

val request = Request.Builder()
    .url("ws://192.168.1.20:8080/ws/speech")
    // .header("Authorization", "Bearer $token")
    .build()

val socket = OkHttpClient().newWebSocket(request, object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
        val event = JSONObject(text)
        when (event.getString("type")) {
            "ready"   -> startRecording(webSocket)
            "partial" -> showPartial(event.getString("text"))
            "final"   -> appendFinal(event.getString("text"))
            "stopped" -> webSocket.close(1000, null)
            "error"   -> showError(event.getString("text"))
        }
    }
})

fun startRecording(ws: WebSocket) = thread {
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(chunkBytes, AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
    val buffer = ByteArray(chunkBytes)
    recorder.startRecording()
    while (recording) {
        val n = recorder.read(buffer, 0, buffer.size)
        if (n > 0) ws.send(buffer.toByteString(0, n))
    }
    recorder.stop(); recorder.release()
    ws.send("STOP")
}
```

`AudioRecord` with `ENCODING_PCM_16BIT` already produces little-endian 16 kHz
mono PCM, so no conversion is needed.

### iOS (Swift, AVAudioEngine + URLSessionWebSocketTask)

Add `NSMicrophoneUsageDescription` to `Info.plist`. The hardware input is
usually 44.1/48 kHz float, so convert to 16 kHz Int16 with `AVAudioConverter`:

```swift
let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000,
                           channels: 1, interleaved: true)!
let input = engine.inputNode.outputFormat(forBus: 0)
let converter = AVAudioConverter(from: input, to: target)!

engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: input) { buffer, _ in
    let out = AVAudioPCMBuffer(pcmFormat: target,
        frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * 16_000 / input.sampleRate) + 1)!
    var fed = false
    converter.convert(to: out, error: nil) { _, status in
        if fed { status.pointee = .noDataNow; return nil }
        fed = true; status.pointee = .haveData; return buffer
    }
    let data = Data(bytes: out.int16ChannelData![0], count: Int(out.frameLength) * 2)
    task.send(.data(data)) { _ in }
}
// Start the engine after the "ready" event; on stop: remove the tap,
// stop the engine and send .string("STOP").
```

### Testing without a phone

`scripts/stream_wav.py` streams a 16 kHz mono WAV in real time and prints the events:

```bash
pip install websockets
ffmpeg -i sample.m4a -ac 1 -ar 16000 -sample_fmt s16 sample.wav
python3 scripts/stream_wav.py sample.wav ws://localhost:8080/ws/speech
```

## Running on a LAN / HTTPS

The phone and the server must reach each other on the network (same Wi-Fi,
server firewall allowing port 8080). Native apps can use plain `ws://` on a
LAN during development (Android needs `android:usesCleartextTraffic="true"` or a
network security config; iOS needs an App Transport Security exception).

For anything beyond local development, use TLS (`wss://`). Either terminate
TLS at a reverse proxy, or enable it in Spring Boot with a certificate the
phone trusts:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: file:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
```

## Production notes

- **Security:** use `wss://`, set `speech.auth-token` (or put real
  authentication in front of the service), and restrict `allowed-origin-patterns`.
- **Capacity:** `speech.max-sessions` caps concurrent recognizers; audio chunk size
  and idle time are limited by `max-binary-message-bytes` and `idle-timeout`.
- **Concurrency:** each session's recognizer is only used while holding that
  session's lock, so audio, `STOP`, disconnects and shutdown never touch a
  recognizer concurrently or after it is closed. On shutdown all sessions are
  closed and recognizers released before the shared model is freed.
- **Accuracy:** this uses a pretrained model as-is; test it against your users'
  speech, microphones and noise conditions, and switch models if needed.

## Testing

For a detailed step-by-step guide on testing with a browser microphone, Python script, or unit tests, see **[TESTING.md](TESTING.md)**.

Quick automated tests (no Vosk model required):
```bash
mvn test
```

## Project layout

```
├── pom.xml
├── TESTING.md                       # Complete testing & troubleshooting guide
├── testing/
│   └── test.html                    # Browser live microphone test client
├── models/                          # Vosk models (git-ignored)
├── scripts/
│   ├── download-model.sh
│   └── stream_wav.py                # Python test client
└── src/
    ├── main/java/com/example/speech/
    │   ├── SpeechApplication.java
    │   ├── config/                  # SpeechConfig, SpeechProperties, WebSocketConfig
    │   ├── controller/              # StatusController, SpeechWebSocketHandler
    │   ├── service/                 # SpeechRecognitionService, SessionService, etc.
    │   ├── repository/              # SessionRepository
    │   ├── model/                   # SpeechSession
    │   ├── dto/                     # StatusResponse, AudioFormat
    │   ├── interceptor/             # TokenHandshakeInterceptor
    │   └── recognizer/              # Vosk model & recognizer wrappers
    └── test/java/com/example/speech/  # WebSocket integration tests (fake engine)
```

