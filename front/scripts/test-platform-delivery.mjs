import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { runInNewContext } from "node:vm"
import ts from "typescript"

const source = readFileSync(new URL("../components/platform/PlatformDeliveryPanel.tsx", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
})
const flush = () => new Promise((resolve) => setImmediate(resolve))

class ApiResponseError extends Error {
  constructor(message, status, data) { super(message); this.status = status; this.data = data }
}

function harness(platform) {
  const slots = []
  const requests = []
  const effects = []
  let cursor = 0
  let first = true
  let savedMode = "CONFIRM"
  let status = "PENDING"
  let confirm = async () => { status = "SENT"; return { success: true, status } }
  const session = { taskStatusKnown: true, isDelivering: false, safetyHoldReason: "", isLoggedIn: true, syncTaskStatus: async () => {} }
  const exports = {}
  const react = {
    useState: (initial) => {
      const index = cursor++
      if (first) slots[index] = initial
      return [slots[index], (next) => { slots[index] = typeof next === "function" ? next(slots[index]) : next }]
    },
    useRef: (current) => {
      const index = cursor++
      if (first) slots[index] = { current }
      return slots[index]
    },
    useCallback: (callback) => callback,
    useEffect: (effect) => { if (first) effects.push(effect) },
  }
  runInNewContext(outputText, {
    exports, AbortController, Error, console,
    require: (name) => {
      if (name === "react") return react
      if (name === "react/jsx-runtime") return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) }
      if (name === "@/lib/api") return {
        API_BASE: "http://local.test", ApiResponseError,
        requestJson: async (url, options = {}, timeout) => {
          requests.push({ url, options, timeout })
          const base = `http://local.test/api/${platform}`
          assert.ok(url.startsWith(base), "Actions must stay scoped to the selected platform")
          const path = url.slice(base.length)
          if (path === "/delivery-settings") {
            if (options.method === "PUT") savedMode = JSON.parse(options.body).mode
            return { success: true, mode: savedMode }
          }
          if (path === "/deliveries") return { success: true, items: [{ id: "draft", platform, status, job: { title: "Fixture engineer", company: "Fixture company", salary: "10K" } }] }
          if (path === "/deliveries/draft/confirm") return confirm()
          if (path === "/deliveries/draft/dismiss") { status = "DISMISSED"; return { success: true } }
          if (path === "/resume") { session.safetyHoldReason = ""; return { success: true } }
          throw new Error(`Unexpected request: ${path}`)
        },
      }
      if (name === "react-icons/bi") return { BiSend: "Icon" }
      if (name.endsWith("/button")) return { Button: "Button" }
      if (name.endsWith("/dialog")) return { Dialog: "Dialog" }
      if (name.endsWith("/section-card")) return { SectionCard: "SectionCard" }
      throw new Error(`Unexpected import: ${name}`)
    },
  })
  function render() {
    cursor = 0
    const tree = exports.PlatformDeliveryPanel({ platform, session })
    if (first) { first = false; for (const effect of effects) effect() }
    return tree
  }
  function all(tree, predicate) {
    if (Array.isArray(tree)) return tree.flatMap((node) => all(node, predicate))
    if (!tree || typeof tree !== "object") return []
    return [...(predicate(tree) ? [tree] : []), ...all(tree.props?.children, predicate), ...all(tree.props?.actions, predicate)]
  }
  const find = (predicate) => all(render(), predicate)[0]
  const button = (label) => find((node) => node.type === "Button" && node.props.children === label)
  return { render, find, button, requests, session, setConfirm: (fn) => { confirm = fn }, setStatus: (value) => { status = value } }
}

for (const platform of ["liepin", "51job", "zhilian"]) {
  const h = harness(platform)
  h.render()
  await flush()
  assert.equal(h.button("确认投递").props.disabled, undefined)
  const autoRadio = h.find((node) => node.type === "input" && node.props.type === "radio" && node.props.checked === false)
  autoRadio.props.onChange()
  assert.equal(h.requests.filter((r) => r.options.method === "PUT").length, 0, "Changing a radio is not a saved mode or a send")
  h.button("保存发送模式").props.onClick()
  await flush()
  const saved = h.requests.filter((r) => r.options.method === "PUT")
  assert.equal(saved.length, 1)
  assert.equal(JSON.parse(saved[0].options.body).mode, "AUTO")
  assert.equal(h.requests.some((r) => r.url.endsWith("/confirm")), false)

  h.button("确认投递").props.onClick()
  assert.equal(h.find((node) => node.type === "Dialog").props.open, true)
  let finish
  h.setConfirm(() => new Promise((resolve) => { finish = resolve }))
  const send = h.button("确认并投递").props.onClick
  send()
  send()
  assert.equal(h.requests.filter((r) => r.url.endsWith("/confirm")).length, 1, "Double clicks must send one request")
  h.setStatus("SENT")
  finish({ success: true, status: "SENT" })
  await flush()
  assert.equal(h.button("确认投递"), undefined, "A successful job is not offered again")
}

for (const failure of ["transport", "busy"]) {
  const h = harness("liepin")
  h.render()
  await flush()
  h.setConfirm(async () => {
    if (failure === "busy") throw new ApiResponseError("Busy", 409, { success: false, status: "busy" })
    throw new Error("Request timed out")
  })
  h.button("确认投递").props.onClick()
  h.button("确认并投递").props.onClick()
  await flush()
  assert.equal(!!h.button("确认投递").props.disabled, failure === "transport", "Uncertain transport failure locks sending, explicit busy does not")
  assert.equal(h.requests.filter((r) => r.url.endsWith("/confirm")).length, 1, "Errors must not retry writes")
}

const held = harness("51job")
held.session.safetyHoldReason = "Rate limit"
held.render()
await flush()
assert.equal(held.button("确认投递").props.disabled, true)
assert.equal(held.button("确认恢复（不自动启动）").props.disabled, true)
held.find((node) => node.type === "input" && node.props.type === "checkbox").props.onChange({ target: { checked: true } })
held.button("确认恢复（不自动启动）").props.onClick()
await flush()
assert.equal(held.requests.filter((r) => r.url.endsWith("/resume")).length, 1)
assert.equal(held.requests.some((r) => r.url.endsWith("/start") || r.url.endsWith("/confirm")), false, "Recovery cannot automatically resume sending")

const shell = readFileSync(new URL("../components/platform/PlatformShell.tsx", import.meta.url), "utf8")
assert.match(shell, /PlatformDeliveryPanel/)
console.log("Three-platform delivery UI: modes, platform isolation, explicit confirmation, single-flight, uncertain result locks and manual recovery passed")
