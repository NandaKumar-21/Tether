# Tether

**Your phone is a private AI runtime for your laptop.**

Tether runs an OpenAI-compatible HTTP server on an Android phone. Point any tool that
speaks the OpenAI API at it — curl, the `openai` SDK, Continue, Cursor — and a local
Gemma 3 model answers on the device's GPU.

No cloud. No API key. No network at all: the whole thing is demonstrated in airplane mode.

```
laptop  ──USB──>  phone :8080/v1/chat/completions  ──>  Gemma 3 1B int4 on the GPU
```

## Why

Every "local AI" workflow still ends with your code, your logs and your customer data
leaving the machine. The hardware to avoid that is already in your pocket: this phone has
12 GB of RAM and a Snapdragon 8 Elite. Tether turns it into an inference endpoint your
existing tools can use without being modified, and the phone doubles as the input surface —
camera OCR and offline speech feed the same endpoint.

## Demo

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo.ps1
```

Takes the phone offline, proves there is no route and no DNS, cold starts the app,
waits for the model, and makes one request. About 40 seconds end to end.

If a reply ever comes back empty:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fix.ps1
```

## Point a real tool at it

```powershell
adb forward tcp:8080 tcp:8080
```

```python
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="unused")
print(client.chat.completions.create(
    model="gemma-3-1b-it-int4",
    messages=[{"role": "user", "content": "What is a race condition?"}],
).choices[0].message.content)
```

That exact script is [scripts/client_demo.py](scripts/client_demo.py) — verified against the
phone in airplane mode with the stock `openai` package and no phone-side changes.

Or plain curl (PowerShell needs `--%`, otherwise it eats the quotes):

```powershell
curl.exe -s -X POST http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" --% -d "{\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"
```

## Endpoints

| Route | Purpose |
| --- | --- |
| `POST /v1/chat/completions` | OpenAI-shaped chat completion, real token usage |
| `GET /v1/models` | Model listing — most OpenAI clients probe this before connecting |
| `GET /health` | Model loaded, backend, requests served, tokens/sec |

## The three input surfaces

1. **Laptop** — any OpenAI-compatible tool over `adb forward` (USB) or the LAN address.
2. **Camera** — SCAN captures a frame, ML Kit reads the text entirely on-device, and it
   becomes context for the next request. The OCR models are bundled in the APK, so this
   works with no network.
3. **Voice** — MIC transcribes with `SpeechRecognizer` using `EXTRA_PREFER_OFFLINE` and
   posts the transcript to the same endpoint over loopback.

## Build

Requires JDK 17 and the Android SDK. No Android Studio needed.

```powershell
.\gradlew installDebug
```

The model is not in this repository. Push it to the device once:

```powershell
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

Get `gemma3-1b-it-int4.task` from
[litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT)
(gated — accept the Gemma licence first).

## Stack

Kotlin, XML layouts, no Compose. AGP 8.7.3 / Gradle 8.9 / Kotlin 1.9.24, minSdk 26.
NanoHTTPD in a `dataSync` foreground service so Android does not kill the server.
MediaPipe LLM Inference (`tasks-genai` 0.10.24) on the GPU backend, initialised once at
service start. ML Kit text recognition. Measured ~40 tok/s, ~16 s cold model load.

## Two things worth knowing

**MediaPipe sessions.** Generation calls `LlmInference.generateResponse` directly rather
than creating an `LlmInferenceSession` per request. Closing a session tears down state the
parent `LlmInference` still needs, which leaves the engine alive but returning empty
strings after exactly one generation. `sizeInTokens` shares the generation lock for the
same reason — it touches the same native engine and crashes the process if called
concurrently with a generation.

**Airplane mode is not proof.** On this vendor ROM `airplane_mode_on` can read `1` while
wifi is quietly still routing. `demo.ps1` disables the radios explicitly and shows
`Active default network: none` instead of trusting the flag.
