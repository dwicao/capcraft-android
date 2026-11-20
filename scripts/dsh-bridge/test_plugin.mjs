#!/usr/bin/env node
// Drives the local-capcraft plugin the way dsh-go does: a fake ctx, a fake dshGo,
// and the real mock app behind it. Verifies the socket layer, the strip text and
// the panel data without needing the app or the engine.
//
//   node test_plugin.mjs

import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
// The plugin ships in this repo under plugins/dsh-go-local-capcraft.
const PLUGIN = process.env.CAPCRAFT_PLUGIN
  ? path.resolve(process.env.CAPCRAFT_PLUGIN)
  : path.resolve(HERE, '..', '..', 'plugins', 'dsh-go-local-capcraft', 'lib', 'index.js')

const checks = []
function check(label, condition, detail = '') {
  checks.push({ label, ok: Boolean(condition), detail })
}

function freePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer()
    probe.on('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address()
      probe.close(() => resolve(port))
    })
  })
}

const LONG_PROJECT = 'A very long project name that will not fit the strip'

function startMock() {
  return new Promise((resolve, reject) => {
    const child = spawn('python3',
      [path.join(HERE, 'mock_capcraft.py'), '--port', '0', '--project', LONG_PROJECT], {
      stdio: ['ignore', 'pipe', 'inherit'],
    })
    let buffer = ''
    const timer = setTimeout(() => reject(new Error('mock did not start')), 8000)
    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString()
      const line = buffer.split('\n')[0]
      if (!line || !line.includes(':')) return
      clearTimeout(timer)
      const token = ['--token', process.env.MOCK_TOKEN || 'test-token-0123456789abcdef']
      resolve({ child, address: line.trim(), token: token[1] })
    })
  })
}

function loadPlugin() {
  const handlers = { actions: new Map(), strips: new Map(), panels: new Map() }
  const dshGo = {
    on: (id, handler) => handlers.actions.set(id, handler),
    strip: (id, factory) => handlers.strips.set(id, factory),
    panel: (id, spec) => handlers.panels.set(id, spec),
  }
  const ctx = { inject: (names, callback) => { assert.deepEqual(names, ['dshGo']); callback(ctx) }, dshGo }
  return import(PLUGIN).then((module) => {
    module.apply(ctx)
    return { module, handlers }
  })
}

async function readPanel(handlers, locale) {
  const panel = handlers.panels.get('capcraft')
  assert.ok(panel, 'panel registered')
  let latest = null
  const stop = panel.data({}, (state) => { latest = state }, { locale })
  await new Promise((resolve) => setTimeout(resolve, 400))
  if (typeof stop === 'function') stop()
  return latest
}

async function readStrip(handlers, locale) {
  const factory = handlers.strips.get('capcraft-status')
  assert.ok(factory, 'strip registered')
  let latest = null
  const stop = factory({}, (state) => { latest = state }, { locale })
  await new Promise((resolve) => setTimeout(resolve, 400))
  if (typeof stop === 'function') stop()
  return latest
}

const mock = await startMock()
process.env.CAPCRAFT_ENDPOINT = mock.address
process.env.CAPCRAFT_TOKEN = mock.token

try {
  const { module, handlers } = await loadPlugin()

  check('plugin name matches its package', module.name === 'local-capcraft')
  check('one strip, two actions, one panel',
    handlers.strips.size === 1 && handlers.actions.size === 2 && handlers.panels.size === 1)

  const state = await readPanel(handlers, 'zh-CN')
  check('panel sees the app', state && state.connected === true, JSON.stringify(state))
  check('panel reports the address it used', state && state.address === mock.address)
  check('panel reports the build the app sent', state && /android/.test(String(state.version)))

  const strip = await readStrip(handlers, 'zh-CN')
  check('strip reads as connected', strip && strip.tone === 'good' && /CapCraft/.test(strip.text), JSON.stringify(strip))
  check('strip stays inside 24 characters', strip && strip.text.length <= 24, `${strip && strip.text.length}`)

  const brief = handlers.actions.get('capcraft-clip')({ clipboard: '/sdcard/DCIM/VID_0001.mp4' }, { locale: 'en-US' })
  check('clip capsule inserts a brief naming the file', /VID_0001\.mp4/.test(brief.insert), brief.insert.split('\n')[0])
  const empty = handlers.actions.get('capcraft-clip')({ clipboard: '   ' }, { locale: 'zh-CN' })
  check('empty clipboard asks for a path instead of inserting', !empty.insert && Boolean(empty.toast), JSON.stringify(empty))
  const prompt = handlers.actions.get('capcraft-prompt')({}, { locale: 'zh-CN' })
  check('prompt capsule inserts the general brief', /CapCraft tools/.test(prompt.insert))

  // Now point at a port where nothing listens.
  const port = await freePort()
  process.env.CAPCRAFT_ENDPOINT = `127.0.0.1:${port}`
  const down = await readPanel(handlers, 'en-US')
  check('panel says offline when the app is away', down && down.connected === false, JSON.stringify(down))
  const downStrip = await readStrip(handlers, 'en-US')
  check('strip says offline when the app is away',
    downStrip && downStrip.tone === 'warn' && /offline/.test(downStrip.text), JSON.stringify(downStrip))

  // The manual endpoint form: validation, then save + refresh.
  // A stale env override must not win over the panel's fresh choice.
  process.env.CAPCRAFT_ENDPOINT = `127.0.0.1:${port}`
  process.env.CAPCRAFT_TOKEN = 'stale'
  const actions = handlers.panels.get('capcraft').actions
  let threw = ''
  try {
    actions.saveEndpoint({}, { locale: 'zh-CN', value: { values: { 'data.form.address': 'nope', 'data.form.token': 'short' } } })
  } catch (e) { threw = String((e && e.message) || e) }
  check('bad address is refused in Chinese', /host:port/.test(threw), threw)
  const saved = actions.saveEndpoint({}, { locale: 'en-US', value: { values: { 'data.form.address': mock.address, 'data.form.token': mock.token } } })
  check('save returns a refresh', saved && saved.refresh === true, JSON.stringify(saved))
  const manual = await readPanel(handlers, 'zh-CN')
  check('manual endpoint connects', manual && manual.connected === true && manual.source === 'manual', JSON.stringify(manual))
} finally {
  mock.child.kill()
}

const failed = checks.filter((c) => !c.ok)
for (const c of checks) console.log(`${c.ok ? 'ok  ' : 'FAIL'} ${c.label}${c.ok || !c.detail ? '' : ` — ${c.detail}`}`)
if (failed.length) {
  console.log(`\n${failed.length} FAILURES`)
  process.exit(1)
}
console.log('\nall plugin checks passed')
