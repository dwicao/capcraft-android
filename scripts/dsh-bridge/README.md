# dsh-bridge

A small MCP server that gives an AI agent CapCraft's editing tools, plus a stand-in
app to test against.

```
capcraft_bridge.py   MCP stdio server + a plain CLI (--call, --args, --list-tools)
mock_capcraft.py     a fake CapCraft that speaks the same socket protocol
test_bridge.py       end-to-end checks: bridge -> mock, as MCP and as CLI
test_plugin.mjs      drives the dsh-go plugin against the mock (node)
```

Standard library only; `python3` is the whole dependency.

## Run it as an MCP server

```sh
python3 capcraft_bridge.py
```

It prints nothing on stdout except MCP frames, so it can be wired straight into a
client:

```json
{
  "mcpServers": {
    "capcraft": {
      "command": "python3",
      "args": ["/sdcard/Projects/capcraft-android/scripts/dsh-bridge/capcraft_bridge.py"]
    }
  }
}
```

DSH users add one entry to `~/.dsh/profiles/web/cordis.patch.yml` and restart the
engine; see [`docs/DSH.md`](../../docs/DSH.md) in the repository root.

## Run it as a command

```sh
python3 capcraft_bridge.py --list-tools
python3 capcraft_bridge.py --call capcraft_status '{}'
python3 capcraft_bridge.py --call media_probe --args '{"path":"/sdcard/DCIM/VID_0001.mp4"}'
```

Exit code is `1` when the tool reports an error, so it composes with `&&` and
`set -e`. Arguments are JSON via `--args`; when omitted, `{}` is sent.

`--endpoint host:port` and `--token …` override what the bridge finds on its own.

## How it finds the app

1. `CAPCRAFT_ENDPOINT` / `CAPCRAFT_TOKEN` if set.
2. `automation.json` under `CAPCRAFT_CONFIG`, `$XDG_CONFIG_HOME`, `~/.config` or `~`
   (CapCraft writes it when Settings → Automation is opened; it holds the address,
   the token and the build).
3. `127.0.0.1:7420`, with whatever token the environment carries.

Every call re-reads the listing, so restarting the app or re-minting its token
needs no bridge restart.

## Wire protocol

One TCP connection per call. The first line is an `auth` frame carrying the token;
the second is the request. CapCraft answers one line per frame, and the bridge
closes the socket. Requests use the bare JSON shape (`{"method": …, …fields}`),
which is what the app's agent server documents; the reply carries `"id": null`.

Limits worth knowing: a line is capped at 4 MB, and a tool call that takes longer
than 30 s (or 700 s for the MCP client, set in the profile) is reported rather
than left hanging.

## Tools

`capcraft_status` first; it tells you whether the app is there and which project
is open. After that the names read like the API:

- projects: `project_list`, `project_create`, `project_open`, `project_save`,
  `project_close`, `project_get`, `project_document`, `project_set_video`
- media: `media_probe`, `media_import`, `media_import_sound`
- edits: `edit_apply`, `edit_undo`, `edit_redo`, `clip_add`, `clip_overlay`,
  `clip_audio`, `clip_sticker`, `title_add`, `clips_remove`, `clips_split`,
  `clip_trim`, `clips_move`, `clip_reorder`, `clip_update`, `captions_add`
- catalogue: `catalogue_list`
- export: `export_start`, `export_status`, `export_wait`, `export_cancel`
- preview: `preview_frame`
