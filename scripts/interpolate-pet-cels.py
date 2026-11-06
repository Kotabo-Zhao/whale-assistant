"""Offline whole-cel optical-flow interpolation, explicitly requested by the user.

Original drawings remain exact even-numbered frames. Generated odd-numbered frames
warp premultiplied RGBA together, using bidirectional flow and consistency weights.
No OpenCV dependency or optical-flow computation is shipped to the Android app.
"""
from pathlib import Path
import argparse
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
LOOPS = {n: 0 for n in ('idle', 'reading', 'writing', 'control', 'ask', 'observe', 'wait', 'drag')}
LOOPS.update(paused=3, stopped=5, error=6)


def interpolate(first, second, fraction=.5):
    import cv2
    import numpy as np
    a, b = np.asarray(first, dtype=np.float32)/255, np.asarray(second, dtype=np.float32)/255
    def gray(p):
        rgb = p[:, :, :3]*p[:, :, 3:4] + .5*(1-p[:, :, 3:4])
        return cv2.cvtColor(np.rint(rgb*255).astype(np.uint8), cv2.COLOR_RGB2GRAY)
    estimator = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    estimator.setFinestScale(0)
    estimator.setGradientDescentIterations(40)
    estimator.setVariationalRefinementIterations(10)
    ga, gb = gray(a), gray(b)
    forward = estimator.calc(ga, gb, None)
    backward = estimator.calc(gb, ga, None)
    # Regularise sub-pixel flow discontinuities at thin illustrated outlines.
    forward = cv2.GaussianBlur(forward,(0,0),1.8)
    backward = cv2.GaussianBlur(backward,(0,0),1.8)
    yy, xx = np.mgrid[:a.shape[0], :a.shape[1]].astype(np.float32)
    grid = np.stack((xx, yy), axis=-1)
    def remap(value, points):
        return cv2.remap(value, points[:, :, 0], points[:, :, 1], cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    def sample(p, flow, reverse, time):
        # Solve the inverse mapping instead of treating a forward flow as a backward map.
        points = grid-time*flow
        for _ in range(3):
            points = grid-time*remap(flow, points)
        mismatch = np.linalg.norm(flow+remap(reverse, grid+flow), axis=-1)
        confidence = .15+.85*np.exp(-mismatch*mismatch/12)
        premultiplied = p.copy()
        premultiplied[:, :, :3] *= premultiplied[:, :, 3:4]
        return remap(premultiplied, points), remap(confidence, points)
    wa, ca = sample(a, forward, backward, fraction)
    wb, cb = sample(b, backward, forward, 1-fraction)
    left, right = (1-fraction)*ca, fraction*cb
    total = np.maximum(left+right, 1e-6)
    mixed = (wa*left[:, :, None]+wb*right[:, :, None])/total[:, :, None]
    # Crossings (especially a page over a sleeve) do not have a two-way pixel match.
    # Do not dissolve two differently located outlines into a ghost hand/page there.
    # Select one warped observation for the entire inconsistent neighbourhood.
    disagreement = np.max(np.abs(wa-wb), axis=-1) > .16
    disagreement = cv2.morphologyEx(disagreement.astype(np.uint8),cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
    disagreement = cv2.dilate(disagreement,np.ones((5,5),np.uint8)) > 0
    mixed[disagreement] = (wb if fraction>=.5 else wa)[disagreement]
    alpha = mixed[:, :, 3:4]
    mixed[:, :, :3] /= np.maximum(alpha, 1/255)
    mixed[alpha[:, :, 0] < .5/255] = 0
    # Both endpoints have transparent gutters. Flow on near-transparent generator grain
    # is unconstrained; keep that grain out of neighbouring atlas cells, never crop ink.
    edge = max(mixed[:2,:,3].max(), mixed[-2:,:,3].max(), mixed[:,:2,3].max(), mixed[:,-2:,3].max())
    assert edge <= 8/255, f'Optical flow moved opaque ink outside the canvas: {edge}'
    mixed[:2] = 0; mixed[-2:] = 0; mixed[:,:2] = 0; mixed[:,-2:] = 0
    from PIL import Image
    result = Image.fromarray(np.rint(np.clip(mixed, 0, 1)*255).astype(np.uint8))
    return result, float(np.percentile(np.linalg.norm(forward, axis=-1)[a[:, :, 3]>.5], 95))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--names', nargs='+', required=True)
    parser.add_argument('--cv-python', type=Path, default=ROOT/'reports/pet-independent-20261106/qa-python')
    args = parser.parse_args()
    if args.cv_python.exists(): sys.path.insert(0, str(args.cv_python))
    import cv2
    import numpy as np
    from PIL import Image
    cv2.setNumThreads(2)
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for name in args.names:
        source = args.input/f'{name}.webp'
        sheet = Image.open(source).convert('RGBA')
        assert sheet.width == 1280 and sheet.height % 320 == 0
        count = sheet.height//320*4
        frames = [sheet.crop((i%4*320, i//4*320, i%4*320+320, i//4*320+320)) for i in range(count)]
        atlas = Image.new('RGBA', (1280, sheet.height*2))
        motions = []
        for i, frame in enumerate(frames):
            next_index = i+1 if i+1<count else LOOPS.get(name)
            middle, distance = interpolate(frame, frames[next_index]) if next_index is not None else (frame, 0)
            for j, image in ((i*2, frame), (i*2+1, middle)):
                alpha = np.asarray(image.getchannel('A'))
                assert not (alpha[0].any() or alpha[-1].any() or alpha[:, 0].any() or alpha[:, -1].any()), (name, j)
                # paste preserves original RGBA pixels exactly; no alpha is composited twice.
                atlas.paste(image, (j%4*320, j//4*320))
            motions.append(distance)
        target = args.output/f'{name}.webp'
        atlas.save(target, 'WEBP', lossless=True, method=6, exact=True)
        decoded=Image.open(target).convert('RGBA')
        for i,frame in enumerate(frames):
            j=i*2; actual=decoded.crop((j%4*320,j//4*320,j%4*320+320,j//4*320+320))
            assert np.array_equal(np.asarray(actual),np.asarray(frame)), (name,i,'changed original key cel')
        interpolated=count if name in LOOPS else count-1
        reports.append(dict(name=name, keyCells=count, interpolatedCells=interpolated,
            unusedDuplicatePaddingCells=count-interpolated, cells=count*2,
            sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            outputSha256=hashlib.sha256(target.read_bytes()).hexdigest(), bytes=target.stat().st_size,
            flowP95PixelsPerPair=motions))
        print(json.dumps(dict(name=name, cells=count*2, bytes=target.stat().st_size)), flush=True)
    (args.output/'interpolation.json').write_text(json.dumps(dict(
        algorithm='OpenCV DIS bidirectional flow; premultiplied RGBA; smoothed flow; single-source occlusion neighbourhoods',
        opencv=cv2.__version__, originalFramesUnchanged=True, runtimeOpticalFlow=False,
        reference='https://docs.opencv.org/4.13.0/de/d4f/classcv_1_1DISOpticalFlow.html',
        clips=reports), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


if __name__ == '__main__': main()
