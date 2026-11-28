import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { chromium } from 'playwright'

const entry = readFileSync(new URL('../app/viewport-compatibility-script.tsx', import.meta.url),'utf8').split('String.raw`')[1].split('`')[0]
const styles = readFileSync(new URL('../app/globals.css', import.meta.url),'utf8')
const css = styles.split('/* Native shell spacing')[1]
const safeBottom = styles.match(/--myshop-safe-bottom:[^;]+;/)[0]
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const browser = await chromium.launch({headless:true, executablePath:existsSync(chrome)?chrome:undefined})
async function fixture({mobile, app, viewport = 'width=device-width, initial-scale=1, viewport-fit=cover'}) {
  const context = await browser.newContext({viewport:{width:mobile?406:1440,height:900},screen:{width:mobile?406:1440,height:900},isMobile:mobile,hasTouch:mobile})
  if(app) await context.addInitScript(state=>{window.__AIKAYUN_DISPLAY__=state},app)
  const page = await context.newPage()
  await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:`<!doctype html><meta name="viewport" content="${viewport}"><style>:root{${safeBottom}}body{margin:0}.storefront-main{padding-top:16px}#bottom-control{position:fixed;bottom:var(--myshop-safe-bottom)}/* Native shell spacing${css}</style><script>${entry}</script><div class="store-shell"><main class="storefront-main storefront-main--without-header"><header>First line</header><input value="draft"><button id="bottom-control">Bottom</button></main></div>`}))
  const session = await context.newCDPSession(page)
  await session.send('Emulation.setSafeAreaInsetsOverride',{insets:{bottom:16}})
  await page.goto('https://fixture.test/')
  await page.waitForTimeout(100)
  return {context,page}
}
const app = {version:1,origin:'https://fixture.test',desktop:false,topBarDp:68,containerWidthDp:406}
try {
  const {context,page} = await fixture({mobile:true,app})
  assert.equal(await page.evaluate(()=>document.documentElement.clientWidth),406)
  assert.equal(await page.locator('main').evaluate(e=>parseFloat(getComputedStyle(e).paddingTop)),84)
  assert.equal(await page.locator('#bottom-control').evaluate(e=>parseFloat(getComputedStyle(e).bottom)),16)
  await page.evaluate(()=>{window.__AIKAYUN_DISPLAY__.desktop=true;dispatchEvent(new Event('aikayun:display'))})
  await page.waitForTimeout(100)
  assert.equal(await page.evaluate(()=>document.documentElement.clientWidth),1280)
  assert.equal(await page.evaluate(()=>matchMedia('(min-width: 1024px)').matches),true)
  const gap = await page.evaluate(()=>parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--aikayun-topbar-gap')))
  assert.ok(Math.abs(gap - 68 * 1280/406) < 1)
  assert.ok(Math.abs(await page.locator('#bottom-control').evaluate(e=>parseFloat(getComputedStyle(e).bottom)) - 16*1280/406)<0.2)
  // Next.js may replace viewport tags on client navigation. The one observer must reconcile it.
  await page.evaluate(()=>{const meta=document.createElement('meta');meta.name='viewport';meta.content='width=device-width, initial-scale=1';document.head.append(meta)})
  await page.waitForTimeout(100)
  assert.equal(await page.locator('meta[name="viewport"]').count(),1)
  assert.equal(await page.evaluate(()=>document.documentElement.clientWidth),1280)
  await page.evaluate(()=>{window.__AIKAYUN_DISPLAY__.desktop=false;dispatchEvent(new Event('aikayun:display'))})
  await page.waitForTimeout(100)
  assert.equal(await page.evaluate(()=>document.documentElement.clientWidth),406)
  assert.equal(await page.locator('input').inputValue(),'draft')
  assert.equal(await page.locator('#bottom-control').evaluate(e=>parseFloat(getComputedStyle(e).bottom)),16)
  await context.close()
  for (const mobile of [true,false]) {
    const {context,page} = await fixture({mobile})
    assert.equal(await page.evaluate(()=>document.documentElement.clientWidth),mobile?406:1440)
    assert.equal(await page.evaluate(()=>document.documentElement.hasAttribute('data-aikayun-shell')),false)
    assert.equal(await page.locator('main').evaluate(e=>parseFloat(getComputedStyle(e).paddingTop)),16)
    assert.equal(await page.locator('#bottom-control').evaluate(e=>parseFloat(getComputedStyle(e).bottom)),16)
    await context.close()
  }
  const wide = await fixture({mobile:true,viewport:'width=1280, initial-scale=1'})
  assert.equal(await wide.page.evaluate(()=>document.documentElement.clientWidth),1280)
  await wide.context.close()
  const invalid = await fixture({mobile:true,app:{...app,origin:'https://other.test',desktop:true}})
  assert.equal(await invalid.page.evaluate(()=>document.documentElement.clientWidth),406)
  assert.equal(await invalid.page.evaluate(()=>document.documentElement.hasAttribute('data-aikayun-shell')),false)
  await invalid.context.close()
  console.log('PASS: actual browser viewport mobile/1280/mobile, navigation metadata, scaled toolbar spacing, ordinary mobile/PC/wide viewport, exact-origin rejection')
} finally {await browser.close()}
