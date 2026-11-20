// local-capcraft: a small dsh-go plugin that watches the CapCraft editor.
//
// It talks the same socket protocol as scripts/dsh-bridge/capcraft_bridge.py:
// a TCP connection, one `auth` line, then one JSON-RPC request line. No token
// is stored here; the address and token come from the app's automation.json
// (Settings > Automation shows and copies both).
//
// The plugin only reads and inserts text. It never edits a project by itself:
// every change is a prompt the user sends to the model, which then calls the
// CapCraft tools.

import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

export const name = 'local-capcraft'

const DEFAULT_HOST = '127.0.0.1'
const DEFAULT_PORT = 7420
const CONNECT_TIMEOUT_MS = 1500
const CALL_TIMEOUT_MS = 4000
const STRIP_INTERVAL_MS = 20_000
const PANEL_INTERVAL_MS = 10_000

// --- where CapCraft writes its address -------------------------------------
//
// The app's automation.json lives in its private folder, which the engine
// cannot read — and the app picks a random free port every launch. So the
// user pastes the address + token once (Settings > Automation > Copy), and
// the panel keeps them beside the auto-discovery below. Env vars and a
// readable automation.json still win when they exist.

let manual = { address: '', token: '' }

function manualEndpoint() {
  if (!manual.address) return null
  const parsed = parseEndpoint(manual.address)
  if (!parsed) return null
  return { ...parsed, token: manual.token || '', source: 'manual' }
}

function listingPaths() {
  const home = process.env.DSH_HOME ? path.dirname(process.env.DSH_HOME) : os.homedir()
  const roots = [
    process.env.CAPCRAFT_CONFIG,
    process.env.XDG_CONFIG_HOME,
    path.join(home, '.config'),
    home,
  ].filter(Boolean)
  const found = []
  for (const root of roots) {
    found.push(path.join(root, 'capcraft', 'automation.json'))
    found.push(path.join(root, 'automation.json'))
  }
  return found
}

function readListing() {
  for (const file of listingPaths()) {
    try {
      const listing = JSON.parse(fs.readFileSync(file, 'utf8'))
      if (listing && listing.version === 1 && typeof listing.address === 'string') {
        return { ...listing, source: file }
      }
    } catch {
      // A missing or half-written listing is normal; try the next one.
    }
  }
  return null
}

function parseEndpoint(text) {
  const value = String(text || '').trim()
  const match = /^(\[[^\]]+\]|[^:\s]+):(\d+)$/.exec(value)
  if (!match) return null
  const port = Number(match[2])
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  const host = match[1].startsWith('[') ? match[1].slice(1, -1) : match[1]
  return { host, port }
}

function resolve() {
  // The panel's manual entry is the most deliberate, most recent choice.
  const fromPanel = manualEndpoint()
  if (fromPanel) return fromPanel
  for (const text of [process.env.CAPCRAFT_ENDPOINT]) {
    if (!text) continue
    const parsed = parseEndpoint(text)
    if (parsed) return { ...parsed, token: process.env.CAPCRAFT_TOKEN || '', source: 'setting' }
  }
  const listing = readListing()
  if (listing) {
    const parsed = parseEndpoint(listing.address)
    if (parsed) {
      return {
        ...parsed,
        token: process.env.CAPCRAFT_TOKEN !== undefined ? process.env.CAPCRAFT_TOKEN : String(listing.token || ''),
        source: listing.source,
        version: listing.capcraft || '',
      }
    }
  }
  return { host: DEFAULT_HOST, port: DEFAULT_PORT, token: process.env.CAPCRAFT_TOKEN || '', source: 'default' }
}

// --- one request on the socket ----------------------------------------------

function call(method, params) {
  return new Promise((resolvePromise, reject) => {
    const target = resolve()
    const socket = net.createConnection({ host: target.host, port: target.port })
    let buffer = ''
    let settled = false
    const answer = { }

    const finish = (fn, value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket.destroy()
      fn(value)
    }
    const timer = setTimeout(() => {
      finish(reject, new Error(`CapCraft did not answer within ${CALL_TIMEOUT_MS} ms`))
    }, CALL_TIMEOUT_MS)

    socket.setNoDelay(true)
    socket.on('error', (error) => {
      finish(reject, new Error(`${error.message} (${target.host}:${target.port})`))
    })
    socket.on('close', () => {
      if (settled) return
      if (Object.prototype.hasOwnProperty.call(answer, 'reply')) return
      const auth = answer.auth
      if (auth && auth.error) {
        finish(reject, new Error('CapCraft refused the token; copy the new one from Settings > Automation'))
        return
      }
      finish(reject, new Error('CapCraft closed the connection before answering'))
    })
    socket.on('connect', () => {
      socket.write(`${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'auth', params: { token: target.token } })}\n`)
      socket.write(`${JSON.stringify({ jsonrpc: '2.0', id: 2, method, params })}\n`)
    })
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      let index = buffer.indexOf('\n')
      while (index >= 0) {
        const line = buffer.slice(0, index).trim()
        buffer = buffer.slice(index + 1)
        index = buffer.indexOf('\n')
        if (!line) continue
        let message
        try {
          message = JSON.parse(line)
        } catch {
          continue
        }
        if (message.id === 1) {
          answer.auth = message
          if (message.error) {
            finish(reject, new Error('CapCraft refused the token; copy the new one from Settings > Automation'))
            return
          }
        } else if (message.id === 2 || message.id === null) {
          answer.reply = message
          if (message.error) {
            finish(reject, new Error(message.error.message || 'CapCraft refused the request'))
            return
          }
          finish(resolvePromise, message.result)
          return
        }
      }
    })
  })
}

// --- what the strip and the panel show --------------------------------------

// The strip has 24 characters; a long project name must not be cut mid-word.
function short(text, max = 24) {
  const value = String(text || '')
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`
}

function zh(locale) {
  return !String(locale || '').startsWith('en')
}

async function snapshot() {
  const target = resolve()
  try {
    const version = await call('version', {})
    const info = (version && (version.build || version.capcraft)) || ''
    let project = ''
    try {
      const projects = await call('project.list', {})
      const first = Array.isArray(projects) ? projects[0] : null
      if (first) project = first.name || first.path || ''
    } catch {
      // A project listing is a bonus; the version already proves the app answers.
    }
    return {
      connected: true,
      address: `${target.host}:${target.port}`,
      version: info,
      project,
      source: target.source,
      note: project ? `project ${project}` : 'no project open',
    }
  } catch (error) {
    return {
      connected: false,
      address: `${target.host}:${target.port}`,
      version: '',
      project: '',
      source: target.source,
      note: error && error.message ? error.message : 'not reachable',
    }
  }
}

const BRIEF = [
  'Use the CapCraft tools to edit a video:',
  '1. capcraft_status to see whether the app answers',
  '2. project_list, then project_open the project',
  '3. media_probe the source clips, then media_import them',
  '4. clip_add / clip_overlay / clip_audio the media onto the timeline',
  '5. clip_trim, clips_split, clips_move, clip_update to cut',
  '6. title_add or captions_add for text',
  '7. preview_frame to check a frame, then export_start and export_wait',
  'Report the output path when the export finishes.',
].join('\n')

function clipBrief(value) {
  const target = String(value || '').trim()
  if (!target) return BRIEF
  return [
    `Use the CapCraft tools to edit ${target}:`,
    '1. media_probe it first, then project_open a project and media_import it',
    '2. clip_add it to the timeline and cut about 30 seconds, vertical 1080x1920',
    '3. add Chinese captions with captions_add',
    '4. preview_frame, then export_start and export_wait',
    'Report the output path.',
  ].join('\n')
}

export function apply(ctx) {
  ctx.inject(['dshGo'], (ctx) => {
    const dshGo = ctx.dshGo

    dshGo.on('capcraft-clip', (scope, event) => {
      const language = zh(event && event.locale)
      const value = (scope.clipboard || '').trim()
      if (!value) return { toast: language ? '剪贴板里没有素材路径' : 'The clipboard has no clip path' }
      return { insert: clipBrief(value) }
    })

    dshGo.on('capcraft-prompt', () => ({ insert: BRIEF }))

    dshGo.strip('capcraft-status', (scope, emit, caps) => {
      const language = zh(caps && caps.locale)
      let alive = true
      const tick = async () => {
        if (!alive) return
        const state = await snapshot()
        if (!alive) return
        if (state.connected) {
          emit({
            text: short(language ? `CapCraft · ${state.project || '已连接'}` : `CapCraft · ${state.project || 'connected'}`),
            tone: 'good',
          })
        } else {
          emit({ text: language ? 'CapCraft · 未连接' : 'CapCraft · offline', tone: 'warn' })
        }
      }
      tick()
      const timer = setInterval(tick, STRIP_INTERVAL_MS)
      return () => {
        alive = false
        clearInterval(timer)
      }
    })

    dshGo.panel('capcraft', {
      view: (scope, caps) => {
        const language = zh(caps && caps.locale)
        const connected = { $: 'data.connected' }
        const summary = [
          {
            type: 'metric',
            label: language ? '编辑器' : 'Editor',
            value: { $: 'data.connectedText' },
            tone: { $: 'data.tone' },
            caption: { $: 'data.address' },
          },
          {
            type: 'keyValue',
            items: [
              [language ? '版本' : 'Version', { $: 'data.version' }],
              [language ? '项目' : 'Project', { $: 'data.project' }],
              [language ? '地址来源' : 'Address from', { $: 'data.source' }],
            ],
          },
          { type: 'callout', text: { $: 'data.note' }, tone: connected ? 'good' : 'warn' },
          {
            type: 'actions',
            items: [
              { label: language ? '刷新' : 'Refresh', action: 'refresh', icon: 'refresh' },
              { label: language ? '插入剪辑指令' : 'Edit brief', action: 'prompt', icon: 'terminal', style: 'primary' },
              { label: language ? '复制地址' : 'Copy address', action: 'copy', icon: 'copy' },
            ],
          },
          {
            type: 'text',
            text: language
              ? '改动都通过对话里的提示词完成：点「插入剪辑指令」，改两句，再发送。'
              : 'Every change goes through the chat: tap "Edit brief", adjust it, then send.',
            tone: 'muted',
          },
        ]
        if (!caps || !Array.isArray(caps.components) || !caps.components.includes('form')) {
          return {
            v: 1, type: 'stack', title: 'CapCraft', children: summary.concat([{
              type: 'text',
              text: language
                ? '此 App 版本不支持手动填地址：把 CapCraft Automation 页的地址和令牌告诉助手，填进 CAPCRAFT_ENDPOINT / CAPCRAFT_TOKEN。'
                : 'This app version has no manual address form: give the address and token from CapCraft Automation to the assistant for CAPCRAFT_ENDPOINT / CAPCRAFT_TOKEN.',
              tone: 'muted',
            }]),
          }
        }
        return {
          v: 2, type: 'stack', title: 'CapCraft', children: summary.concat([{
            type: 'form', submit: 'saveEndpoint', submitLabel: language ? '连接' : 'Connect',
            children: [
              { type: 'textField', label: language ? '地址 (host:port)' : 'Address (host:port)', bind: 'data.form.address', placeholder: '127.0.0.1:41077', required: true, maxLength: 64 },
              { type: 'textField', label: language ? '令牌' : 'Token', bind: 'data.form.token', placeholder: language ? '从 Automation 页复制' : 'Copy from the Automation page', required: true, maxLength: 256 },
            ],
          }]),
        }
      },
      data: (scope, emit, caps) => {
        const language = zh(caps && caps.locale)
        let alive = true
        const tick = async () => {
          if (!alive) return
          const state = await snapshot()
          if (!alive) return
          emit({
            connected: state.connected,
            connectedText: state.connected ? (language ? '已连接' : 'connected') : (language ? '未连接' : 'offline'),
            tone: state.connected ? 'good' : 'warn',
            address: state.address,
            version: state.version || '—',
            project: state.project || (language ? '没有打开的项目' : 'no project open'),
            source: state.source,
            note: state.connected
              ? (language ? `连上了 ${state.address}` : `Reached ${state.address}`)
              : (language ? `连不上 ${state.address}。在 CapCraft 里打开 Settings > Automation，把下面的地址和令牌填好点连接。` : `Cannot reach ${state.address}. Open Settings > Automation in CapCraft and fill in the address and token below.`),
            form: {
              address: manual.address || state.address,
              token: manual.token || '',
            },
          })
        }
        tick()
        const timer = setInterval(tick, PANEL_INTERVAL_MS)
        return () => {
          alive = false
          clearInterval(timer)
        }
      },
      actions: {
        refresh: (scope, event) => ({ refresh: true, toast: zh(event && event.locale) ? '已刷新' : 'Refreshed' }),
        prompt: (scope, event) => ({ insert: BRIEF, toast: zh(event && event.locale) ? '已放进输入框' : 'Inserted into the composer' }),
        copy: async () => {
          const target = resolve()
          return { copy: `${target.host}:${target.port}`, toast: '地址已复制' }
        },
        saveEndpoint: (scope, event) => {
          const language = zh(event && event.locale)
          const values = (event && event.value && event.value.values) || {}
          const address = String(values['data.form.address'] || '').trim()
          const token = String(values['data.form.token'] || '').trim()
          if (!parseEndpoint(address)) throw new Error(language ? '地址要是 host:port 的样子' : 'Address must look like host:port')
          if (token.length < 8) throw new Error(language ? '令牌太短了' : 'That token is too short')
          manual = { address, token }
          return {
            patch: { form: { address, token }, source: 'manual' },
            refresh: true,
            toast: language ? '已保存，正在连接…' : 'Saved, connecting…',
          }
        },
      },
    })
  })
}
