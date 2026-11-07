"""Register independently illustrated key cels, then optionally bake optical-flow midpoints."""
from pathlib import Path
from PIL import Image
import argparse, hashlib, json, subprocess, sys
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "art/pet-independent/sources"
OUTPUT = ROOT / "android/app/src/main/assets/pet-cels"
NAMES = "idle drink reading writing control accept ask celebrate setback touch observe wait paused stopped error reply notice drag".split()
AIRBORNE = {"celebrate", "drag"}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def pack(name, output=OUTPUT):
    smooth = SOURCES / f"{name}-smooth16.png"
    path = smooth if smooth.exists() else SOURCES / f"{name}.png"
    count, rows = (16, 4) if smooth.exists() else (8, 2)
    raw = Image.open(path).convert("RGBA")
    frames, anchors = [], []
    for i in range(count):
        left, right = round((i % 4) * raw.width / 4), round((i % 4 + 1) * raw.width / 4)
        top, bottom = round((i // 4) * raw.height / rows), round((i // 4 + 1) * raw.height / rows)
        frame = raw.crop((left, top, right, bottom))
        a = np.asarray(frame.getchannel("A"))
        if np.mean(a > 200) > .78:
            raise ValueError(f"{name}/{i}: background is not transparent; repair source artwork")
        ys, xs = np.where(a > 128)
        if len(xs) < 1000:
            raise ValueError(f"{name}/{i}: empty character")
        floor = int(ys.max())
        foot_x = float(np.median(xs[ys > floor - 30]))
        anchors.append((foot_x, floor))
        frames.append(frame)
    atlas = Image.new("RGBA", (1280, 320 * rows))
    qa = []
    for i, frame in enumerate(frames):
        # Fixed feet registration corrects camera drift, not body pose. Preserve authored flight.
        _, ay = anchors[0] if name in AIRBORNE else anchors[i]
        # The authored cell center is the head/root registration. Centering the feet
        # would shift the entire body whenever one shoe moves outwards (the ASK stance).
        ax = frame.width / 2
        scale = (320 / (raw.width / 4)) * .90 * (.88 if name == "writing" else 1)
        size = (round(frame.width * scale), round(frame.height * scale))
        # Premultiplied filtering prevents invisible RGB around generated alpha from making fringes.
        small = frame.convert("RGBa").resize(size, Image.Resampling.LANCZOS).convert("RGBA")
        x = round(160 - ax * scale)
        y = min(round(304 - ay * scale), 320 - small.height - 8)
        cel = Image.new("RGBA", (320, 320))
        cel.alpha_composite(small, (x, y))
        a = np.asarray(cel.getchannel("A"))
        if max(a[0].max(), a[-1].max(), a[:, 0].max(), a[:, -1].max()) != 0:
            raise ValueError(f"{name}/{i}: character or residue touches output cell edge")
        yy, xx = np.where(a > 128)
        qa.append({"frame": i, "anchor": [ax, ay], "offset": [x,y], "scale": scale,
                   "opaqueBounds": [int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)],
                   "opaquePixels": int((a > 200).sum())})
        atlas.alpha_composite(cel, ((i % 4) * 320, (i // 4) * 320))
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"{name}.webp"
    atlas.save(target, "WEBP", lossless=True, method=6, exact=True)
    return {"name":name,"source":str(path.relative_to(ROOT)).replace("\\","/"),
            "sourceSha256":sha(path),"sourceSize":list(raw.size),"atlasSize":list(atlas.size),"cells":count,
            "assetSha256":sha(target),"assetBytes":target.stat().st_size,
            "registration":"fixed cell center, flight preserved" if name in AIRBORNE else "fixed cell center and feet, same scale for all frames",
            "frames":qa,"visualAcceptance":"requires per-action visual review; geometry checks are not aesthetic approval"}

def pack_return(name, output):
    path = SOURCES / f"{name}-return16.png"
    raw = Image.open(path).convert("RGBA")
    # Native sheets can have slightly uneven empty gutters. Locate the actual row gaps
    # before slicing so a curl from the next row never enters the preceding frame.
    ink = (np.asarray(raw.getchannel('A')) > 128).sum(axis=1)
    cuts = [0]
    for k in range(1,4):
        lo,hi=round((k-.32)*raw.height/4),round((k+.32)*raw.height/4)
        gaps=np.where(ink[lo:hi]==0)[0]+lo
        if not len(gaps): raise ValueError(f'{name}: row {k} overlaps; repair source artwork')
        cuts.append(int(gaps[np.argmin(abs(gaps-k*raw.height/4))]))
    cuts.append(raw.height)
    frames = [raw.crop((round(i%4*raw.width/4),cuts[i//4],
                       round((i%4+1)*raw.width/4),cuts[i//4+1])) for i in range(16)]
    def bounds(image):
        a = np.asarray(image.getchannel('A'))
        if np.mean(a > 200) > .78: raise ValueError(f'{name}: opaque return background')
        ys,xs = np.where(a > 128)
        assert len(xs)>1000
        return np.array([xs.min(),ys.min(),xs.max()+1,ys.max()+1],dtype=float)
    endpoints = [Image.open(ROOT/f'art/pet-independent/references/{n}-endpoint.png').convert('RGBA')
                 for n in (name,'idle')]
    raw_bounds = [bounds(frames[i]) for i in (0,15)]
    target_bounds = [bounds(im) for im in endpoints]
    # Whole-canvas camera registration only: a uniform scale and translation, never individual bones.
    scales = [(b[3]-b[1])/(a[3]-a[1]) for a,b in zip(raw_bounds,target_bounds)]
    def head_center(image):
        b=bounds(image);a=np.asarray(image.getchannel('A'))>128
        y0=int(b[1]+.12*(b[3]-b[1]));y1=int(b[1]+.40*(b[3]-b[1]))
        _,xs=np.where(a[y0:y1]);return float(np.median(xs))
    root_x = [head_center(im) for im in endpoints]
    atlas = Image.new('RGBA',(1280,1280)); qa=[]
    for i,frame in enumerate(frames):
        t=i/15
        lerp=lambda values: values[0]*(1-t)+values[1]*t
        b=bounds(frame);scale=lerp(scales)
        small=frame.convert('RGBa').resize((round(frame.width*scale),round(frame.height*scale)),Image.Resampling.LANCZOS).convert('RGBA')
        x=round(lerp(root_x)-head_center(frame)*scale)
        y=round(lerp([v[3] for v in target_bounds])-b[3]*scale)
        cel=Image.new('RGBA',(320,320));cel.alpha_composite(small,(x,y))
        if i in (0,15): cel=endpoints[0 if i==0 else 1].copy()
        a=np.asarray(cel.getchannel('A'))
        # Atlas gutter: reject clipped ink, but do not let near-transparent generator grain
        # outside the figure bleed into the neighbouring cell during bitmap filtering.
        edge=max(a[:2].max(),a[-2:].max(),a[:,:2].max(),a[:,-2:].max())
        if edge <= 8 and i not in (0,15):
            bounded=Image.new('RGBA',(320,320));bounded.paste(cel.crop((2,2,318,318)),(2,2));cel=bounded
            a=np.asarray(cel.getchannel('A'))
        if a[0].any() or a[-1].any() or a[:,0].any() or a[:,-1].any():
            raise ValueError(f'{name}_return/{i}: drawing or alpha residue touches cell edge')
        atlas.paste(cel,(i%4*320,i//4*320))
        qa.append(dict(frame=i,scale=scale,offset=[x,y],opaqueBounds=bounds(cel).astype(int).tolist()))
    target=output/f'{name}_return.webp';atlas.save(target,'WEBP',lossless=True,method=6,exact=True)
    return dict(name=f'{name}_return',source=str(path.relative_to(ROOT)).replace('\\','/'),
                sourceSha256=sha(path),sourceSize=list(raw.size),atlasSize=list(atlas.size),cells=16,
                assetSha256=sha(target),assetBytes=target.stat().st_size,frames=qa,
                exactEndpoints=[f'references/{name}-endpoint.png','references/idle-endpoint.png'])

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--available",action="store_true",help="Package present sources for an intermediate preview")
    parser.add_argument("--interpolate",action="store_true",help="Include independent return clips and bake twofold optical-flow interpolation")
    args=parser.parse_args()
    names=[n for n in NAMES if (SOURCES/f"{n}.png").exists()] if args.available else NAMES
    output=ROOT/'reports/pet-independent-20261106/key-atlases' if args.interpolate else OUTPUT
    reports=[pack(n,output) for n in names]
    if args.interpolate:
        returns=[n for n in names if n!='idle' and (not args.available or (SOURCES/f'{n}-return16.png').exists())]
        reports += [pack_return(n,output) for n in returns]
        subprocess.run([sys.executable,str(ROOT/'scripts/interpolate-pet-cels.py'),'--input',str(output),
                        '--output',str(OUTPUT),'--names',*[r['name'] for r in reports]],check=True)
        subprocess.run([sys.executable, str(ROOT/'scripts/prepare-pet-entries.py'), '--assets', str(OUTPUT),
                        '--cv-python', str(ROOT/'reports/pet-independent-20261106/qa-python')], check=True)
        for report in reports:
            report['keyCells']=report['cells'];report['keyAtlasSha256']=report['assetSha256']
            report['cells']*=2;report['atlasSize'][1]*=2
            target=OUTPUT/(report['name']+'.webp')
            report['assetSha256']=sha(target);report['assetBytes']=target.stat().st_size
    report={"artSource":"host_image","route":"native image_gen","sharedSkeleton":False,
            "interpolatedFrames":args.interpolate,"sourceArtworkPreserved":True,
            "actions":reports,"missing":[n for n in NAMES if n not in names]}
    if args.interpolate:
        report["entries"] = [{"name": f"{n}_enter", "source": f"{n}_return", "cells": 32,
                              "assetSha256": sha(OUTPUT/f"{n}_enter.webp")} for n in returns]
    out=ROOT/"art/pet-independent/package.json"
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"packed":len(reports),"bytes":sum(r["assetBytes"] for r in reports),"missing":report["missing"]},ensure_ascii=False))
