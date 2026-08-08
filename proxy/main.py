"""lockscreen-translate proxy: one Cloud Function `translate`.

Holds the everyday EN -> Cantonese+Mandarin system prompt, calls a config-selectable Vertex
AI Model Garden model (Claude / Gemini / MaaS open-weight), does words.hk web grounding where
the provider supports it, and streams the result back as SSE. This is what the phone app calls.

Trimmed from cloud-claude/functions/chat/main.py: no Firebase, no images, no history — single
turn, translation only.

Request (POST JSON):
  { "input": "grab a bite", "mode": "everyday",
    "render": "spans"|"plain", "model": "<optional friendly id>", "stream": true }
Response: SSE frames  data: {"type":"chunk","text":...}
                       data: {"type":"done","content":...,"usage":...,"model":...,"citations":[...]}
                       data: {"type":"error","error":...}
Env: LT_PROJECT, LT_MODEL (default friendly id), LT_SHARED_TOKEN (optional bearer),
     LT_GROUNDING = auto|on|off (default auto = on iff the model is grounding-capable).
Health: GET ?health=1
"""
import os
import json
import time
import pathlib
import functions_framework
from flask import Response
from flask_cors import cross_origin

PROJECT = os.environ.get("LT_PROJECT", "wz-cloud-claude")
DEFAULT_MODEL = os.environ.get("LT_MODEL", "claude-sonnet-4-6")
SHARED_TOKEN = os.environ.get("LT_SHARED_TOKEN", "")
GROUNDING_MODE = os.environ.get("LT_GROUNDING", "auto")  # auto|on|off
MAX_TOKENS = int(os.environ.get("LT_MAX_TOKENS", "2048"))

_PROMPTS = pathlib.Path(__file__).parent / "prompts"
SPAN_PROMPT = (_PROMPTS / "en-to-pu-yue-everyday.md").read_text()
PLAIN_PROMPT = (_PROMPTS / "en-to-pu-yue-everyday-plain.md").read_text()
USER_TMPL = "Translate to colloquial Cantonese and Mandarin:\n\n{text}"

# Friendly id -> Vertex spec. IDs verified 2026-07-22 in wz-cloud-claude.
MODELS = {
    "claude-sonnet-4-6": {"provider": "anthropic", "vertex_id": "claude-sonnet-4-6", "region": "global", "grounding": True},
    "claude-opus-4-8":   {"provider": "anthropic", "vertex_id": "claude-opus-4-8",   "region": "global", "grounding": True},
    "gemini-3.6-flash":       {"provider": "gemini", "vertex_id": "gemini-3.6-flash",       "region": "global", "grounding": True},
    "gemini-3.5-flash-lite":  {"provider": "gemini", "vertex_id": "gemini-3.5-flash-lite",  "region": "global", "grounding": True},
    "grok-4.1-fast":  {"provider": "maas", "publisher": "xai",         "vertex_id": "grok-4.1-fast-non-reasoning",        "region": "global", "grounding": False},
    "grok-4.20":      {"provider": "maas", "publisher": "xai",         "vertex_id": "grok-4.20-non-reasoning",            "region": "global", "grounding": False},
    "qwen3-235b":     {"provider": "maas", "publisher": "qwen",        "vertex_id": "qwen3-235b-a22b-instruct-2507-maas", "region": "global", "grounding": False},
    "kimi-k2-thinking": {"provider": "maas", "publisher": "moonshotai", "vertex_id": "kimi-k2-thinking-maas",            "region": "global", "grounding": False},
    "deepseek-v3.2":  {"provider": "maas", "publisher": "deepseek-ai", "vertex_id": "deepseek-v3.2-maas",                "region": "global", "grounding": False},
}

_clients = {}
_token = {"t": None, "exp": 0.0}


def _anthropic(region):
    from anthropic import AnthropicVertex
    _clients.setdefault("a", {})
    if region not in _clients["a"]:
        _clients["a"][region] = AnthropicVertex(region=region, project_id=PROJECT)
    return _clients["a"][region]


def _genai(location):
    from google import genai
    _clients.setdefault("g", {})
    if location not in _clients["g"]:
        _clients["g"][location] = genai.Client(vertexai=True, project=PROJECT, location=location)
    return _clients["g"][location]


def _access_token():
    import google.auth
    from google.auth.transport.requests import Request
    now = time.time()
    if _token["t"] and now < _token["exp"]:
        return _token["t"]
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    creds.refresh(Request())
    _token["t"] = creds.token
    _token["exp"] = now + 50 * 60
    return creds.token


def _maas(region):
    import openai
    host = "aiplatform.googleapis.com" if region == "global" else f"{region}-aiplatform.googleapis.com"
    base = f"https://{host}/v1/projects/{PROJECT}/locations/{region}/endpoints/openapi"
    return openai.OpenAI(base_url=base, api_key=_access_token())


def _web_context(text):
    """Forced Gemini google_search fetch (thinking OFF) -> current HK usage to inject. ~5-6s.
    Fresh client (thread-safe). Hosted search otherwise barely fires; the hard instruction +
    google_search tool makes it search reliably. Returns '' on failure (caller falls back)."""
    from google import genai
    from google.genai import types
    client = genai.Client(vertexai=True, project=PROJECT, location="global")
    cfg = types.GenerateContentConfig(
        system_instruction=("You MUST call the google_search tool to verify CURRENT Hong Kong "
                            "Cantonese usage before answering. Return the current colloquial term(s) "
                            "with a one-line gloss each. No preamble."),
        max_output_tokens=1200,
        thinking_config=types.ThinkingConfig(thinking_budget=0),
        tools=[types.Tool(google_search=types.GoogleSearch())])
    r = client.models.generate_content(model="gemini-3.6-flash", contents=f"Phrase: {text}", config=cfg)
    return getattr(r, "text", "") or ""


def _sse(obj):
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


def _stream(spec, system, user, grounding):
    """Yield SSE frames for one translation call."""
    provider = spec["provider"]
    full = []
    citations = []
    usage = {}
    print(f"STREAM start provider={provider} model={spec['vertex_id']} region={spec.get('region')} "
          f"grounding={grounding} sys_len={len(system)} user={user!r}")
    try:
        if provider == "anthropic":
            opts = dict(model=spec["vertex_id"], max_tokens=MAX_TOKENS,
                        system=[{"type": "text", "text": system}],
                        messages=[{"role": "user", "content": user}])
            if grounding:
                # Native Claude web_search, FORCED. Left to itself Claude skips searching almost
                # always; tool_choice "any" guarantees at least one search while still letting it
                # return the clean 2-line output. Needs token headroom for the tool round-trip.
                opts["tools"] = [{"type": "web_search_20250305", "name": "web_search", "max_uses": 5}]
                opts["tool_choice"] = {"type": "any"}
                opts["max_tokens"] = max(MAX_TOKENS, 8000)
            with _anthropic(spec.get("region", "global")).messages.stream(**opts) as s:
                for ev in s:
                    if getattr(ev, "type", None) == "content_block_delta" and hasattr(ev, "delta"):
                        t = getattr(ev.delta, "text", None)
                        if t:
                            full.append(t)
                            yield _sse({"type": "chunk", "text": t})
                fm = s.current_message_snapshot
                usage = {"input_tokens": fm.usage.input_tokens, "output_tokens": fm.usage.output_tokens}
                for b in fm.content:
                    if getattr(b, "type", None) == "text" and getattr(b, "citations", None):
                        for c in b.citations:
                            if getattr(c, "url", None):
                                citations.append({"url": c.url, "title": getattr(c, "title", "")})

        elif provider == "gemini":
            from google.genai import types
            cfg = dict(system_instruction=system, max_output_tokens=MAX_TOKENS)
            if grounding:
                cfg["tools"] = [types.Tool(google_search=types.GoogleSearch())]
            stream = _genai(spec.get("region", "global")).models.generate_content_stream(
                model=spec["vertex_id"], contents=user,
                config=types.GenerateContentConfig(**cfg))
            for chunk in stream:
                t = getattr(chunk, "text", None)
                if t:
                    full.append(t)
                    yield _sse({"type": "chunk", "text": t})
                if getattr(chunk, "usage_metadata", None):
                    um = chunk.usage_metadata
                    usage = {"input_tokens": getattr(um, "prompt_token_count", None),
                             "output_tokens": getattr(um, "candidates_token_count", None)}

        elif provider == "maas":
            model = f'{spec["publisher"]}/{spec["vertex_id"]}'
            stream = _maas(spec.get("region", "global")).chat.completions.create(
                model=model, stream=True, max_tokens=MAX_TOKENS,
                messages=[{"role": "system", "content": system},
                          {"role": "user", "content": user}])
            for chunk in stream:
                if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                    t = chunk.choices[0].delta.content
                    full.append(t)
                    yield _sse({"type": "chunk", "text": t})
                if getattr(chunk, "usage", None):
                    usage = {"input_tokens": chunk.usage.prompt_tokens,
                             "output_tokens": chunk.usage.completion_tokens}
        else:
            raise ValueError(f"unknown provider {provider}")

        content = "".join(full)
        done = {"type": "done", "content": content, "usage": usage,
                "model": spec["vertex_id"], "citations": citations}
        print(f"STREAM done model={spec['vertex_id']} out_len={len(content)} usage={usage} "
              f"citations={len(citations)} content={content!r}")
        yield _sse(done)
    except Exception as e:
        print(f"STREAM error model={spec.get('vertex_id')}: {type(e).__name__}: {e}")
        yield _sse({"type": "error", "error": f"{type(e).__name__}: {e}"})


@functions_framework.http
@cross_origin()
def translate(request):
    if request.method == "OPTIONS":
        return ("", 204, {"Access-Control-Allow-Origin": "*",
                          "Access-Control-Allow-Methods": "POST",
                          "Access-Control-Allow-Headers": "Content-Type, Authorization",
                          "Access-Control-Max-Age": "3600"})

    if request.method == "GET" and request.args.get("health"):
        return {"status": "ok", "default_model": DEFAULT_MODEL, "project": PROJECT}, 200

    # Optional shared-token auth
    if SHARED_TOKEN:
        auth = request.headers.get("Authorization", "")
        if auth != f"Bearer {SHARED_TOKEN}":
            return {"error": "unauthorized"}, 401

    body = request.get_json(silent=True) or {}
    print(f"REQUEST payload={json.dumps(body, ensure_ascii=False)}")
    text = (body.get("input") or "").strip()
    if not text:
        return {"error": "`input` is required"}, 400

    model_id = body.get("model") or DEFAULT_MODEL
    spec = MODELS.get(model_id)
    if not spec:
        return {"error": f"unknown model {model_id!r}; known: {list(MODELS)}"}, 400

    render = body.get("render", "spans")
    system = SPAN_PROMPT if render == "spans" else PLAIN_PROMPT

    # "Verify": ground the translation in a live web search.
    #
    # Claude models use their OWN forced web_search (one call, no hop) — it beat the Gemini-fetch
    # hop on the user's real queries: 4.05 vs 3.84 mean, best on 12/22, and faster (5.0s p50 / 8.0s
    # worst vs 5.6 / 9.8). Non-Claude models can't self-search here, so they still get the injected
    # Gemini fetch.
    # Native Claude web_search was trialled here and REVERTED: forcing it (tool_choice "any") makes
    # the model sometimes answer in prose instead of the mandated span format — which the renderer
    # can't parse — it costs 14k-42k input tokens per call vs 1.3k, and its bake-off edge (4.05 vs
    # 3.84 mean on 22 queries) did not reproduce on retest. The injected Gemini fetch keeps the
    # generation step clean, cheap and format-safe. Revisit only with a format fix + a bigger A/B.
    grounding = False
    if bool(body.get("web", False)):
        try:
            ctx = _web_context(text)
            if ctx:
                system = system + ("\n\n## LIVE WEB CONTEXT — current Hong Kong usage (from web "
                                   "search). Prefer these up-to-date forms:\n" + ctx)
                print(f"web verify: injected {len(ctx)} chars of Gemini-fetched context")
        except Exception as e:
            print(f"web verify fetch failed (falling back to no-web): {e}")

    user = USER_TMPL.format(text=text)
    return Response(_stream(spec, system, user, grounding),
                    mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no",
                             "Access-Control-Allow-Origin": "*"})
