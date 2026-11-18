"""Cramtale: one Claude call through Claude Code (`claude -p`) in Termux.

    python claude_call.py REQUEST.json STREAM.txt

REQUEST.json: {"model", "system", "prompt", "schema" (or null), "effort" (or null)}.
While Claude answers, the reply's text is appended to STREAM.txt piece by piece (the app reads it
to show the passage as it is written). At the end one JSON line is printed:
{"ok": true, "text", "usage", "stop", "model"} or {"ok": false, "error", "retryable"}.
"""
import json
import os
import subprocess
import sys

WORK = os.path.expanduser("~/.cramtale/work")
RETRYABLE = ("overloaded", "rate limit", "timed out", "timeout", "connection", "network", "529", "500", "502", "503")


def finish(**o):
    print(json.dumps(o, ensure_ascii=False))
    sys.exit(0)


def main():
    request_path, stream_path = sys.argv[1], sys.argv[2]
    with open(request_path, encoding="utf-8") as f:
        req = json.load(f)
    schema = req.get("schema")
    system, prompt = req.get("system") or "", req.get("prompt") or ""
    # one command-line argument is capped at 128 KB on Linux: a longer system prompt goes in with the prompt
    if len(system.encode("utf-8")) > 100_000:
        prompt = "<instructions>\n" + system + "\n</instructions>\n\n" + prompt
        system = "Follow the instructions at the start of the user's message."
    cmd = [
        "claude", "-p", "--model", req["model"],
        "--output-format", "stream-json", "--verbose", "--include-partial-messages",
        "--system-prompt", system,
        "--tools", "", "--strict-mcp-config", "--no-session-persistence",
        "--disable-slash-commands", "--setting-sources", "", "--max-turns", "3",
    ]
    if schema:
        cmd += ["--json-schema", schema]
    if req.get("effort"):
        cmd += ["--effort", req["effort"]]
    os.makedirs(WORK, exist_ok=True)
    try:
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, encoding="utf-8", cwd=WORK)
    except FileNotFoundError:
        finish(ok=False, error="`claude` isn't installed in Termux. Run the setup command again.", retryable=False)
    proc.stdin.write(prompt)
    proc.stdin.close()

    result = None
    # with a schema the answer arrives as the StructuredOutput tool's input; without, as text
    wanted = "input_json_delta" if schema else "text_delta"
    with open(stream_path, "a", encoding="utf-8") as stream:
        for line in proc.stdout:
            try:
                o = json.loads(line)
            except ValueError:
                continue
            if o.get("type") == "stream_event":
                d = (o.get("event") or {}).get("delta") or {}
                if d.get("type") == wanted:
                    stream.write(d.get("partial_json") if schema else d.get("text", ""))
                    stream.flush()
            elif o.get("type") == "result":
                result = o
    err = proc.stderr.read()
    proc.wait()

    if result is None:
        detail = (err or "").strip()[-600:] or f"exit code {proc.returncode}"
        finish(ok=False, error=f"Claude Code stopped without an answer: {detail}",
               retryable=any(k in detail.lower() for k in RETRYABLE))
    if result.get("is_error") or result.get("subtype") != "success":
        msg = str(result.get("result") or result.get("subtype") or "error")
        if "login" in msg.lower() or "/login" in msg:
            msg += " (open Termux, run `claude` and log in)"
        finish(ok=False, error=f"Claude Code: {msg}", retryable=any(k in msg.lower() for k in RETRYABLE))
    data = result.get("structured_output")
    text = json.dumps(data, ensure_ascii=False) if (schema and data is not None) else (result.get("result") or "")
    u = result.get("usage") or {}
    finish(ok=True, text=text, stop=result.get("stop_reason") or "end_turn", model=req["model"], usage={
        "input": u.get("input_tokens", 0), "output": u.get("output_tokens", 0),
        "cacheRead": u.get("cache_read_input_tokens", 0), "cacheWrite": u.get("cache_creation_input_tokens", 0),
    })


if __name__ == "__main__":
    main()
