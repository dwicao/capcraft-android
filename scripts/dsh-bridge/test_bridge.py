#!/usr/bin/env python3
"""End-to-end checks: capcraft_bridge -> mock_capcraft, as MCP and as CLI.

    python3 scripts/dsh-bridge/test_bridge.py

Spawns the mock on a free port, points the bridge at it, and asserts the wire
shapes (auth line, method names, seconds-vs-microseconds fields) plus the MCP
framing (tools/list, tools/call, image content for preview_frame).
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BRIDGE = os.path.join(HERE, "capcraft_bridge.py")
MOCK = os.path.join(HERE, "mock_capcraft.py")
TOKEN = "test-token-0123456789abcdef"

failures = []


def check(label, condition, detail=""):
    print(("ok  " if condition else "FAIL") + f" {label}" + (f" — {detail}" if detail and not condition else ""))
    if not condition:
        failures.append(label)


def run_bridge_mcp(messages, env):
    proc = subprocess.run(
        [sys.executable, BRIDGE], input="".join(messages),
        capture_output=True, text=True, timeout=30, env=env)
    return [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]


def main():
    record = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False)
    record.close()
    mock = subprocess.Popen(
        [sys.executable, MOCK, "--port", "0", "--token", TOKEN, "--record", record.name],
        stdout=subprocess.PIPE, text=True)
    try:
        address = mock.stdout.readline().strip()
        assert ":" in address, f"mock did not print host:port, got {address!r}"
        env = dict(os.environ, CAPCRAFT_ENDPOINT=address, CAPCRAFT_TOKEN=TOKEN)

        # --list-tools names every tool once
        out = subprocess.run([sys.executable, BRIDGE, "--list-tools"],
                             capture_output=True, text=True, timeout=15)
        names = out.stdout.split()
        check("list-tools names 34 tools", len(names) == 34, f"got {len(names)}: {names}")
        check("list-tools names are unique", len(set(names)) == len(names))
        for want in ("capcraft_status", "project_open", "clip_add", "title_add",
                     "captions_add", "export_start", "export_wait", "preview_frame"):
            check(f"list-tools names {want}", want in names)

        # MCP tools/list carries JSON schemas
        replies = run_bridge_mcp([
            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize"}) + "\n",
            json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}) + "\n",
        ], env)
        tools = {t["name"]: t for t in replies[1]["result"]["tools"]}
        check("tools/list answers", len(tools) == len(names))
        schema = tools["clip_trim"]["inputSchema"]
        check("clip_trim trims by head/deltaUs",
              "deltaUs" in schema["properties"] and "head" in schema["properties"],
              json.dumps(sorted(schema.get("properties", {}).keys())))
        check("clip_add takes mediaIds/atUs",
              "mediaIds" in tools["clip_add"]["inputSchema"]["properties"])

        def call(name, args):
            replies = run_bridge_mcp([
                json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                            "params": {"name": name, "arguments": args}}) + "\n",
            ], env)
            result = replies[0]["result"]
            if result.get("isError"):
                raise AssertionError(f"{name} error: {result}")
            return result

        # status/version through the socket
        status = json.loads(call("capcraft_status", {})["content"][0]["text"])
        check("status connected", status["connected"] is True, json.dumps(status))
        check("status carries address", status["endpoint"] == address)

        # projects and edits round-trip
        view = json.loads(call("project_open", {"path": "/tmp/CapCraft/Mock"})["content"][0]["text"])
        check("project_open returns rows", bool(view["rows"]), json.dumps(view)[:200])
        check("view keeps microsecond starts", view["rows"][0]["clips"][0]["startUs"] == 0)
        check("view adds second starts", view["rows"][0]["clips"][0]["start"] == 0.0)
        edited = json.loads(call("clip_trim", {"path": "/tmp/CapCraft/Mock", "clipId": "c1",
                                               "head": True, "deltaUs": -500000})["content"][0]["text"])
        check("clip_trim returns a view", edited["createdId"] == "c9")

        # preview_frame returns an image part plus text
        preview = call("preview_frame", {"path": "/tmp/CapCraft/Mock", "time": 1.0})
        kinds = [part.get("type") for part in preview["content"]]
        check("preview_frame returns image+text", kinds == ["image", "text"], json.dumps(kinds))
        raw = base64.b64decode(preview["content"][0]["data"])
        check("preview image is a PNG", raw[:8] == b"\x89PNG\r\n\x1a\n")

        # export start + wait (mock finishes at once)
        started = json.loads(call("export_start", {"path": "/tmp/CapCraft/Mock",
                                                   "output": "/tmp/out.mp4"})["content"][0]["text"])
        check("export_start returns a job", started["job"] == "job1")
        ended = json.loads(call("export_wait", {"job": "job1", "timeoutSeconds": 5})["content"][0]["text"])
        check("export_wait sees finished", ended["state"] == "finished", json.dumps(ended))

        # CLI --call with --args
        out = subprocess.run(
            [sys.executable, BRIDGE, "--call", "media_probe",
             "--args", json.dumps({"path": "/sdcard/DCIM/VID_0001.mp4"})],
            capture_output=True, text=True, timeout=15, env=env)
        probe = json.loads(out.stdout)["content"][0]["text"]
        check("CLI media_probe answers", '"hasAudio": true' in probe, probe[:160])

        # what the bridge actually sent, from the mock's record
        with open(record.name, encoding="utf-8") as handle:
            seen = [json.loads(line) for line in handle if line.strip()]
        methods = [entry.get("method") for entry in seen]
        check("bridge sent session.* for edits", "session.trim" in methods, json.dumps(methods))
        check("bridge sent preview.frame", "preview.frame" in methods)
        trim = next(e for e in seen if e.get("method") == "session.trim")
        check("trim keeps microsecond fields",
              trim.get("deltaUs") == -500000 and trim.get("head") is True, json.dumps(trim))
    finally:
        mock.terminate()
        os.unlink(record.name)

    if failures:
        print(f"\n{len(failures)} FAILURES")
        return 1
    print("\nall bridge checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
