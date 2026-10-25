// Run after `next build` and copy-dist; every browser request is answered locally, including API calls.
import assert from "node:assert/strict"
import { readFile, stat, mkdir } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..")
const dist = path.join(root, "src/main/resources/dist")
const driver = path.join(root, "build/patchright-driver/package/index.mjs")
const { chromium } = await import(pathToFileURL(driver).href)
const browser = await chromium.launch({ channel: "chrome", headless: true })
const screenshots = path.join(root, "tmp/three-platform-delivery-ui")
await mkdir(screenshots, { recursive: true })

try {
  for (const platform of ["liepin", "51job", "zhilian"]) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, serviceWorkers: "block" })
    const writes = []
    const errors = []
    let mode = "CONFIRM"
    let status = "PENDING"
    await context.route("**/*", async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const headers = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,PUT,OPTIONS", "access-control-allow-headers": "content-type" }
      if (request.method() === "OPTIONS") return route.fulfill({ status: 204, headers })
      if (url.pathname.startsWith("/api/")) {
        if (request.method() !== "GET") writes.push({ path: url.pathname, body: request.postDataJSON() })
        let body = { success: true }
        if (url.pathname.endsWith("/login-status")) body = { bossLoggedIn: true, liepinLoggedIn: true, job51LoggedIn: true, zhilianLoggedIn: true }
        else if (url.pathname.endsWith("/status")) body = { success: true, isRunning: false, taskState: "IDLE", safetyHoldReason: "" }
        else if (url.pathname.endsWith("/delivery-settings")) {
          if (request.method() === "PUT") mode = request.postDataJSON().mode
          body = { success: true, mode }
        } else if (url.pathname.endsWith("/deliveries")) body = { success: true, items: [{
          id: "fixture-job", platform, status, job: { title: "Java 后端工程师（本地测试）", company: "测试公司", salary: "15-25K" },
        }] }
        else if (url.pathname.endsWith("/confirm")) { status = "SENT"; body = { success: true, status, message: "本地模拟回执" } }
        else if (url.pathname.endsWith("/config")) body = {
          config: { keywords: '["Java"]', jobArea: "[]", salary: "", cityCode: "", city: "" },
          options: { city: [], jobArea: [], salary: [] },
        }
        return route.fulfill({ status: 200, contentType: "application/json; charset=utf-8", headers, body: JSON.stringify(body) })
      }
      if (url.hostname !== "localhost" || url.port !== "6866") return route.abort()
      let file = path.resolve(dist, "." + decodeURIComponent(url.pathname))
      if (!file.startsWith(dist + path.sep)) return route.abort()
      try {
        if (!path.extname(file)) file += ".html"
        if (!(await stat(file)).isFile()) return route.abort()
        const type = { ".js": "application/javascript", ".css": "text/css", ".html": "text/html", ".txt": "text/plain" }[path.extname(file)] || "application/octet-stream"
        return route.fulfill({ status: 200, contentType: type + "; charset=utf-8", body: await readFile(file) })
      } catch { return route.fulfill({ status: 404, body: "Missing local fixture asset" }) }
    })
    const page = await context.newPage()
    page.on("pageerror", (error) => errors.push(error.message))
    await page.goto(`http://localhost:6866/${platform}`)
    const confirm = page.getByRole("button", { name: "确认投递", exact: true })
    await confirm.waitFor({ state: "visible" })
    await page.waitForFunction(() => [...document.querySelectorAll("button")].some((button) => button.textContent.trim() === "确认投递" && !button.disabled))
    assert.equal(writes.length, 0, "Opening the page must not send, resume, or save")
    assert.equal(await page.getByLabel("逐条确认（默认）").isChecked(), true)
    await page.getByLabel("自动投递", { exact: true }).check()
    assert.equal(writes.length, 0, "Mode selection must require explicit save")
    await page.getByRole("button", { name: "保存发送模式", exact: true }).click()
    await page.waitForFunction(() => [...document.querySelectorAll("button")].some((button) => button.textContent.trim() === "保存发送模式" && button.disabled))
    assert.equal(mode, "AUTO")
    assert.equal(writes.filter((item) => item.path.endsWith("/confirm")).length, 0)
    await confirm.click()
    await page.getByRole("dialog").waitFor()
    await page.screenshot({ path: path.join(screenshots, `${platform}-confirm.png`) })
    await page.getByRole("button", { name: "确认并投递", exact: true }).click()
    await page.getByText("已确认成功", { exact: true }).waitFor()
    assert.equal(writes.filter((item) => item.path === `/api/${platform}/deliveries/fixture-job/confirm`).length, 1)
    assert.equal(await confirm.count(), 0)
    await page.screenshot({ path: path.join(screenshots, `${platform}-desktop.png`), fullPage: true })
    if (platform === "51job") {
      await page.setViewportSize({ width: 390, height: 844 })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true)
      await page.screenshot({ path: path.join(screenshots, `${platform}-mobile.png`), fullPage: true })
    }
    assert.deepEqual(errors, [], "Built frontend must hydrate without runtime errors")
    await context.close()
    console.log(`${platform}: built UI, modes, confirmation dialog and single confirmed request passed`)
  }
} finally { await browser.close() }
