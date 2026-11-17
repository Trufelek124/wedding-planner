#!/usr/bin/env python3
"""Call the infokuznia tracker's agent tools (MCP over HTTP).

    scripts/ops/ik.py <tool> ['<json object>'] [--quiet]
    scripts/ops/ik.py --tools

IK_MCP_URL     the tracker's MCP endpoint (required).
IK_TOKEN_FILE  file holding the agent token; default ~/.infokuznia/token.
               When the file does not exist no Authorization header is sent:
               in a Claude cloud session the environment's network proxy adds
               it. The token is never printed.

Exit codes: 0 ok, 2 tool error or refused argument, 3 transport error.

`claim_task` answers with a very large snapshot: pass --quiet, or import this
module and pick fields from the returned dict.
"""
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

TOOL_NAME = re.compile(r"^[a-z_]+$")


class IkError(Exception):
    """A tool-level refusal; `payload` is the tracker's error object."""

    def __init__(self, payload):
        super().__init__(json.dumps(payload, ensure_ascii=False)[:500])
        self.payload = payload

    @property
    def code(self):
        err = self.payload.get("error") if isinstance(self.payload, dict) else None
        return err.get("code") if isinstance(err, dict) else None


def _url():
    url = os.environ.get("IK_MCP_URL", "").strip()
    if not url.startswith("https://"):
        raise SystemExit("IK_MCP_URL is not set (the tracker's https://…/mcp endpoint)")
    return url


def _headers():
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        # the tracker's edge refuses Python's default User-Agent (Cloudflare error 1010)
        "User-Agent": "ik-client/1.0",
    }
    token_file = pathlib.Path(os.environ.get("IK_TOKEN_FILE", "~/.infokuznia/token")).expanduser()
    if token_file.is_file():
        headers["Authorization"] = "Bearer " + token_file.read_text().strip()
    return headers


def rpc(method, params=None, rid=1):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    req = urllib.request.Request(_url(), data=json.dumps(body).encode(), headers=_headers(), method="POST")
    with urllib.request.urlopen(req, timeout=90) as resp:
        raw = resp.read().decode("utf-8", "replace")
    messages = []
    if raw.lstrip().startswith("{"):
        messages.append(json.loads(raw, strict=False))
    else:  # server-sent events: one JSON message per "data:" line
        for line in raw.splitlines():
            if line.startswith("data:"):
                messages.append(json.loads(line[5:].strip(), strict=False))
    for message in messages:
        if message.get("id") == rid:
            if "error" in message:
                raise IkError({"error": message["error"]})
            return message["result"]
    raise RuntimeError("no JSON-RPC answer in the response")


def tools():
    return rpc("tools/list")["tools"]


def call(tool, arguments=None):
    """Run one tool and return its result as a dict; raise IkError on a refusal."""
    if not TOOL_NAME.match(tool):
        raise ValueError("tool name must match ^[a-z_]+$")
    arguments = {} if arguments is None else arguments
    if not isinstance(arguments, dict):
        raise ValueError("arguments must be a JSON object")
    result = rpc("tools/call", {"name": tool, "arguments": arguments})
    text = "".join(part.get("text", "") for part in result.get("content", []) if part.get("type") == "text")
    try:
        data = json.loads(text, strict=False)  # comment bodies can hold raw control characters
    except ValueError:
        data = {"text": text}
    if result.get("isError"):
        raise IkError(data)
    structured = result.get("structuredContent")
    return structured if isinstance(structured, dict) else data


def main(argv):
    args = [a for a in argv if a != "--quiet"]
    quiet = len(args) != len(argv)
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    try:
        if args[0] == "--tools":
            for tool in tools():
                schema = tool.get("inputSchema", {})
                required = set(schema.get("required", []))
                names = [n if n in required else n + "?" for n in schema.get("properties", {})]
                print(f"{tool['name']}({', '.join(names)})")
            return 0
        arguments = json.loads(args[1]) if len(args) > 1 else {}
        data = call(args[0], arguments)
    except (ValueError, IkError) as exc:
        payload = exc.payload if isinstance(exc, IkError) else {"error": {"code": "BAD_ARGUMENT", "message": str(exc)}}
        print(json.dumps(payload, ensure_ascii=False, indent=1), file=sys.stderr)
        return 2
    except (urllib.error.URLError, RuntimeError, TimeoutError) as exc:
        print(f"transport error: {exc}", file=sys.stderr)
        return 3
    if not quiet:
        print(json.dumps(data, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
