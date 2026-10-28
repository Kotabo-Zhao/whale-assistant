const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const assets = path.resolve('dist/assets');
  const matches = fs.readdirSync(assets).filter(name => /^marketCatalog\.worker-.*\.js$/.test(name));
  if (matches.length !== 1) throw Error('Expected exactly one production worker');
  const code = fs.readFileSync(path.join(assets, matches[0]));
  const profile = process.env.BENCHMARK_PROFILE;
  if (!profile || !process.env.CHROME_PATH) throw Error('Set BENCHMARK_PROFILE and CHROME_PATH');
  if (fs.existsSync(profile)) throw Error('Benchmark profile already exists');
  const server = http.createServer((req, res) => {
    if (req.url === '/worker.js') { res.writeHead(200, {'Content-Type':'application/javascript'}); res.end(code); }
    else { res.writeHead(200, {'Content-Type':'text/html'}); res.end('<!doctype html><title>Worker benchmark</title>'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let context;
  try {
    context = await chromium.launchPersistentContext(profile, { executablePath: process.env.CHROME_PATH, headless: true, args: ['--disable-gpu','--disk-cache-size=1048576'] });
    const page = await context.newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const result = await page.evaluate(async () => {
      const plugins = Array.from({length: 20000}, (_, i) => ({name:`插件 ${i}`, npm:`example-plugin-${i}`, description:{zh:`描述 ${i}`}}));
      const data = new TextEncoder().encode(JSON.stringify({plugins}));
      const inputBytes = data.byteLength;
      const start = performance.now();
      const worker = new Worker('/worker.js', {type:'module'});
      let ticks = 0; const timer = setInterval(() => ticks++, 0);
      const result = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Worker timed out')), 10000);
        worker.onmessage = event => { clearTimeout(timeout); resolve(event.data); };
        worker.onerror = event => { clearTimeout(timeout); reject(new Error(event.message)); };
        worker.postMessage(data.buffer, [data.buffer]);
      });
      const elapsed = performance.now() - start;
      clearInterval(timer); worker.terminate();
      if (!result.ok || result.entries.length !== 20000) throw Error('Worker returned unexpected data');
      return { count:result.entries.length, inputBytes, workerWallMs:elapsed, eventLoopTicks:ticks, userAgent:navigator.userAgent };
    });
    console.log(JSON.stringify(result));
  } finally { if (context) await context.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
