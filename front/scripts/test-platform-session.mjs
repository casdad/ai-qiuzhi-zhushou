import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { runInNewContext } from "node:vm"
import ts from "typescript"

const source = readFileSync(new URL("../components/platform/usePlatformSession.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
})

const loginFlags = {
  boss: "bossLoggedIn",
  liepin: "liepinLoggedIn",
  "51job": "job51LoggedIn",
  zhilian: "zhilianLoggedIn",
}

for (const [platform, flag] of Object.entries(loginFlags)) {
  const requests = []
  const cleanups = []
  let onSnapshot
  const stateChanges = []
  let unsubscribed = false
  const exports = {}
  const react = {
    useCallback: (callback) => callback,
    useEffect: (effect) => {
      const cleanup = effect()
      if (cleanup) cleanups.push(cleanup)
    },
    useRef: (current) => ({ current }),
    useState: (initial) => [initial, (value) => stateChanges.push(value)],
  }
  runInNewContext(outputText, {
    exports,
    require: (name) => {
      if (name === "react") return react
      if (name === "@/lib/api") return {
        API_BASE: "http://local.test",
        requestJson: async (...args) => {
          requests.push(args)
          return { success: false, message: "Still running" }
        },
      }
      if (name === "@/lib/login-status") {
        return {
          subscribeLoginStatus: (listener) => {
            onSnapshot = listener
            return () => { unsubscribed = true }
          },
          refreshLoginStatus: () => {},
        }
      }
      throw new Error(`Unexpected module: ${name}`)
    },
    window: {},
    console,
    fetch: async (...args) => {
      requests.push(args)
      throw new Error("No network request expected")
    },
  })

  const session = exports.usePlatformSession(platform, { trackTask: false })
  onSnapshot({ [flag]: true })
  onSnapshot({ [flag]: false })
  onSnapshot({ [flag]: true })
  await session.saveCookie()
  assert.equal(requests.length, 0, `${platform}: login snapshots and compatibility save must not persist credentials`)
  assert.ok(stateChanges.includes(true), `${platform}: live login state must still update`)
  stateChanges.length = 0
  await session.logout()
  assert.equal(requests.length, 1, "Logout must be attempted only once")
  assert.ok(!stateChanges.includes(false), "Failed logout must not claim login/task state was cleared")
  assert.equal(stateChanges.at(-1).tone, "danger")
  for (const cleanup of cleanups) cleanup()
  assert.equal(unsubscribed, true, `${platform}: login subscription must close on unmount`)
}

for (const platform of Object.keys(loginFlags)) {
  const page = readFileSync(new URL(`../app/${platform}/page.tsx`, import.meta.url), "utf8")
  assert.doesNotMatch(page, /session\.saveCookie\s*\(/, `${platform}: config save must not persist credentials`)
  assert.match(page, /session=\{session\}/, `${platform}: page and shell must share notices and task state`)
}

const shell = readFileSync(new URL("../components/platform/PlatformShell.tsx", import.meta.url), "utf8")
assert.doesNotMatch(shell, /=\s*usePlatformSession\(/, "Shell must not duplicate its page's session hook")
assert.doesNotMatch(source, /createSSEWithBackoff|new EventSource/, "Login tracking must not occupy a permanent connection")

console.log("Platform session: fresh-login policy assertions passed for all 4 platforms")
