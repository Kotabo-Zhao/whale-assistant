"""Package independently illustrated cels. No generated limbs, meshes, morphs or in-between images."""
from pathlib import Path
from PIL import Image
import argparse, hashlib, json
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "art/pet-independent/sources"
OUTPUT = ROOT / "android/app/src/main/assets/pet-cels"
NAMES = "idle drink reading writing control accept ask celebrate setback touch observe wait paused stopped error reply notice drag".split()
AIRBORNE = {"celebrate", "drag"}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def pack(name):
    path = SOURCES / f"{name}.png"
    raw = Image.open(path).convert("RGBA")
    frames, anchors = [], []
    for i in range(8):
        left, right = round((i % 4) * raw.width / 4), round((i % 4 + 1) * raw.width / 4)
        top, bottom = round((i // 4) * raw.height / 2), round((i // 4 + 1) * raw.height / 2)
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
    atlas = Image.new("RGBA", (1280, 640))
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
    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / f"{name}.webp"
    atlas.save(target, "WEBP", lossless=True, method=6, exact=True)
    return {"name":name,"source":str(path.relative_to(ROOT)).replace("\\","/"),
            "sourceSha256":sha(path),"sourceSize":list(raw.size),"atlasSize":[1280,640],
            "assetSha256":sha(target),"assetBytes":target.stat().st_size,
            "registration":"fixed cell center, flight preserved" if name in AIRBORNE else "fixed cell center and feet, same scale for all frames",
            "frames":qa,"visualAcceptance":"requires per-action visual review; geometry checks are not aesthetic approval"}

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--available",action="store_true",help="Package present sources for an intermediate preview")
    args=parser.parse_args()
    names=[n for n in NAMES if (SOURCES/f"{n}.png").exists()] if args.available else NAMES
    reports=[pack(n) for n in names]
    report={"artSource":"host_image","route":"native image_gen","sharedSkeleton":False,
            "interpolatedFrames":False,"sourceArtworkPreserved":True,
            "actions":reports,"missing":[n for n in NAMES if n not in names]}
    out=ROOT/"art/pet-independent/package.json"
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"packed":len(reports),"bytes":sum(r["assetBytes"] for r in reports),"missing":report["missing"]},ensure_ascii=False))
