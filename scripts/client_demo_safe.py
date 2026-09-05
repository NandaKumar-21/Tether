"""
STAGE FALLBACK. Non-streaming. Use this if the streaming version looks wrong.

Identical to the version verified all afternoon: one request, one response,
no SSE involved.

The point of this file: not one line of it knows it is talking to a phone.
It is the stock `openai` package, pointed at localhost.
"""

from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

print("models on this endpoint:", [m.id for m in client.models.list().data])

resp = client.chat.completions.create(
    model="gemma-3-1b-it-int4",
    messages=[{"role": "user", "content": "What is a race condition? Two sentences."}],
)

print("\nanswer:", resp.choices[0].message.content)
print("\nusage:", resp.usage.prompt_tokens, "prompt +",
      resp.usage.completion_tokens, "completion tokens")
