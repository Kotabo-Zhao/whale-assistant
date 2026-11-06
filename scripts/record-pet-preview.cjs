// Review movie from the packed runtime cels; no tweening or generated in-between frames.
const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..'),out=path.join(root,'reports/pet-independent-20261106');
const {chromium}=require(process.env.PET_PLAYWRIGHT_MODULE || path.join(out,'qa-tooling/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PET_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try {
  const page=await browser.newPage({viewport:{width:1280,height:440}});
  await page.goto(pathToFileURL(path.join(out,'独立动作预览.html')).href);
  await page.waitForFunction(()=>window.PET_READY===true);
  const video=await page.evaluate(async()=>{
   const data=window.PET_PREVIEW.data,images={};
   for(const [name,value] of Object.entries(data)) {const im=new Image();im.src=value.url;await im.decode();images[name]=im}
   document.body.replaceChildren();
   const canvas=document.createElement('canvas');canvas.width=1280;canvas.height=440;document.body.append(canvas);
   const ctx=canvas.getContext('2d');const stream=canvas.captureStream(30);
   const mime=MediaRecorder.isTypeSupported('video/webm;codecs=vp9')?'video/webm;codecs=vp9':'video/webm';
   const recorder=new MediaRecorder(stream,{mimeType:mime,videoBitsPerSecond:5000000});const chunks=[];
   recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)};
   const done=new Promise(resolve=>{recorder.onstop=()=>resolve(new Blob(chunks,{type:mime}))});
   function cel(name,t){const d=data[name],total=d.holds.reduce((s,h)=>s+h[1],0);let first=0;
    if(t>=total){first=d.loop;t=(t-total)%d.holds.slice(first).reduce((s,h)=>s+h[1],0)}
    for(let i=first;i<d.holds.length;i++){if(t<d.holds[i][1])return d.holds[i][0];t-=d.holds[i][1]}}
   function draw(t){
    ctx.fillStyle='#132333';ctx.fillRect(0,0,1280,440);
    const names=t<16000?['drink','reading','writing','control']:t<20000?['accept','reply','stopped','paused']:['idle','ask','notice','error'];
    const offset=t<16000?0:t<20000?16000:20000;
    names.forEach((name,i)=>{const f=cel(name,t-offset);
     ctx.fillStyle=i%2?'#0a1017':'#eaf1f5';ctx.fillRect(i*320+6,52,308,320);
     ctx.drawImage(images[name],f%4*320,Math.floor(f/4)*320,320,320,i*320+28,82,264,264);
     ctx.fillStyle='#e4eff7';ctx.font='21px "Microsoft YaHei",sans-serif';ctx.fillText(data[name].label,i*320+20,34)});
    ctx.fillStyle='#adc5d6';ctx.font='17px "Microsoft YaHei",sans-serif';
    ctx.fillText('独立动作素材回放 · 桌宠实际帧序与停留时长 · '+(t/1000).toFixed(1)+' 秒',22,411);
   }
   draw(0);recorder.start();const start=performance.now();
   await new Promise(resolve=>{function tick(){const t=performance.now()-start;draw(Math.min(t,23999));if(t<24000)requestAnimationFrame(tick);else resolve()}requestAnimationFrame(tick)});
   recorder.stop();const blob=await done;stream.getTracks().forEach(t=>t.stop());
   return await new Promise(resolve=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result.split(',')[1]);reader.readAsDataURL(blob)});
  });
  const file=path.join(out,'独立动作素材回放.webm');fs.writeFileSync(file,Buffer.from(video,'base64'));console.log(file);
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
