# Testing Guide — sp2txt

This document explains how to test the application in different ways: automated tests, REST status checks, interactive microphone tests in a web browser, and audio file streaming with Python.

---

## 1. Automated Tests (Unit / Integration)

The test suite runs against an in-memory fake speech recognizer, so **no Vosk model download is required**.

From the project root:

```powershell
mvn test
```

---

## 2. Starting the Backend Server

Before running interactive tests, make sure the local Vosk model is downloaded:

### Download Model (if not already downloaded)

Run in PowerShell:
```powershell
cd "path/to/sp2txt"
curl.exe -fL -o "$env:TEMP\vosk.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
Expand-Archive "$env:TEMP\vosk.zip" -DestinationPath models -Force
```

### Start the Spring Boot App

> **Note on Browser Testing:** Browsers send an `Origin` header during WebSocket handshakes. If you plan to test via the browser test client on port `8090`, allow that origin when starting the application:

**Option A — PowerShell:**
```powershell
cd "path/to/sp2txt"
$env:SPEECH_ALLOWED_ORIGINS="http://localhost:8090"
mvn spring-boot:run
```

**Option B — IntelliJ IDEA:**
1. Open **Run** → **Edit Configurations...** → **SpeechApplication**.
2. Under **Environment variables**, add:
   ```text
   SPEECH_ALLOWED_ORIGINS=http://localhost:8090
   ```
3. Click **Apply** and start the application.

---

## 3. Verify Server Health & REST Status

With the server running on port 8080, run:

```powershell
curl.exe http://localhost:8080/api/health
```
**Expected response:**
```json
{"status":"UP"}
```

Check the engine status:
```powershell
curl.exe http://localhost:8080/api/status
```
**Expected response:**
```json
{
  "status": "UP",
  "engine": "vosk",
  "model": "vosk-model-small-en-us-0.15",
  "endpoint": "/ws/speech",
  "audio": { "encoding": "pcm_s16le", "sampleRate": 16000, "channels": 1 },
  "maxChunkBytes": 65536,
  "activeSessions": 0,
  "maxSessions": 20,
  "authRequired": false
}
```

---

## 4. Test via Web Browser (Microphone Live Streaming)

A test client is provided in the `testing/` folder: `testing/test.html`.

### Step 1: Serve `test.html`
In a separate terminal window:
```powershell
cd "path/to/sp2txt/testing"
python -m http.server 8090
```

### Step 2: Open in Browser
1. In Chrome / Edge / Firefox, navigate to:
   ```text
   http://localhost:8090/test.html
   ```
   *(Ensure you use `localhost:8090`, not `127.0.0.1:8090`, so the Origin header matches).*
2. Click **Start Recording**.
3. Allow microphone access when prompted by the browser.
4. Speak into your microphone.
5. Watch the transcript stream in real time:
   - `ready`: session established
   - `partial: ...`: interim transcription as you speak
   - `final: ...`: finalized sentence
6. Click **Stop** when finished.

---

## 5. Test via Python WAV Streamer (Simulated Mic)

If you don't have a microphone or want deterministic input testing:

### Step 1: Install dependencies & prepare audio
```powershell
pip install websockets
```

Convert any audio file to mono 16 kHz 16-bit PCM WAV:
```powershell
ffmpeg -i sample.mp3 -ac 1 -ar 16000 -sample_fmt s16 sample.wav
```

### Step 2: Stream the audio file
```powershell
python scripts/stream_wav.py sample.wav ws://localhost:8080/ws/speech
```

If an auth token is configured:
```powershell
python scripts/stream_wav.py sample.wav ws://localhost:8080/ws/speech --token YOUR_TOKEN
```

---

## 6. Common Issues & Troubleshooting

| Error | Cause | Solution |
|---|---|---|
| **`HTTP 403` on `/ws/speech` (or `WebSocket closed: 1006`)** | The browser's origin (`http://localhost:8090`) is not permitted by the server. | Restart the Spring Boot app with `SPEECH_ALLOWED_ORIGINS=http://localhost:8090`. |
| **`HTTP 404 File not found` on python http server** | The python server was started in a different folder, or the URL path is misspelled. | `cd` into the folder containing `test.html` before running `python -m http.server 8090`, then open `http://localhost:8090/test.html`. |
| **`HTTP 401 Unauthorized` on `/ws/speech`** | `SPEECH_AUTH_TOKEN` is configured on the backend. | Add `?token=YOUR_TOKEN` to the WebSocket URL in `test.html`. |
| **`No plugin found for prefix 'spring-boot'`** | `mvn spring-boot:run` was executed inside a subfolder where `pom.xml` does not exist. | Navigate to the project root (`path/to/sp2txt`) before running Maven commands. |
| **`Vosk model not found` at startup** | The model files are not in `models/vosk-model-small-en-us-0.15`. | Run the model download command from Section 2 or verify the directory exists. |




