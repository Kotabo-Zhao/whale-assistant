// Review movie from the exact packed runtime cels, including offline-baked optical-flow midpoints.
const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..'),out=path.join(root,'reports/pet-independent-20261106');
const {chromium}=require(process.env.PET_PLAYWRIGHT_MODULE || path.join(out,'qa-tooling/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PET_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try {
  const page=await browser.newPage({viewport:{width:1280,height:440}});
  const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  await page.goto(pathToFileURL(path.join(out,'独立动作预览.html')).href);
  await page.waitForFunction(()=>window.PET_READY===true);
  const video=await page.evaluate(async(group)=>{
   const data=window.PET_PREVIEW.data,images={};
   for(const [name,value] of Object.entries(data)) {const im=new Image();im.src=value.url;await im.decode();images[name]=im}
   window.PET_PREVIEW.frame(0); // stop the inspection player's timer before replacing its DOM
   document.body.replaceChildren();
   const canvas=document.createElement('canvas');canvas.width=1280;canvas.height=440;document.body.append(canvas);
   const ctx=canvas.getContext('2d');const stream=canvas.captureStream(60);
   const mime=MediaRecorder.isTypeSupported('video/webm;codecs=vp9')?'video/webm;codecs=vp9':'video/webm';
   const recorder=new MediaRecorder(stream,{mimeType:mime,videoBitsPerSecond:5000000});const chunks=[];
   recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)};
   const done=new Promise(resolve=>{recorder.onstop=()=>resolve(new Blob(chunks,{type:mime}))});
   function cel(name,t){const d=data[name],total=d.holds.reduce((s,h)=>s+h[1],0);let first=0;
    if(t>=total){first=d.loop;t=(t-total)%d.holds.slice(first).reduce((s,h)=>s+h[1],0)}
    for(let i=first;i<d.holds.length;i++){if(t<d.holds[i][1])return d.holds[i][0];t-=d.holds[i][1]}}
   function draw(t){
    ctx.fillStyle='#132333';ctx.fillRect(0,0,1280,440);
    const names=group==='transitions'?['drink','reading','writing','control']:group==='reactions'
      ?(t<8000?['accept','ask','celebrate','setback']:t<16000?['touch','reply','notice','drag']:['observe','wait','paused','error'])
      :(t<16000?['drink','reading','writing','control']:t<20000?['accept','reply','stopped','paused']:['idle','ask','notice','error']);
    const offset=group==='transitions'?0:group==='reactions'?(t<8000?0:t<16000?8000:16000):(t<16000?0:t<20000?16000:20000);
    names.forEach((base,i)=>{
     let name=base,time=t-offset;
     if(group==='transitions') {
      const duration=data[base].holds.reduce((s,h)=>s+h[1],0),exit=base+'_return';
      const ending=data[exit].holds.reduce((s,h)=>s+h[1],0);
      if(time>=duration+ending){name='idle';time-=duration+ending}
      else if(time>=duration){name=exit;time-=duration}
     }
     const f=cel(name,time);
     ctx.fillStyle=i%2?'#0a1017':'#eaf1f5';ctx.fillRect(i*320+6,52,308,320);
     ctx.drawImage(images[name],f%4*320,Math.floor(f/4)*320,320,320,i*320+28,82,264,264);
     ctx.fillStyle='#e4eff7';ctx.font='21px "Microsoft YaHei",sans-serif';ctx.fillText(data[base].label+' · '+(name===base?'动作':name==='idle'?'待机':'收尾'),i*320+14,34)});
    ctx.fillStyle='#adc5d6';ctx.font='17px "Microsoft YaHei",sans-serif';
    ctx.fillText('离线插值素材回放 · 动作 → 独立收尾 → 待机 · '+(t/1000).toFixed(1)+' 秒',22,411);
   }
   draw(0);recorder.start();const start=performance.now();
   const duration=group==='transitions'?9000:24000;
   await new Promise(resolve=>{function tick(){const t=performance.now()-start;draw(Math.min(t,duration-1));if(t<duration)requestAnimationFrame(tick);else resolve()}requestAnimationFrame(tick)});
   recorder.stop();const blob=await done;stream.getTracks().forEach(t=>t.stop());
   return await new Promise(resolve=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result.split(',')[1]);reader.readAsDataURL(blob)});
  },process.env.PET_MOVIE_GROUP||'props');
  if(errors.length)throw new Error(errors.join('\n'));
  const file=path.join(out,(process.env.PET_MOVIE_NAME||'顺滑修订回放')+'.webm');fs.writeFileSync(file,Buffer.from(video,'base64'));console.log(file);
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
