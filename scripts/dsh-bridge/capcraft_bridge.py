#!/usr/bin/env python3
"""CapCraft bridge for the DSH harness: an MCP stdio server over the app's agent socket.

CapCraft (Settings > Automation) speaks JSON-RPC on a loopback port, mirroring
desktop Concat's automation socket. This file turns that socket into MCP tools,
so an agent in DSH (or any MCP client) can drive CapCraft: open a project,
import media, edit the timeline, render a frame preview, export and wait for it.

    python3 capcraft_bridge.py                 # MCP server on stdin/stdout
    python3 capcraft_bridge.py --call capcraft_status '{}'
    python3 capcraft_bridge.py --list-tools

Where it looks for the port, in order:
    --endpoint host:port, $CAPCRAFT_ENDPOINT, automation.json, 127.0.0.1:7420
`automation.json` is written by the app (filesDir/automation.json); pull it
with `adb pull` or read it on-device. The token beside the address goes on the
first `auth` line. $CAPCRAFT_TOKEN overrides the token.

Standard library only; `python3` is the whole dependency.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import socket
import sys
import time

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "capcraft", "version": "0.1.0"}

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 7420
CONFIG_NAME = "automation.json"


def config_candidates():
    home = os.path.expanduser("~")
    seen = []
    for root in (os.environ.get("CAPCRAFT_CONFIG"), os.environ.get("XDG_CONFIG_HOME"),
                 os.path.join(home, ".config"), home):
        if root:
            seen.append(os.path.join(root, "capcraft", CONFIG_NAME))
            seen.append(os.path.join(root, CONFIG_NAME))
    env = os.environ.get("CAPCRAFT_API_FILE")
    if env:
        seen.insert(0, env)
    return seen


class ToolError(Exception):
    """A refusal worth showing the model: it becomes an MCP error result."""


# --------------------------------------------------------------------------
# JSON schemas
# --------------------------------------------------------------------------

def string(description, **extra):
    return dict(type="string", description=description, **extra)


def number(description, **extra):
    return dict(type="number", description=description, **extra)


def integer(description, **extra):
    return dict(type="integer", description=description, **extra)


def boolean(description, **extra):
    return dict(type="boolean", description=description, **extra)


def array(items, description, **extra):
    return dict(type="array", items=items, description=description, **extra)


def obj(properties, required=(), description=None, **extra):
    schema = {"type": "object", "properties": properties}
    if required:
        schema["required"] = list(required)
    if description:
        schema["description"] = description
    schema.update(extra)
    return schema


def ref(name):
    return {"$ref": "#/definitions/" + name}


DEFINITIONS = {
    "video": obj(
        {
            "width": integer("Frame width in pixels."),
            "height": integer("Frame height in pixels."),
            "rateNum": integer("Frame rate numerator; 30000 with rateDen 1001 is 29.97 fps."),
            "rateDen": integer("Frame rate denominator."),
        },
        ("width", "height", "rateNum", "rateDen"),
        "Frame size and rate.",
    ),
    "clip_move": obj(
        {
            "clipId": string("The clip to move."),
            "start": number("New start in seconds."),
            "trackId": string("Destination track; omit to keep the clip's track."),
        },
        ("clipId", "start"),
        "One clip's new place.",
    ),
    "caption": obj(
        {
            "start": number("Start in seconds."),
            "end": number("End in seconds; must be after the start."),
            "text": string("The words to show."),
        },
        ("start", "end", "text"),
        "One caption.",
    ),
}


# --------------------------------------------------------------------------
# Tools
# --------------------------------------------------------------------------

class Tool:
    def __init__(self, name, title, description, kind, target, schema,
                 read_only=False):
        self.name = name
        self.title = title
        self.description = description
        self.kind = kind          # "method", "edit" or a special handler name
        self.target = target      # CapCraft method, or the command's `op`
        self.schema = schema
        self.read_only = read_only

    def as_mcp(self):
        schema = dict(self.schema)
        schema.setdefault("type", "object")
        schema.setdefault("properties", {})
        if DEFINITIONS:
            schema["definitions"] = DEFINITIONS
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": schema,
            "annotations": {
                "title": self.title,
                "readOnlyHint": self.read_only,
                "destructiveHint": False,
                "idempotentHint": False,
                "openWorldHint": False,
            },
        }


def method_tool(name, title, description, method, properties, required=(),
                read_only=False):
    return Tool(name, title, description, "method", method,
                obj(properties, required), read_only)


def edit_tool(name, title, description, op, properties, required=("path",),
              read_only=False):
    return Tool(name, title, description, "edit", op,
                obj(properties, required), read_only)


TOOLS = []


def add(tool):
    TOOLS.append(tool)
    return tool


# --- what is running -------------------------------------------------------

add(Tool(
    "capcraft_status", "Bridge status",
    "What this bridge reached: the address and port it dialled, where the token "
    "came from, the CapCraft build on the other end, and whether it answered. "
    "Call it first when a call fails.",
    "status", None,
    obj({}),
    read_only=True,
))

add(method_tool(
    "version", "CapCraft version",
    "What this CapCraft speaks: API version, build, and capabilities.",
    "version", {}, read_only=True,
))

# --- projects --------------------------------------------------------------

add(method_tool(
    "project_list", "Recent projects",
    "Every project on this phone, the most recently changed first: each one's "
    "folder `path`, name, frame size. A project is a folder, and every other "
    "project tool names it by that `path`.",
    "project.list", {}, read_only=True,
))

add(method_tool(
    "project_create", "Create a project",
    "Makes an empty project titled `name` at `width` by `height`, `fps` frames "
    "a second, and opens it. Returns its folder `path`, which later tools take.",
    "project.create",
    {"name": string("The project's title."),
     "width": integer("Frame width in pixels; default 1080."),
     "height": integer("Frame height in pixels; default 1920."),
     "fps": integer("Frames a second; default 30.")},
    ("name",),
))

add(method_tool(
    "project_open", "Open a project",
    "Opens the project in `path` so it can be read and edited, and returns its "
    "timeline: rows of clips with ids, kinds, names, starts and durations in "
    "both microseconds and seconds. Timelines keep seconds; the bridge keeps "
    "seconds too (see clip tools).",
    "project.open",
    {"path": string("The project folder.")},
    ("path",),
    read_only=True,
))

add(method_tool(
    "project_get", "Get the timeline",
    "The open project's timeline as it stands: rows, clips, duration, undo "
    "state. Same as project_open; neither saves.",
    "project.get",
    {"path": string("The project folder.")},
    ("path",),
    read_only=True,
))

add(method_tool(
    "project_document", "Get the document",
    "The project's document exactly as a save would write it.",
    "project.document",
    {"path": string("The project folder.")},
    ("path",),
    read_only=True,
))

add(method_tool(
    "project_save", "Save a project",
    "Writes the open project's document to its folder; edits stay in memory "
    "until this is called.",
    "project.save",
    {"path": string("The project folder.")},
    ("path",),
))

add(method_tool(
    "project_close", "Close a project",
    "Drops an open project from the bridge's sessions. With `save` true it is "
    "saved first; otherwise unsaved edits stay in the app, unsaved.",
    "project.close",
    {"path": string("The project folder."),
     "save": boolean("Save before closing; default false.")},
    ("path",),
))

add(method_tool(
    "project_set_video", "Set frame size and rate",
    "Sets the timeline's frame (width, height in pixels) and rate as one "
    "undoable edit.",
    "project.setVideo",
    {"path": string("The project folder."), "video": ref("video")},
    ("path", "video"),
))

# --- media -----------------------------------------------------------------

add(method_tool(
    "media_probe", "Probe a media file",
    "What is inside a video, audio or image file: duration in seconds, picture "
    "size, frame rate, codecs, audio streams. Changes no project.",
    "media.probe",
    {"path": string("Absolute path of the file to probe.")},
    ("path",),
    read_only=True,
))

add(method_tool(
    "media_import", "Import media",
    "Reads a file and adds it to the open project's bin, as dropping it on the "
    "app does. The new item's id is `id` (`m3`); place it with clip_add. A file "
    "already in the bin is answered with its existing entry.",
    "media.import",
    {"path": string("The project folder."),
     "file": string("Absolute path of the media file to import.")},
    ("path", "file"),
))

add(method_tool(
    "media_import_sound", "Import sound",
    "Reads a file and adds only its sound to the bin: a video brought in for "
    "its sound comes in as sound, so a clip of it is a sound clip. A file "
    "with no sound is refused.",
    "media.importSound",
    {"path": string("The project folder."),
     "file": string("Absolute path of the file to import.")},
    ("path", "file"),
))

# --- history ---------------------------------------------------------------

add(method_tool(
    "edit_apply", "Apply an edit",
    "Applies any one edit command to an open project as one undoable step, with "
    "the same limits and refusals as the app. `command` is an object tagged by "
    "`op` (`addClip`, `trimClip`, `splitClips`, `moveClips`, `updateClip`, "
    "`addTextClip`, `addTrack`, ...); times are seconds as the engine's are. "
    "A refusal changes nothing.",
    "edit.apply",
    {"path": string("The project folder."),
     "command": {"type": "object",
                 "description": "The command, tagged by `op`; times in seconds."}},
    ("path", "command"),
))

add(method_tool(
    "edit_undo", "Undo",
    "Steps an open project's history back one edit.",
    "edit.undo",
    {"path": string("The project folder.")},
    ("path",),
))

add(method_tool(
    "edit_redo", "Redo",
    "Steps an open project's history forward one edit.",
    "edit.redo",
    {"path": string("The project folder.")},
    ("path",),
))

# --- clips -----------------------------------------------------------------

add(edit_tool(
    "clip_add", "Add a clip to the timeline",
    "Puts bin items on the main track, one after another, each starting where "
    "the last ends (a project with nothing on it takes its frame from the "
    "first picture). Returns the timeline, with the new clip's id in "
    "`createdId`.",
    "addToMain",
    {"path": string("The project folder."),
     "mediaIds": array(string("A media bin id."), "Media bin ids, in order."),
     "atUs": integer("Where to add them, in microseconds; omit to append.")},
    ("path", "mediaIds"),
))

add(edit_tool(
    "clip_overlay", "Add an overlay",
    "Adds one bin item as an overlay at `atUs` microseconds, on a free lane of "
    "its kind. Returns the timeline, with the new clip's id in `createdId`.",
    "addOverlay",
    {"path": string("The project folder."),
     "mediaId": string("Media bin id, from media_import."),
     "atUs": integer("Where it starts, in microseconds.")},
    ("path", "mediaId", "atUs"),
))

add(edit_tool(
    "clip_audio", "Add a sound clip",
    "Adds a sound bin item (see media_import_sound) on an audio row at `atUs` "
    "microseconds. Returns the timeline, with the new clip's id in `createdId`.",
    "addAudio",
    {"path": string("The project folder."),
     "mediaId": string("Sound bin id, from media_import_sound."),
     "atUs": integer("Where it starts, in microseconds.")},
    ("path", "mediaId", "atUs"),
))

add(edit_tool(
    "clip_sticker", "Add a sticker",
    "Puts a picture bin item on a text row at `atUs` microseconds for "
    "`durationUs` microseconds, as the app's stickers do.",
    "addSticker",
    {"path": string("The project folder."),
     "mediaId": string("Picture bin id, from media_import."),
     "atUs": integer("Where it starts, in microseconds."),
     "durationUs": integer("How long it stays, in microseconds.")},
    ("path", "mediaId", "atUs", "durationUs"),
))

add(edit_tool(
    "title_add", "Add a title",
    "Adds a title at `atUs` microseconds for `durationUs` microseconds, styled "
    "by `style` (its `content` is the words). Returns the timeline, with the "
    "new clip's id in `createdId`.",
    "addText",
    {"path": string("The project folder."),
     "atUs": integer("Where it starts, in microseconds."),
     "durationUs": integer("How long it stays, in microseconds."),
     "style": {"type": "object", "description": "The title: content plus its look."}},
    ("path", "atUs", "durationUs", "style"),
))

add(edit_tool(
    "clips_remove", "Remove clips",
    "Removes clips. Main-track clips close up behind them; other rows keep "
    "their space.",
    "delete",
    {"path": string("The project folder."),
     "clipIds": array(string("A clip id."), "Clips to remove.")},
    ("path", "clipIds"),
))

add(edit_tool(
    "clips_split", "Split clips",
    "Splits clips at `atUs` microseconds.",
    "split",
    {"path": string("The project folder."),
     "clipIds": array(string("A clip id."), "Clips to split."),
     "atUs": integer("Cut point, in microseconds.")},
    ("path", "clipIds", "atUs"),
))

add(edit_tool(
    "clip_trim", "Trim a clip's edge",
    "Drags one edge of a clip by `deltaUs` microseconds (positive is to the "
    "right): `head` for the start, else the end. On the main track the clips "
    "after it follow.",
    "trim",
    {"path": string("The project folder."),
     "clipId": string("The clip to trim."),
     "head": boolean("Trim the start (`head`) or the end."),
     "deltaUs": integer("How far, in microseconds; negative shortens.")},
    ("path", "clipId", "head", "deltaUs"),
))

add(edit_tool(
    "clips_move", "Move a clip",
    "Moves a clip of a row other than main to `startUs` on `trackId` (empty "
    "keeps its lane).",
    "moveClip",
    {"path": string("The project folder."),
     "clipId": string("The clip to move."),
     "trackId": string("Destination track; empty keeps its lane."),
     "startUs": integer("Where it starts, in microseconds.")},
    ("path", "clipId", "startUs"),
))

add(edit_tool(
    "clip_reorder", "Reorder the main track",
    "Moves a main-track clip to position `index` among the main track's clips, "
    "the others closing up around it.",
    "reorderMain",
    {"path": string("The project folder."),
     "clipId": string("The main-track clip to move."),
     "index": integer("Position among the main track's clips.")},
    ("path", "clipId", "index"),
))

add(edit_tool(
    "clip_update", "Change a clip's properties",
    "Changes clip properties: `patch` holds the engine's field names (volume, "
    "opacity, flipH, videoEffects, ...).",
    "update",
    {"path": string("The project folder."),
     "clipId": string("The clip to patch."),
     "patch": {"type": "object",
               "description": "Fields to change: volume, opacity, flipH, videoEffects, ..."}},
    ("path", "clipId", "patch"),
))

# --- catalogue -------------------------------------------------------------

add(method_tool(
    "catalogue_list", "List effects and filters",
    "Every package of `kind` (\"effect\", \"filter\", \"audio\", \"transition\") "
    "in the catalogue's order, with the parameters a clip chain names.",
    "catalogue.list",
    {"kind": string("One kind: `effect`, `filter`, `audio` or `transition`; default `effect`.")},
    read_only=True,
))

# --- export ----------------------------------------------------------------

add(method_tool(
    "export_start", "Start an export",
    "Starts writing the timeline to `output`, and returns at once with a job "
    "id. Ask export_status or export_wait how it is going. `resolution` is the "
    "output's short side in pixels; `fps` frames a second; `bitrateKbps` 0 lets "
    "the encoder choose; `codec` h264 or h265.",
    "export.start",
    {"path": string("The project folder."),
     "output": string("File to write, with its extension."),
     "resolution": integer("Short side in pixels; default the timeline's."),
     "fps": integer("Frames a second; default 30."),
     "bitrateKbps": integer("Target bitrate in kbps; 0 lets the encoder choose."),
     "codec": string("h264 or h265; default h264.")},
    ("path", "output"),
))

add(method_tool(
    "export_status", "Export status",
    "One export job's state: running, finished (with the file written) or "
    "failed, by its `job` id from export_start.",
    "export.status",
    {"job": string("The job id, from export_start.")},
    ("job",),
    read_only=True,
))

add(Tool(
    "export_wait", "Wait for an export",
    "Polls an export until it ends or `timeoutSeconds` passes, returning the "
    "job as it stands. A still-running job says so; call again.",
    "export_wait", None,
    obj({"job": string("The job id, from export_start."),
         "timeoutSeconds": number("How long to wait; default 60, at most 600.")},
        ("job",)),
    read_only=True,
))

add(method_tool(
    "export_cancel", "Cancel an export",
    "Asks the running export to stop, and reports the job as it stands.",
    "export.cancel",
    {"job": string("The job id, from export_start.")},
    ("job",),
))

add(Tool(
    "preview_frame", "Render a frame",
    "Renders the frame at `time` seconds to a PNG and returns it as an image, "
    "so you can see what an edit did. Without width/height the frame is "
    "scaled to fit 1280 pixels. With `output` it writes the PNG there instead "
    "of returning it.",
    "preview", None,
    obj({"path": string("The project folder."),
         "time": number("Which frame, in seconds."),
         "width": integer("Picture width in pixels; optional."),
         "height": integer("Picture height in pixels; optional."),
         "output": string("Write the PNG here instead of returning it; optional.")},
        ("path", "time")),
    read_only=True,
))

BY_NAME = {tool.name: tool for tool in TOOLS}

#: Session methods behind the `edit.*` tools: what the server dispatches.
EDIT_METHODS = {
    "addToMain": "session.addToMain",
    "addOverlay": "session.addOverlay",
    "addAudio": "session.addAudio",
    "addSticker": "session.addSticker",
    "addText": "session.addText",
    "delete": "session.delete",
    "split": "session.split",
    "trim": "session.trim",
    "moveClip": "session.moveClip",
    "reorderMain": "session.reorderMain",
    "update": "session.update",
}


# --------------------------------------------------------------------------
# Client
# --------------------------------------------------------------------------

class Endpoint:
    def __init__(self, host, port, token, source):
        self.host = host
        self.port = port
        self.token = token
        self.source = source

    def __str__(self):
        return f"{self.host}:{self.port}"


def read_listing(path):
    with open(path, encoding="utf-8") as handle:
        listing = json.load(handle)
    if not isinstance(listing, dict) or listing.get("version") != 1:
        raise ToolError(f"{path} is not an automation listing")
    if not isinstance(listing.get("address"), str):
        raise ToolError(f"{path} names no address")
    return listing


def parse_endpoint(text):
    text = (text or "").strip()
    if text.startswith("["):
        host, _, port = text[1:].partition("]:")
    else:
        host, _, port = text.partition(":")
    try:
        port = int(port, 10)
    except ValueError:
        raise ToolError(f"{text!r} is not host:port")
    if not host or not (1 <= port <= 65535):
        raise ToolError(f"{text!r} is not host:port")
    return host, port


def resolve(cli_endpoint=None, cli_token=None, verbose=False):
    if cli_endpoint:
        host, port = parse_endpoint(cli_endpoint)
        token = cli_token if cli_token is not None else os.environ.get("CAPCRAFT_TOKEN", "")
        return Endpoint(host, port, token, "flag")
    for text in (os.environ.get("CAPCRAFT_ENDPOINT"),):
        if text:
            host, port = parse_endpoint(text)
            return Endpoint(host, port, os.environ.get("CAPCRAFT_TOKEN", ""), "setting")
    for path in config_candidates():
        if not os.path.isfile(path):
            continue
        try:
            listing = read_listing(path)
        except (OSError, ValueError, ToolError) as error:
            if verbose:
                print(f"capcraft: skipping {path}: {error}", file=sys.stderr)
            continue
        try:
            host, port = parse_endpoint(listing["address"])
        except ToolError as error:
            if verbose:
                print(f"capcraft: skipping {path}: {error}", file=sys.stderr)
            continue
        token = os.environ.get("CAPCRAFT_TOKEN")
        if token is None:
            token = listing.get("token", "")
        return Endpoint(host, port, token, path)
    return Endpoint(DEFAULT_HOST, DEFAULT_PORT, os.environ.get("CAPCRAFT_TOKEN", ""), "default")


def listing_search_note():
    return ("looked in " + ", ".join(config_candidates()) +
            "; set CAPCRAFT_ENDPOINT=host:port / CAPCRAFT_TOKEN, "
            "or copy the app's filesDir/automation.json")


class Concat:
    """One TCP connection per call; one request line, one reply line, close."""

    def __init__(self, endpoint, timeout=30.0):
        self.endpoint = endpoint
        self.timeout = timeout

    def call(self, request):
        data = (json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8")
        if len(data) > 4 * 1024 * 1024:
            raise ToolError("that request is larger than the 4 MB line CapCraft reads")
        sock = socket.create_connection((self.endpoint.host, self.endpoint.port),
                                        timeout=self.timeout)
        try:
            sock.settimeout(self.timeout)
            sock.sendall(("%s\n" % json.dumps(
                {"jsonrpc": "2.0", "id": 1, "method": "auth",
                 "params": {"token": self.endpoint.token}},
                separators=(",", ":"))).encode("utf-8"))
            sock.sendall(data)
            return self._read_reply(sock)
        finally:
            sock.close()

    def _read_line(self, sock):
        buffer = bytearray()
        while len(buffer) <= 4 * 1024 * 1024 + 1:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buffer += chunk
            index = buffer.find(b"\n")
            if index >= 0:
                return bytes(buffer[:index]).decode("utf-8", "replace")
        raise ToolError("CapCraft closed the connection without answering")

    def _read_reply(self, sock):
        auth = json.loads(self._read_line(sock) or "{}")
        if auth.get("error"):
            raise ToolError("CapCraft refused the token; copy the new one from Settings > Automation. " +
                            listing_search_note())
        reply = json.loads(self._read_line(sock) or "{}")
        if reply.get("error"):
            raise ToolError(str(reply["error"].get("message", reply["error"])))
        if "result" not in reply:
            raise ToolError("CapCraft answered without a result")
        return reply["result"]


def call_tool(name, arguments):
    """Run one tool, returning the MCP result object."""
    tool = BY_NAME[name]
    con = Concat(resolve())

    if tool.kind == "status":
        return text_result(status_view(con))
    if tool.name == "export_wait":
        return text_result(wait_for_export(con, arguments))
    if tool.name == "captions_add":
        return text_result(con.call(captions_request(arguments)))
    if tool.name == "preview_frame":
        return preview_result(con, arguments)
    if tool.name == "clip_update":
        return text_result(con.call(edit_update_request(arguments)))
    if tool.kind == "method":
        return text_result(con.call(with_method(arguments, tool.target)))
    if tool.kind == "edit":
        return text_result(con.call(edit_method_request(arguments, tool.target)))
    raise ToolError(f"the bridge does not know the tool {name!r}")


def with_method(arguments, method):
    request = dict(arguments or {})
    request["method"] = method
    return request


def edit_method_request(arguments, op):
    """An edit.* tool behind a session method the server dispatches."""
    method = EDIT_METHODS[op]
    params = dict(arguments or {})
    if not params.get("path"):
        raise ToolError("invalid arguments: missing field `path`")
    params["method"] = method
    return params


def edit_update_request(arguments):
    """`clip_update` behind edit.apply's updateClip op, like the app sends."""
    params = {"path": (arguments or {}).get("path")}
    if not params["path"]:
        raise ToolError("invalid arguments: missing field `path`")
    for name in ("clipId", "patch"):
        if (arguments or {}).get(name) is None:
            raise ToolError(f"invalid arguments: missing field `{name}`")
    params["command"] = {"op": "updateClip", "clipId": arguments["clipId"],
                         "patch": arguments["patch"]}
    params["method"] = "edit.apply"
    params["path"] = arguments["path"]
    return params


def captions_request(arguments):
    """One batch of titles as one edit, like the app's captions importer."""
    path = (arguments or {}).get("path")
    captions = (arguments or {}).get("captions")
    if not path:
        raise ToolError("invalid arguments: missing field `path`")
    if not isinstance(captions, list) or not captions:
        raise ToolError("invalid arguments: `captions` is a list of {start, end, text}")
    offset = arguments.get("offsetY", 0.35)
    style = dict(arguments.get("style") or
                 {"fontFamily": "Hanken Grotesk", "fontSize": 0.04, "fontWeight": 600})
    style.pop("runs", None)
    commands = []
    for index, caption in enumerate(captions):
        try:
            start = float(caption["start"])
            end = float(caption["end"])
            text = caption["text"]
        except (KeyError, TypeError, ValueError):
            raise ToolError(f"invalid arguments: caption {index} needs a start, an end and a text")
        if not (start >= 0.0 and end > start):
            raise ToolError(f"invalid arguments: caption {index} ends at {end} s, "
                            f"not after its start at {start} s")
        one = dict(style)
        one["content"] = text
        commands.append({"op": "addTextClip", "trackId": None, "above": True,
                         "start": start, "duration": end - start,
                         "style": one, "offsetY": offset})
    return {"method": "edit.apply", "path": path,
            "command": {"op": "batch", "commands": commands}}


# --- captions_add as a composed tool ---------------------------------------

add(Tool(
    "captions_add", "Add captions",
    "Adds several captions as one undoable step: each caption becomes a title "
    "on its own track, timed from `start` to `end` seconds. `style` is the shared "
    "look (`content` is filled in per caption); `offsetY` places them (0.35 is "
    "the lower third).",
    "captions", None,
    obj({"path": string("The project folder."),
         "captions": array(ref("caption"), "The captions, in time order."),
         "style": {"type": "object",
                   "description": "Shared style for every caption: fontSize, "
                                  "fontWeight, color, align, background..."},
         "offsetY": number("Vertical position as a fraction of frame height; "
                           "default 0.35.")},
        ("path", "captions")),
))


def wait_for_export(con, arguments, poll=0.25):
    timeout = arguments.get("timeoutSeconds", 60.0)
    try:
        timeout = max(0.0, min(float(timeout), 600.0))
    except (TypeError, ValueError):
        raise ToolError("invalid arguments: `timeoutSeconds` is a number of seconds")
    job = {"method": "export.status", "job": arguments["job"]}
    until = time.monotonic() + timeout
    while True:
        status = con.call(job)
        if status.get("state") != "running":
            return status
        if time.monotonic() >= until:
            status["note"] = "still running: call export_wait again, or export_status"
            return status
        time.sleep(poll)


def preview_result(con, arguments):
    arguments = dict(arguments or {})
    wants_file = arguments.get("output") not in (None, "")
    sized = arguments.get("width") not in (None, "") and arguments.get("height") not in (None, "")
    if not wants_file and not sized:
        view = con.call({"method": "project.get", "path": arguments.get("path")})
        width = float(view.get("width") or 1920)
        height = float(view.get("height") or 1080)
        scale = min(1280.0 / max(width, height), 1.0)
        arguments["width"] = max(1, round(width * scale))
        arguments["height"] = max(1, round(height * scale))
    arguments["method"] = "preview.frame"
    result = con.call(arguments)
    png = result.get("png")
    if not png:
        return text_result(result)
    try:
        raw = base64.b64decode(png)
    except (binascii.Error, ValueError):
        raise ToolError("CapCraft answered a preview that is not PNG")
    if len(raw) > 4 * 1024 * 1024:
        path = (arguments.get("output") or "").strip()
        if not path:
            raise ToolError("that frame is larger than 4 MB; call preview_frame with `output` to save it")
    return {
        "content": [
            {"type": "image", "data": png, "mimeType": "image/png"},
            {"type": "text",
             "text": f"The frame at {arguments.get('time')} s, "
                     f"{result.get('width')}x{result.get('height')}."},
        ],
        "structuredContent": {"width": result.get("width"),
                              "height": result.get("height"),
                              "time": arguments.get("time")},
    }


def status_view(con):
    try:
        version = con.call({"method": "version"})
        answer = "answered"
    except ToolError as error:
        version, answer = None, str(error)
    return {
        "endpoint": str(con.endpoint),
        "endpointSource": con.endpoint.source,
        "status": answer,
        "connected": answer == "answered",
        "capcraft": version,
    }


def text_result(value):
    return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]}


def send(payload):
    sys.stdout.write(json.dumps(payload, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def send_error(request_id, code, message):
    send({"jsonrpc": "2.0", "id": request_id,
          "error": {"code": code, "message": message}})


def read_message():
    line = sys.stdin.readline()
    if not line:
        return None
    try:
        return json.loads(line)
    except ValueError:
        return {"jsonrpc": "2.0", "id": None, "method": "",
                "_parse_error": "not JSON"}


def serve(stdin=None, stdout=None):
    if stdin is not None:
        sys.stdin = stdin
    if stdout is not None:
        sys.stdout = stdout
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except ValueError:
            send_error(None, -32700, "not JSON")
            continue
        if not isinstance(message, dict):
            send_error(None, -32600, "not an object")
            continue
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}
        try:
            if method == "initialize":
                send({"jsonrpc": "2.0", "id": request_id,
                      "result": {"protocolVersion": PROTOCOL_VERSION,
                                 "capabilities": {},
                                 "serverInfo": SERVER_INFO}})
            elif method == "notifications/initialized":
                pass
            elif method == "tools/list":
                send({"jsonrpc": "2.0", "id": request_id,
                      "result": {"tools": [tool.as_mcp() for tool in TOOLS]}})
            elif method == "tools/call":
                name = params.get("name", "")
                arguments = params.get("arguments") or {}
                if name not in BY_NAME:
                    send_error(request_id, -32601, f"no tool {name!r}")
                    continue
                try:
                    send({"jsonrpc": "2.0", "id": request_id,
                          "result": call_tool(name, arguments)})
                except ToolError as error:
                    send({"jsonrpc": "2.0", "id": request_id,
                          "result": {"content": [{"type": "text",
                                                  "text": f"capcraft: {error}"}],
                                     "isError": True}})
                except (socket.timeout, TimeoutError, OSError) as error:
                    send({"jsonrpc": "2.0", "id": request_id,
                          "result": {"content": [{"type": "text",
                                                  "text": f"capcraft: no answer ({error}); "
                                                          f"{listing_search_note()}"}],
                                     "isError": True}})
            else:
                send_error(request_id, -32601, f"no method {method!r}")
        except BrokenPipeError:
            break


def main(argv=None):
    parser = argparse.ArgumentParser(description="CapCraft MCP bridge: MCP stdio over the app's agent socket.")
    parser.add_argument("--endpoint", default=None, help="host:port of the app (Settings > Automation)")
    parser.add_argument("--token", default=None, help="token beside the address in automation.json")
    parser.add_argument("--call", default=None, help="run one tool and print its result as JSON")
    parser.add_argument("--args", default="{}", help="JSON arguments for --call")
    parser.add_argument("--list-tools", action="store_true", help="print the tool names, one a line")
    args = parser.parse_args(argv)
    if args.list_tools:
        for tool in TOOLS:
            print(tool.name)
        return 0
    if args.call:
        if args.call not in BY_NAME:
            print(f"no tool {args.call!r}", file=sys.stderr)
            return 2
        if args.endpoint:
            os.environ["CAPCRAFT_ENDPOINT"] = args.endpoint
        if args.token:
            os.environ["CAPCRAFT_TOKEN"] = args.token
        try:
            result = call_tool(args.call, json.loads(args.args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        except ToolError as error:
            print(f"capcraft: {error}", file=sys.stderr)
            return 1
    serve()
    return 0


if __name__ == "__main__":
    sys.exit(main())
