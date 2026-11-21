# CapCraft in DSH

[DSH](https://github.com/deepseek-ai/deepseek-harness) is a coding agent that runs on a phone, a laptop or a server. This page has two independent halves; you can use either alone.

1. **The bridge** gives the model CapCraft's tools. It is a small Python program that speaks MCP to the agent and the app's agent socket to CapCraft. No build step, no Rust, and it works in any MCP client.
2. **The dsh-go plugin** adds a status line, two composer capsules and a small CapCraft panel to the phone app's native interface. It is a nicety: it reads the app's address and drops a prompt into the composer. It never edits a project by itself.

Everything lives in [`scripts/dsh-bridge/`](../scripts/dsh-bridge/) and [`plugins/dsh-go-local-capcraft/`](../plugins/dsh-go-local-capcraft/).

## What you need

- CapCraft itself, with **Settings → Automation** opened once. That starts the loopback agent server and writes `automation.json` (address, token) into the app's files folder.
- `python3` (standard library only) for the bridge.
- For the plugin: dsh-go with a local engine, and the plugin installed (see below).

The bridge reads `automation.json` on **every** call, so a restarted app or a re-minted token is picked up without restarting anything. `CAPCRAFT_ENDPOINT=host:port` overrides the address and `CAPCRAFT_TOKEN` overrides the token. On a different machine than the phone, pull the file first, e.g. `adb pull /data/data/io.github.artcraftandroid.capcraft/files/automation.json`.

## The bridge

```sh
python3 scripts/dsh-bridge/capcraft_bridge.py --list-tools
python3 scripts/dsh-bridge/capcraft_bridge.py --call capcraft_status '{}'
python3 scripts/dsh-bridge/capcraft_bridge.py --call media_probe --args '{"path":"/sdcard/DCIM/clip.mp4"}'
```

It speaks MCP over stdio (newline-delimited JSON-RPC 2.0, protocol `2024-11-05`), so any client can start it:

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

In a DSH profile, add one insert entry to `cordis.patch.yml`. The `insert:`
list is what the loader wants; a bare `- id:` entry only *overrides* an entry
that already exists, and is rejected with `entry "mcp-capcraft" not found`:

```yaml
- insert:
    - id: mcp-capcraft
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: capcraft
        transport: stdio
        command: /usr/bin/python3
        args:
          - /sdcard/Projects/capcraft-android/scripts/dsh-bridge/capcraft_bridge.py
        toolCallTimeoutMs: 700000
```

Then restart the engine. The tools appear as `mcp__capcraft__*`. To check the
layer composes without booting:

```sh
dsh --profile web --dump-config | grep -A9 mcp-capcraft
```

The bridge is deliberately a **thin** client: one TCP connection per call, one request per connection, no session. That matches what the app's agent server does, and it means a stale connection can never wedge the editor.

### Tools

`capcraft_status` first; it tells you whether the app answers and which address it used. After that the names read like the app's API:

- projects: `project_list`, `project_create`, `project_open`, `project_save`,
  `project_close`, `project_get`, `project_document`, `project_set_video`
- media: `media_probe`, `media_import`, `media_import_sound`
- edits: `edit_apply` (any op-tagged command, times in seconds), `edit_undo`, `edit_redo`,
  plus one tool per timeline operation: `clip_add`, `clip_overlay`, `clip_audio`,
  `clip_sticker`, `title_add`, `clips_remove`, `clips_split`, `clip_trim`,
  `clips_move`, `clip_reorder`, `clip_stack`, `clip_arrange`, `clip_to_main`,
  `clip_to_overlay`, `clip_update`, `captions_add`
- catalogue: `catalogue_list`
- export: `export_start`, `export_status`, `export_wait`, `export_cancel`
- preview: `preview_frame` (a PNG image part, or `--args '{"output": ...}'` to save)

Times on the wire: **seconds** for `edit.apply` commands and `preview.frame`
(the engine's own units); **microseconds** for the `session.*` tools'
`atUs`/`durationUs`/`deltaUs`/`startUs` fields (the phone API's units). The view
every edit returns carries both (`startUs` and `start`).

### The skill

The `capcraft-agent` skill in this repo's harness profile tells an agent the
order and the pitfalls: probe first, open, import, add, trim/split/move,
titles, preview, export and wait. Load it when the user asks to edit a video.

## The dsh-go plugin

The plugin lives in this repository at `plugins/dsh-go-local-capcraft/` and is
linked into the dsh-go profile, so the app loads the repo copy and edits to it
are versioned with everything else:

```sh
dsh plugin --profile web add /sdcard/Projects/capcraft-android/plugins/dsh-go-local-capcraft
dsh plugin --profile web list    # shows local-capcraft@link:.../plugins/dsh-go-local-capcraft
```

`dsh plugin add` takes a path, so point it at the repo rather than copying the
folder into `$DSH_HOME/dsh-go/plugins/`. A copy there drifts from the repo the
moment either one is edited.

It contributes:

| Where | What |
| --- | --- |
| Status bar | `CapCraft · <project>` when the app answers, `CapCraft · offline` when it does not |
| Composer capsule | **Cut this** — reads the clipboard path and inserts a prepared edit brief |
| Composer capsule | **Edit brief** — inserts the general editing brief |
| Panel (session tab / features) | address, version, open project, and buttons to refresh, insert the brief, or copy the address |

The plugin only ever **reads** the app and **inserts text**. It has no edit tools of its own: the user sends the prompt, the model calls the CapCraft tools, and the result comes back in the chat. That is the whole design - the model is the editor's hands, the plugin is its dashboard.

Change the plugin's `lib/index.js` and restart the engine in the app's **Engine** page; the manifest is re-read without a restart.

## Trying it without the app

`mock_capcraft.py` is a stand-in that speaks the same socket protocol:

```sh
python3 scripts/dsh-bridge/mock_capcraft.py --port 7420 --token test-token-0123456789abcdef &
CAPCRAFT_ENDPOINT=127.0.0.1:7420 CAPCRAFT_TOKEN=test-token-0123456789abcdef \
  python3 scripts/dsh-bridge/capcraft_bridge.py --call capcraft_status '{}'
```

Two test scripts run the whole chain and assert the wire shapes:

```sh
python3 scripts/dsh-bridge/test_bridge.py   # bridge <-> mock, as MCP and as CLI
node scripts/dsh-bridge/test_plugin.mjs     # plugin <-> mock, with a fake dshGo
```

The second one needs no engine: it drives the plugin's handlers the way the app
does, and checks the strip's 24-character limit, the offline message, and the
inserted brief.

## Limits

- The bridge is line-based JSON, so a tool result larger than 4 MB is refused. `preview_frame` returns one PNG; that is the only large result.
- One call at a time. `export_wait` polls the job and returns progress, so a long export does not need a second call.
- The plugin polls every 10 s (panel) and 20 s (status bar). Each poll is one TCP connection; if CapCraft is closed, the plugin says so and stops retrying loudly.
- Neither half runs FFmpeg or touches a project file. Everything goes through the app's own API, so the same undo history and the same limits apply.
