// Offline visual-review helper. Tooling stays outside production dependencies.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'reports/pet-independent-20261106');
const {chromium} = require(process.env.PET_PLAYWRIGHT_MODULE || path.join(out, 'qa-tooling/node_modules/playwright'));
(async () => {
  const browser = await chromium.launch({headless:true,
    executablePath: process.env.PET_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:1060},deviceScaleFactor:1});
    const errors=[];page.on('pageerror',e=>errors.push(String(e)));
    await page.goto(pathToFileURL(path.join(out,'独立动作预览.html')).href);
    await page.waitForFunction(()=>window.PET_READY===true);
    await page.locator('#play').click();
    const names=await page.evaluate(()=>Object.keys(window.PET_PREVIEW.data));
    assert.equal(names.length,18);
    for(const name of names) {
      await page.selectOption('#action',name);
      for(let frame=0;frame<8;frame++) {
        const state=await page.evaluate(n=>{window.PET_PREVIEW.frame(n);return window.PET_PREVIEW.state()},frame);
        assert.equal(state.frame,frame);assert.equal(state.playing,false);
        assert.equal(state.selected,name);
      }
    }
    await page.selectOption('#action','drink');
    await page.evaluate(()=>window.PET_PREVIEW.frame(4));
    await page.screenshot({path:path.join(out,'浏览器实际预览.png')});
    await page.locator('#play').click();
    const during=await page.evaluate(()=>window.PET_PREVIEW.state());
    assert.equal(during.frame,4);assert.equal(during.playing,true);
    await page.waitForTimeout(750);
    const after=await page.evaluate(()=>window.PET_PREVIEW.state());
    assert.ok(after.frame>=5,'Playback must resume from selected frame, not reset to the old clock');
    assert.deepEqual(errors,[]);
    const result={actions:names.length,inspectedCells:names.length*8,controls:'pass',consoleErrors:errors,
      note:'Controls and image loading only; visual acceptance requires playback review.'};
    fs.writeFileSync(path.join(out,'preview-check.json'),JSON.stringify(result,null,2)+'\n');
    console.log(JSON.stringify(result));
  } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
