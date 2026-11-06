"""Build an offline inspection page from the exact runtime atlases and Kotlin cel timings."""
from pathlib import Path
import base64, json, re
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"reports/pet-independent-20261106"
OUT.mkdir(parents=True,exist_ok=True)
ASSETS=ROOT/"android/app/src/main/assets/pet-cels"
source=(ROOT/"android/app/src/main/java/io/deepseekharness/mobile/overlay/PetCels.kt").read_text(encoding="utf-8")
names={"idle":"安静待机","drink":"举杯喝水","reading":"阅读翻页","writing":"坐姿书写","control":"手机点按",
       "accept":"接单确认","ask":"询问","celebrate":"完成庆祝","setback":"受挫恢复","touch":"触摸回应",
       "observe":"观察手机","wait":"等待","paused":"暂停","stopped":"结束工作","error":"异常反馈",
       "reply":"对话回复","notice":"挥手递信","drag":"拖动平衡"}
clips={}
for match in re.finditer(r'clip\("([a-z]+)",([^)]*)\)',source):
    name,body=match.groups()
    times=[];loop=0;still=0;cells=8
    for arg in body.split(","):
        arg=arg.strip()
        if re.fullmatch(r"\d+",arg):times.append(int(arg))
        elif arg.startswith("loopFrom"):loop=int(arg.split("=")[1])
        elif arg.startswith("still"):still=int(arg.split("=")[1])
        elif arg.startswith("cells"):cells=int(arg.split("=")[1])
    clips[name]={"holds":[[i,t] for i,t in enumerate(times)],"loop":loop,"still":still,"cells":cells}
if "ask" not in clips:
    ask=re.search(r'PetCelClip\("ask",(.+?)\), still = (\d+)\)',source,re.S)
    assert ask,"Missing authored ASK timings"
    clips["ask"]={"holds":[list(map(int,p)) for p in re.findall(r"PetCelHold\((\d+),(\d+)\)",ask[1])],"loop":0,"still":int(ask[2]),"cells":8}
assert set(clips)==set(names)
primary_names=list(names)
looping=set(re.findall(r'"([a-z]+)"',re.search(r'private val looping = setOf\(([^\n]+)\)',source)[1]))
release=re.search(r'clip\(name, (.+?),\s*still = 15, loopFrom = 15, cells = 16\)',source,re.S)[1]
return_times=[int(x.strip()) for x in release.split(',')]
for name in primary_names:
    if name!='idle':
        names[name+'_return']=names[name]+' · 收尾'
        clips[name+'_return']={'holds':list(enumerate(return_times)),'loop':15,'still':15,'cells':16}
for name,clip in clips.items():
    old=clip['holds'];holds=[]
    for i,ms in old:
        if i==clip['cells']-1 and name not in looping:holds.append([i*2,ms])
        else:holds.extend([[i*2,ms-min(ms,80)//2],[i*2+1,min(ms,80)//2]])
    clip.update(holds=holds,loop=clip['loop']*2,still=clip['still']*2,cells=clip['cells']*2)
data={}
for name in names:
    path=ASSETS/f"{name}.webp"
    if not path.exists():raise ValueError(f"Missing atlas: {name}")
    assert Image.open(path).size==(1280,clips[name]['cells']//4*320),f"Wrong cell layout: {name}"
    data[name]={**clips[name],"label":names[name],"url":"data:image/webp;base64,"+base64.b64encode(path.read_bytes()).decode()}

html=r"""<!doctype html><html lang="zh-CN"><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>鲸鱼助手 · 独立动作预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0d1824;color:#e2edf5;font:15px/1.5 system-ui,"Microsoft YaHei",sans-serif}
main{max-width:1180px;margin:auto;padding:28px}h1{font-size:25px;margin:0 0 6px}p{margin:0 0 18px;color:#98b1c5}
.controls{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:18px 0}
button,select{background:#203649;border:1px solid #36566e;color:#eef8ff;padding:9px 14px;border-radius:9px;font:inherit;cursor:pointer}
button.active{background:#4ca6aa;color:#082e36}.stages{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.stage{display:flex;align-items:center;justify-content:center;min-height:400px;border-radius:18px;position:relative}
.light{background:#edf3f7}.dark{background:#0a1017;border:1px solid #34414f}.stage span{position:absolute;top:14px;left:18px;color:#6d8497;font-size:13px}
canvas{max-width:100%;object-fit:contain}.status{display:flex;justify-content:space-between;margin:14px 0;color:#b6cfdf}
.gallery{display:grid;grid-template-columns:repeat(9,1fr);gap:8px}.card{padding:8px 4px;text-align:center}.card img{display:block;width:88px;height:88px;margin:auto;object-fit:cover}
.card small{display:block;font-size:12px}input[type=range]{width:240px;accent-color:#77c9c2}
@media(max-width:850px){.gallery{grid-template-columns:repeat(6,1fr)}.stages{grid-template-columns:1fr}.stage{min-height:330px}}
@media(max-width:550px){.gallery{grid-template-columns:repeat(3,1fr)}main{padding:16px}}
</style><main><h1>鲸鱼助手 · 独立动作预览</h1>
<p>独立原画加离线光流插值。偶数索引保留原画，奇数索引为中间帧；动作播完会收尾并回到待机。可暂停逐帧检查。</p>
<div class="controls"><select id="action" aria-label="动作"></select><button id="play">暂停</button><button id="prev">上一帧</button><button id="next">下一帧</button>
<select id="size" aria-label="显示尺寸"><option value="264">实机 264 像素</option><option value="88">88 像素</option><option value="480">放大检查</option></select>
<select id="speed" aria-label="播放速度"><option value="1">正常速度</option><option value=".5">半速</option></select><button id="tour">依次播放全部</button></div>
<div class="stages"><div class="stage light"><span>浅色背景</span><canvas id="light"></canvas></div><div class="stage dark"><span>深色背景</span><canvas id="dark"></canvas></div></div>
<div class="status"><strong id="label"></strong><label>逐帧 <input id="scrub" type="range" min="0" max="7" step="1" value="0"></label><span id="frame"></span></div>
<div class="gallery" id="gallery"></div></main>
<script>const DATA=__DATA__;
const images={},order=Object.keys(DATA),primary=order.filter(n=>!n.endsWith('_return')),selector=document.querySelector('#action'),gallery=document.querySelector('#gallery');
const canvases=[document.querySelector('#light'),document.querySelector('#dark')];
let selected='drink',playing=true,frame=0,elapsed=0,last=performance.now(),speed=1,size=264,tour=false,tourIndex=0,timer=null;
function sample(name,t){const d=DATA[name],total=d.holds.reduce((s,h)=>s+h[1],0);let start=0;
 if(t>=total){start=d.loop;t=(t-total)%d.holds.slice(start).reduce((s,h)=>s+h[1],0)}
 for(let i=start;i<d.holds.length;i++){if(t<d.holds[i][1])return {frame:d.holds[i][0],next:d.holds[i][1]-t};t-=d.holds[i][1]}}
function paint(){for(const c of canvases){c.width=c.height=size;c.style.width=c.style.height=size+'px';const x=c.getContext('2d');
 x.clearRect(0,0,size,size);x.drawImage(images[selected],(frame%4)*320,Math.floor(frame/4)*320,320,320,0,0,size,size)}
 document.querySelector('#label').textContent=DATA[selected].label;document.querySelector('#frame').textContent=(frame+1)+' / '+DATA[selected].cells;
 document.querySelector('#scrub').max=DATA[selected].cells-1;
 document.querySelector('#scrub').value=frame;selector.value=selected;
 for(const b of gallery.children)b.classList.toggle('active',b.dataset.name===selected);
 document.querySelector('#play').textContent=playing?'暂停':'播放'}
function tick(){clearTimeout(timer);const now=performance.now();if(playing)elapsed+=(now-last)*speed;last=now;
 const duration=DATA[selected].holds.reduce((s,h)=>s+h[1],0);
 if(playing&&selected!=='idle'&&elapsed>=duration){selected=selected.endsWith('_return')?'idle':selected+'_return';elapsed-=duration}
 if(tour&&selected==='idle'&&elapsed>=2600){tourIndex=(tourIndex+1)%primary.length;selected=primary[tourIndex];elapsed=0}
 const s=sample(selected,elapsed);if(playing)frame=s.frame;paint();
 if(playing)timer=setTimeout(tick,Math.max(10,s.next/speed))}
function choose(name){selected=name;elapsed=0;frame=DATA[name].holds[0][0];last=performance.now();tick()}
selector.onchange=()=>{tour=false;choose(selector.value)};
document.querySelector('#play').onclick=()=>{playing=!playing;last=performance.now();tick()};
function manual(n){playing=false;tour=false;frame=(n+DATA[selected].cells)%DATA[selected].cells;
 const at=DATA[selected].holds.findIndex(h=>h[0]===frame);
 elapsed=at<0?0:DATA[selected].holds.slice(0,at).reduce((s,h)=>s+h[1],0);tick()}
document.querySelector('#prev').onclick=()=>manual(frame-1);document.querySelector('#next').onclick=()=>manual(frame+1);
document.querySelector('#scrub').oninput=e=>manual(+e.target.value);
document.querySelector('#size').onchange=e=>{size=+e.target.value;paint()};
document.querySelector('#speed').onchange=e=>{speed=+e.target.value;last=performance.now();tick()};
document.querySelector('#tour').onclick=()=>{tour=true;tourIndex=0;playing=true;choose('idle')};
document.addEventListener('visibilitychange',()=>{if(document.hidden){clearTimeout(timer)}else{last=performance.now();tick()}});
window.PET_PREVIEW={select:choose,frame:manual,state:()=>({selected,frame,elapsed,playing}),data:DATA};
Promise.all(order.map(name=>new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>{
 images[name]=im;const c=document.createElement('canvas');c.width=c.height=88;
 c.getContext('2d').drawImage(im,(DATA[name].still%4)*320,Math.floor(DATA[name].still/4)*320,320,320,0,0,88,88);
 const b=document.createElement('button');b.className='card';b.dataset.name=name;const thumb=new Image();thumb.src=c.toDataURL();b.append(thumb);
 const text=document.createElement('small');text.textContent=DATA[name].label;b.append(text);b.onclick=()=>{tour=false;choose(name)};gallery.append(b);
 const o=document.createElement('option');o.value=name;o.textContent=DATA[name].label;selector.append(o);resolve()};im.onerror=reject;im.src=DATA[name].url}))).then(()=>{last=performance.now();tick();window.PET_READY=true});
</script></html>"""
(OUT/"独立动作预览.html").write_text(html.replace("__DATA__",json.dumps(data,ensure_ascii=False)),encoding="utf-8")
(OUT/"timings.json").write_text(json.dumps({n:{**clips[n],"label":names[n]} for n in names},ensure_ascii=False,indent=2),encoding="utf-8")
font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",18)
board=Image.new("RGB",(1400,310*((len(names)+4)//5)),(236,243,247));draw=ImageDraw.Draw(board)
for i,name in enumerate(names):
    atlas=Image.open(ASSETS/f"{name}.webp").convert("RGBA")
    ix=clips[name]["still"];cel=atlas.crop((ix%4*320,ix//4*320,ix%4*320+320,ix//4*320+320))
    cel=cel.convert("RGBa").resize((264,264),Image.Resampling.LANCZOS).convert("RGBA")
    x=i%5*280+8;y=i//5*310+32
    board.paste(cel,(x,y),cel);draw.text((x+12,y-26),names[name],font=font,fill=(24,52,75))
board.save(OUT/"独立动作总览.png")
for group in range(3):
    board=Image.new("RGB",(1536,1236),(13,25,37));draw=ImageDraw.Draw(board)
    for row,name in enumerate(list(names)[group*6:group*6+6]):
        atlas=Image.open(ASSETS/f"{name}.webp").convert("RGBA")
        draw.text((10,row*206+2),names[name],font=font,fill=(213,233,244))
        for ix in range(8):
            cel=atlas.crop((ix%4*320,ix//4*320,ix%4*320+320,ix//4*320+320))
            cel=cel.convert("RGBa").resize((180,180),Image.Resampling.LANCZOS).convert("RGBA")
            board.paste(cel,(ix*192+6,row*206+24),cel)
    board.save(OUT/f"逐帧检查-{group+1}.png")
for name,clip in clips.items():
    if clip['cells']<=8:continue
    board=Image.new("RGB",(1120,300*(clip['cells']//4)),(13,25,37));draw=ImageDraw.Draw(board)
    atlas=Image.open(ASSETS/f"{name}.webp").convert("RGBA")
    for ix in range(clip['cells']):
        cel=atlas.crop((ix%4*320,ix//4*320,ix%4*320+320,ix//4*320+320))
        cel=cel.convert("RGBa").resize((264,264),Image.Resampling.LANCZOS).convert("RGBA")
        x=ix%4*280+8;y=ix//4*300+28
        board.paste(cel,(x,y),cel)
        draw.text((x,y-25),f"{names[name]} {ix+1}",font=font,fill=(213,233,244))
    board.save(OUT/f"连续姿态-{name}.png")
print(json.dumps({"actions":len(data),"html":str(OUT/"独立动作预览.html")},ensure_ascii=False))
