"""
The point of this file: not one line of it knows it is talking to a phone.

It is the stock `openai` package, pointed at localhost. Tether is
OpenAI-compatible, so a real tool needs zero changes and zero phone-side code.

Streams the reply so the tokens appear as they arrive.
If anything looks wrong on stage, run client_demo_safe.py instead.
"""

from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

print("models on this endpoint:", [m.id for m in client.models.list().data])

stream = client.chat.completions.create(
    model="gemma-3-1b-it-int4",
    messages=[{"role": "user", "content": "What is a race condition? Two sentences."}],
    stream=True,
)

print("\nanswer: ", end="", flush=True)
chunks = 0
for chunk in stream:
    delta = chunk.choices[0].delta.content
    if delta:
        print(delta, end="", flush=True)
        chunks += 1

print(f"\n\nstreamed {chunks} chunks from the phone")
