import { chromium } from '@playwright/test'

const BASE = 'http://localhost:5175'
const out = process.argv[2] ?? 'd:/chenxu/my-linkone/_tmp_browse.png'

const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 414, height: 900 }, deviceScaleFactor: 2 })
const page = await ctx.newPage()

page.on('console', (m) => {
  if (m.type() === 'error') console.log('[console.error]', m.text())
})

await page.goto(`${BASE}/#/pages/rt/browse/index`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(6000)

const cards = await page.locator('.sku').count()
const barKw = await page.locator('.bar__kw').first().textContent().catch(() => '')
const count = await page.locator('.bar__count').first().textContent().catch(() => '')
const firstName = await page.locator('.sku__name').first().textContent().catch(() => '')
const firstStore = await page.locator('.sku__store').first().textContent().catch(() => '')
console.log(JSON.stringify({ cards, barKw, count, firstName, firstStore }, null, 1))
await page.screenshot({ path: out, fullPage: false })

// 首页入口
await page.goto(`${BASE}/#/`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(4000)
console.log(JSON.stringify({
  plazaTitle: await page.locator('.plaza__title').first().textContent().catch(() => ''),
  plazaBtn: await page.locator('.plaza__btn').first().textContent().catch(() => ''),
}, null, 1))

// 首页入口跳转：输入关键词 → 搜索 → 落到广场页且带关键词
await page.locator('.plaza__input input').first().fill('Sell')
await page.locator('.plaza__btn').first().click()
await page.waitForTimeout(5000)
console.log(JSON.stringify({
  cards: await page.locator('.sku').count(),
  kw: await page.locator('.bar__kw').first().textContent().catch(() => ''),
  count: await page.locator('.bar__count').first().textContent().catch(() => ''),
  url: page.url(),
}, null, 1))

await browser.close()
