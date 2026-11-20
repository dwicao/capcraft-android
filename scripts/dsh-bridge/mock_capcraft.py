#!/usr/bin/env python3
"""A stand-in CapCraft app for testing the bridge without the phone.

Speaks exactly what AgentServer speaks — one `auth` line, then one JSON-RPC
request per connection, one JSON line back — and answers the methods the
bridge uses. Every request it serves is appended to a record file as one JSON
object, so a test can check what the bridge actually sent.

    python3 mock_capcraft.py --port 0 --token test-token --record /tmp/seen.jsonl

The chosen port is printed on the first line, so a caller can read it.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sys
import threading

#: A real 1x1 PNG, so preview_frame returns something decodable.
PNG = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM"
       "IQAAAABJRU5ErkJggg==")

VERSION = {
    "api": "capcraft/1",
    "build": "android-mock",
    "capabilities": ["projects", "edits", "catalogue", "export", "preview"],
}

PROJECTS = [
    {"path": "/tmp/CapCraft/Mock", "name": "Mock", "width": 1080, "height": 1920,
     "modifiedMs": 1700000000000, "sizeBytes": 1234},
]


def project_named(name):
    return [{"path": f"/tmp/CapCraft/{name}", "name": name, "width": 1080,
             "height": 1920, "modifiedMs": 1700000000000, "sizeBytes": 1234}]

VIEW = {
    "revision": 3,
    "name": "Mock",
    "width": 1080,
    "height": 1920,
    "fpsNum": 30,
    "fpsDen": 1,
    "durationUs": 4000000,
    "durationSeconds": 4.0,
    "canUndo": True,
    "canRedo": False,
    "createdId": "c2",
    "rows": [
        {"trackId": "t1", "kind": "main", "muted": False, "visible": True,
         "clips": [{"id": "c1", "kind": "video", "name": "clip.mp4", "mediaId": "m1",
                    "startUs": 0, "durationUs": 4000000, "start": 0.0, "duration": 4.0,
                    "sourceStartUs": 0, "speed": 1.0, "volume": 1.0,
                    "audible": True, "muted": False, "text": "",
                    "effectId": "", "transitionId": ""}]},
    ],
}

PROBE = {
    "path": "/sdcard/DCIM/VID_0001.mp4",
    "durationUs": 12000000,
    "durationSeconds": 12.0,
    "width": 1920,
    "height": 1080,
    "fpsNum": 30,
    "fpsDen": 1,
    "hasAudio": True,
    "audioStreams": ["",
                     ],
    "videoCodec": "h264",
    "audioCodec": "aac",
}


def answer(method, params):
    if method == "version":
        return VERSION
    if method == "project.list":
        return PROJECTS
    if method in ("project.create", "project.open", "project.get"):
        return VIEW
    if method == "project.document":
        return {"name": "Mock", "timelines": []}
    if method in ("project.save", "project.close"):
        return {"saved": True} if method == "project.save" else {"closed": True}
    if method == "project.setVideo":
        return VIEW
    if method == "media.probe":
        return dict(PROBE, path=params.get("path", PROBE["path"]))
    if method in ("media.import", "media.importSound"):
        return {"id": "m1", "kind": "video" if method == "media.import" else "audio",
                "durationUs": 12000000, "durationSeconds": 12.0,
                "width": 1920, "height": 1080, "hasAudio": True}
    if method in ("edit.apply", "edit.undo", "edit.redo") or method.startswith("session."):
        view = dict(VIEW)
        view["createdId"] = "c9"
        return view
    if method == "catalogue.list":
        return [{"id": "concat.glow", "name": "Glow",
                 "kind": params.get("kind", "effect"), "category": "Stylize",
                 "shelves": ["Stylize"], "intensity": "strength",
                 "params": [{"key": "strength", "label": "Strength",
                             "min": 0.0, "max": 1.0, "default": 0.5,
                             "step": 0.01, "unit": "%"}]}]
    if method == "export.start":
        return {"job": "job1", "output": params.get("output", "/tmp/out.mp4"),
                "width": 1080, "height": 1920}
    if method == "export.status":
        return {"job": params.get("job", "job1"), "state": "finished",
                "output": "/tmp/out.mp4"}
    if method == "export.cancel":
        return {"cancelled": True}
    if method == "preview.frame":
        return {"png": PNG, "width": 1280, "height": 720,
                "time": params.get("time", 0.0)}
    raise KeyError(method)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Stand-in CapCraft agent server.")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--token", default="test-token-0123456789abcdef")
    parser.add_argument("--record", default=None)
    parser.add_argument("--project", default="Mock")
    args = parser.parse_args(argv)

    record_lock = threading.Lock()

    def record(entry):
        if not args.record:
            return
        with record_lock:
            with open(args.record, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(entry, ensure_ascii=False) + "\n")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", args.port))
    server.listen(8)
    print(f"127.0.0.1:{server.getsockname()[1]}", flush=True)

    def handle(client):
        try:
            reader = client.makefile("r", encoding="utf-8")
            auth = json.loads(reader.readline() or "{}")
            if auth.get("params", {}).get("token") != args.token:
                client.sendall((json.dumps(
                    {"id": 1, "error": {"message": "bad token"}}) + "\n").encode())
                return
            client.sendall((json.dumps({"id": 1, "result": {}}) + "\n").encode())
            request = json.loads(reader.readline() or "{}")
            record(request)
            result = answer(request.get("method"), request)
            client.sendall((json.dumps({"id": None, "result": result}) + "\n").encode())
        except (KeyError, ValueError) as error:
            try:
                client.sendall((json.dumps(
                    {"id": None,
                     "error": {"message": f"mock: {error}"}}) + "\n").encode())
            except OSError:
                pass
        finally:
            client.close()

    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
