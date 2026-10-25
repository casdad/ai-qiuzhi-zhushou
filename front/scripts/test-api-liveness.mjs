import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { runInNewContext } from "node:vm"
import ts from "typescript"

function load(path, globals) {
  const source = readFileSync(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  })
  const exports = {}
  runInNewContext(outputText, { exports, AbortController, console, ...globals })
  return exports
}

let calls = 0
let signal
let response
const api = load("../lib/api.ts", {
  process: { env: {} }, setTimeout, clearTimeout,
  fetch: (_url, options) => {
    calls++
    signal = options.signal
    return response()
  },
})

response = () => new Promise(() => {})
await assert.rejects(api.requestJson("http://local.test/save", { method: "PUT" }, 15), /请求超时/)
assert.equal(signal.aborted, true, "A stalled request must release its browser connection")
assert.equal(calls, 1, "Timed-out writes must never be retried automatically")

response = async () => ({ ok: true, json: () => new Promise(() => {}) })
await assert.rejects(api.requestJson("http://local.test/save", {}, 15), /请求超时/)
assert.equal(signal.aborted, true, "The response body is included in the deadline")

response = async () => ({ ok: false, status: 409, json: async () => ({ message: "Busy, not logged out" }) })
await assert.rejects(api.requestJson("http://local.test/logout"), /Busy, not logged out/)
assert.equal(calls, 3)

await assert.rejects(api.requestJson("http://local.test/confirm"), (error) =>
  error instanceof api.ApiResponseError && error.status === 409 && error.data.message === "Busy, not logged out")

response = async () => ({ ok: true, json: async () => ({ saved: true }) })
assert.equal((await api.requestJson("http://local.test/save")).saved, true)

const listeners = new Map()
const timers = new Map()
const pending = []
let timerId = 0
const document = {
  visibilityState: "visible",
  addEventListener: (name, handler) => listeners.set(name, handler),
  removeEventListener: (name) => listeners.delete(name),
}
const poller = load("../lib/login-status.ts", {
  document, window: document,
  setTimeout: (callback) => { timers.set(++timerId, callback); return timerId },
  clearTimeout: (id) => timers.delete(id),
  require: (name) => {
    assert.equal(name, "@/lib/api")
    return {
      API_BASE: "http://local.test",
      requestJson: (url, options) => {
        assert.equal(url, "http://local.test/api/jobs/login-status")
        assert.equal(options.cache, "no-store")
        return new Promise((resolve, reject) => pending.push({ resolve, reject, signal: options.signal }))
      },
    }
  },
})
const flush = async () => { await new Promise((resolve) => setImmediate(resolve)) }
const first = []
const second = []
const offFirst = poller.subscribeLoginStatus((data) => first.push(data))
const offSecond = poller.subscribeLoginStatus((data) => second.push(data))
assert.equal(pending.length, 1, "Multiple consumers must share one in-flight login request")
const loggedIn = { bossLoggedIn: true, liepinLoggedIn: false, job51LoggedIn: false, zhilianLoggedIn: false }
pending.shift().resolve(loggedIn)
await flush()
assert.equal(first.at(-1).bossLoggedIn, true)
assert.equal(second.at(-1).bossLoggedIn, true)
assert.equal(timers.size, 1, "Only one timer is needed per tab")

document.visibilityState = "hidden"
listeners.get("visibilitychange")()
assert.equal(timers.size, 0)
assert.equal(pending.length, 0, "Hidden pages must not poll")
document.visibilityState = "visible"
listeners.get("visibilitychange")()
assert.equal(pending.length, 1, "Returning to the tab immediately refreshes login status")
pending.shift().reject(new Error("Offline"))
await flush()
assert.equal(first.at(-1), null, "Connection errors must not leave a stale authenticated state")

poller.refreshLoginStatus()
const stale = pending.shift()
offFirst()
assert.equal(stale.signal.aborted, false, "Another subscriber still needs the request")
offSecond()
assert.equal(stale.signal.aborted, true)
assert.equal(timers.size, 0)
assert.equal(listeners.size, 0)
const third = []
const offThird = poller.subscribeLoginStatus((data) => third.push(data))
stale.resolve(loggedIn)
await flush()
assert.equal(third.length, 0, "A previous mount's late response must not overwrite the new one")
pending.shift().resolve({ ...loggedIn, bossLoggedIn: false })
await flush()
assert.equal(third.at(-1).bossLoggedIn, false)
offThird()

console.log("API liveness: queue/body deadlines, no write retries, shared polling, hidden-tab cleanup and stale response tests passed")
