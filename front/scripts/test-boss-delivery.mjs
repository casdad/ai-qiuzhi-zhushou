import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { runInNewContext } from "node:vm"
import ts from "typescript"

const source = readFileSync(new URL("../lib/boss-delivery.ts", import.meta.url), "utf8")
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
})
const exports = {}
runInNewContext(outputText, { exports, URL })
const { normalizeDeliveryMode, normalizeDelivery, isDeliveryStatus, bossJobUrl, DELIVERY_STATUS_LABEL } = exports

assert.equal(normalizeDeliveryMode("AUTO"), "AUTO")
for (const value of [undefined, null, "CONFIRM", "auto", "invalid"]) {
  assert.equal(normalizeDeliveryMode(value), "CONFIRM")
}
assert.equal(normalizeDelivery(null), null)
assert.equal(normalizeDelivery({ id: {} }), null)
const pending = normalizeDelivery({ id: 42, message: "preview", status: "PENDING", sendImgResume: true })
assert.equal(pending.id, "42")
assert.equal(pending.message, "preview")
assert.equal(pending.status, "PENDING")
assert.equal(pending.sendImgResume, true)
assert.equal(normalizeDelivery({ id: "unknown", status: "unexpected" }).status, "UNKNOWN")
assert.equal(normalizeDelivery({ id: "empty" }).status, "UNKNOWN")
assert.equal(normalizeDelivery({ id: "not-true", sendImgResume: "false" }).sendImgResume, false)
assert.equal(isDeliveryStatus("__proto__"), false)
assert.equal(isDeliveryStatus("toString"), false)
for (const status of ["PENDING", "IN_FLIGHT", "SENT", "UNKNOWN", "FAILED", "SKIPPED", "RESTRICTED", "DISMISSED"]) {
  assert.equal(isDeliveryStatus(status), true)
  assert.equal(typeof DELIVERY_STATUS_LABEL[status], "string")
}
assert.equal(bossJobUrl("https://www.zhipin.com/job_detail/test.html"), "https://www.zhipin.com/job_detail/test.html")
for (const value of ["javascript:alert(1)", "http://www.zhipin.com/", "https://evilzhipin.com/", "https://zhipin.com.evil.test/", "not-a-url"]) {
  assert.equal(bossJobUrl(value), undefined)
}
console.log("Boss delivery UI helpers: all assertions passed")
