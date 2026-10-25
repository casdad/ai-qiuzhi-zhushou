import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { runInNewContext } from "node:vm"
import ts from "typescript"

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8")
const jsx = (type, props) => ({ type, props })
const react = {
  useCallback: (callback) => callback,
  useEffect: () => {},
  useRef: (current) => ({ current }),
  useState: (initial) => [initial, () => {}],
}
function load(path, modules = {}, globals = {}) {
  const { outputText } = ts.transpileModule(read(path), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  })
  const exports = {}
  runInNewContext(outputText, {
    exports, console, URL,
    require: (name) => {
      if (Object.hasOwn(modules, name)) return modules[name]
      if (name === "react") return react
      if (name === "react/jsx-runtime") return { jsx, jsxs: jsx, Fragment: "Fragment" }
      if (name === "@/lib/api") return { API_BASE: "http://local.test" }
      if (name === "react-icons/bi") return {}
      if (name.startsWith("@/components/ui/")) return new Proxy({}, { get: (_, key) => key })
      throw new Error(`Unexpected module: ${name}`)
    },
    ...globals,
  })
  return exports
}
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes)
  if (!tree || typeof tree !== "object") return []
  return [tree, ...nodes(tree.props?.children)]
}

const profiles = load("../app/boss/components/useJobProfiles.ts")
const delivery = load("../lib/boss-delivery.ts")
const saved = profiles.normalizeProfile({
  id: 7, name: "Vision", enabled: 1, enableAiRelevance: 1,
  jobIntent: "Computer vision, deep learning and object detection",
  aiIntroduce: "Existing Python and PyTorch projects", aiPrompt: "Prefer internships",
})
const body = profiles.buildProfileBody({ ...saved, jobIntent: `  ${saved.jobIntent}  ` })
assert.equal(body.jobIntent, saved.jobIntent)
assert.equal(body.aiIntroduce, saved.aiIntroduce, "Full profile saves must preserve the existing technical stack")
assert.equal(body.aiPrompt, saved.aiPrompt)
assert.equal(profiles.buildProfileBody({ ...saved, jobIntent: "  " }).jobIntent, "")
assert.equal(profiles.normalizeProfile({ name: "Legacy" }).jobIntent, "")
assert.equal(delivery.BOSS_DELIVERY_INTERVAL_SECONDS, 60)
assert.equal(delivery.BOSS_DELIVERY_INTERVAL_MAX_SECONDS, 120)
assert.equal(delivery.BOSS_DELIVERY_TIMEOUT_MS, 240_000)

const requests = []
let mirrored = true
let maximumMirrored = true
let dirty = false
let notified = false
function page() {
  let stateIndex = 0
  const states = [{}, [], false, "CONFIRM", "CONFIRM", false, 0]
  const modules = {
    react: { ...react, useState: () => [states[stateIndex++], () => {}] },
    "@/app/boss/components/useJobProfiles": {
      ...profiles,
      useJobProfiles: () => ({
        profiles: [saved], activeProfile: saved, profileDraft: { ...saved, jobIntent: dirty ? "New intent" : saved.jobIntent },
        saveProfile: async () => saved,
      }),
    },
    "@/lib/api": {
      API_BASE: "http://local.test",
      requestJson: async (url, options) => {
        const body = JSON.parse(options.body)
        requests.push({ url, body })
        if (!mirrored) return { deliveryMode: body.deliveryMode }
        if (!maximumMirrored) return { ...body, waitTimeMax: undefined }
        return body
      },
    },
    "@/lib/boss-delivery": delivery,
    "@/components/platform/usePlatformSession": { usePlatformSession: () => ({ notify: () => { notified = true } }) },
    "@/components/platform/PlatformShell": { PlatformShell: "PlatformShell" },
    "@/app/boss/analysis/AnalysisContent": { default: "AnalysisContent" },
  }
  for (const name of ["BlacklistCard", "DeliveryModeCard", "DeliveryQueuePanel", "ProfileCard", "RecommendationPanel"]) {
    modules[`@/app/boss/components/${name}`] = { [name]: name }
  }
  return load("../app/boss/page.tsx", modules).default()
}
const tree = page()
assert.deepEqual(nodes(tree).map((node) => node.type).filter((type) => ["DeliveryModeCard", "RecommendationPanel", "ProfileCard"].includes(type)),
  ["DeliveryModeCard", "RecommendationPanel", "ProfileCard"])
const panelProps = nodes(tree).find((node) => node.type === "RecommendationPanel").props
assert.equal(panelProps.jobIntent, saved.jobIntent)
assert.equal(panelProps.profileId, saved.id)
assert.equal(await tree.props.onSave(), true)
assert.equal(requests[0].body.jobIntent, saved.jobIntent)
assert.equal(requests[0].body.waitTime, 60)
assert.equal(requests[0].body.waitTimeMax, 120)
maximumMirrored = false
assert.equal(await page().props.onSave(), false, "A backend that ignores the maximum must not report a saved random range")
maximumMirrored = true
dirty = true
assert.equal(page().props.startDisabled, true, "Unsaved intent must not silently use old scoring preferences")
mirrored = false
assert.equal(await page().props.onSave(), false, "An old backend must not claim it saved the new fields")
assert.equal(notified, true)

let changed
let resolveFetch
const recommendationRequests = []
const { RecommendationPanel } = load("../app/boss/components/RecommendationPanel.tsx", {
  "@/lib/boss-delivery": delivery,
}, {
  fetch: (url, options) => {
    recommendationRequests.push({ url, body: JSON.parse(options.body) })
    return new Promise((resolve) => { resolveFetch = resolve })
  },
})
const panel = RecommendationPanel({ ...panelProps, onJobIntentChange: (value) => { changed = value } })
const input = nodes(panel).find((node) => node.type === "Textarea")
assert.equal(input.props.value, saved.jobIntent)
assert.equal(nodes(panel).filter((node) => node.type === "Button").length, 0)
assert.ok(!read("../app/boss/components/RecommendationPanel.tsx").includes("\u7406\u89e3\u5e76\u627e\u5c97\u4f4d"))
input.props.onChange({ target: { value: "New intent" } })
assert.equal(changed, "New intent")
const key = { ctrlKey: true, key: "Enter", nativeEvent: { isComposing: false }, preventDefault: () => {} }
input.props.onKeyDown(key)
input.props.onKeyDown(key)
assert.equal(recommendationRequests.length, 1, "Repeated shortcuts must not start concurrent scoring calls")
assert.equal(recommendationRequests[0].body.query, saved.jobIntent)
assert.equal(recommendationRequests[0].body.profileId, saved.id)
resolveFetch({ ok: true, json: async () => [] })
await new Promise((resolve) => setImmediate(resolve))

console.log("Boss job intent: profile round-trip, save payload, layout, shortcut and interval assertions passed")
