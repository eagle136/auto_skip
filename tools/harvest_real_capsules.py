"""Harvest REAL labelled crops of the corner skip capsule from the device.

Read-only with respect to app data: the only device-side writes are
uiautomator dumps under /sdcard/harvest, which this script removes.

Each launch produces:
  pos/     crops of nodes whose text/contentDescription contains a skip word
  neg/     crops of other top-band controls (their text is kept in the manifest)
  visonly/ a capsule found by the pixel finder while the tree had NO skip word
           (this is the evidence question T3 asks)
  frames/  the full PNG, so every crop stays re-derivable
  manifest.csv  one row per crop

Usage:
  python tools/harvest_real_capsules.py --launches 12
"""

import argparse
import csv
import io
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

SKIP_WORDS = ["跳过", "跳過", "skip"]

# ---------------------------------------------------------------- adb plumbing


def find_adb():
    w = shutil.which("adb")
    if w:
        return w
    for c in [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
    ]:
        if os.path.exists(c):
            return c
    sys.exit("[fatal] adb not found")


class Dev:
    def __init__(self, adb, serial):
        self.adb = adb
        self.base = [adb] + (["-s", serial] if serial else [])

    def sh(self, cmd, binary=False, timeout=25):
        """adb -s S shell <cmd>.  NOTE: never use this for binary data -- adb shell
        translates LF to CRLF, which corrupts PNG (0A 0A -> 0D 0D)."""
        r = subprocess.run(self.base + ["shell"] + cmd, capture_output=True,
                           timeout=timeout)
        if binary:
            return r.stdout
        return r.stdout.decode("utf-8", "replace")

    def execout(self, cmd, timeout=25):
        """adb -s S exec-out <cmd> -> raw bytes, no PTY and no line-ending munging."""
        r = subprocess.run(self.base + ["exec-out"] + cmd, capture_output=True,
                           timeout=timeout)
        return r.stdout


def pick_serial(adb, override):
    if override:
        return override
    out = subprocess.run([adb, "devices"], capture_output=True,
                         timeout=15).stdout.decode("utf-8", "replace")
    live = [ln.split()[0] for ln in out.splitlines()[1:]
            if ln.strip().endswith("\tdevice")]
    if len(live) != 1:
        sys.exit(f"[fatal] expected exactly one online device, got {live} "
                 f"(pass --serial)")
    return live[0]


def screen_size(dev):
    out = dev.sh(["wm", "size"])
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        sys.exit(f"[fatal] cannot parse screen size from: {out!r}")
    return int(m.group(1)), int(m.group(2))


# ------------------------------------------------------------------- capture


def screencap(dev):
    """Full-frame PNG via exec-out (binary-safe, no device-side temp file)."""
    raw = dev.execout(["screencap", "-p"])
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return raw


def uiautomator_xml(dev, remote_dir="/sdcard/harvest"):
    """Return (xml_text, how). Tries the /dev/tty trick, falls back to file+cat."""
    txt = dev.sh(["uiautomator", "dump", "/dev/tty"], timeout=30)
    i, j = txt.find("<?xml"), txt.rfind("</hierarchy>")
    if i >= 0 and j > i:
        return txt[i:j + len("</hierarchy>")], "tty"
    remote = f"{remote_dir}/d_{int(time.time() * 1000)}.xml"
    dev.sh(["mkdir", "-p", remote_dir])
    out = dev.sh(["uiautomator", "dump", remote], timeout=30)
    raw = dev.execout(["cat", remote])
    dev.sh(["rm", "-f", remote])
    s = raw.decode("utf-8", "replace")
    i, j = s.find("<?xml"), s.rfind("</hierarchy>")
    if i < 0 or j <= i:
        return None, f"empty(out={out.strip()[:60]!r},bytes={len(raw)})"
    return s[i:j + len("</hierarchy>")], "file"


BOX = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_nodes(xml_text, W, H):
    """Flatten the tree into dicts with bounds + corner/zone flags."""
    nodes = []
    root = ET.fromstring(xml_text)
    for el in root.iter():
        a = el.attrib
        b = BOX.search(a.get("bounds", ""))
        if not b:
            continue
        x0, y0, x1, y1 = map(int, b.groups())
        bw, bh = x1 - x0, y1 - y0
        if bw <= 0 or bh <= 0:
            continue
        text = a.get("text", "") or ""
        desc = a.get("content-desc", "") or ""
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        nodes.append({
            "text": text, "desc": desc, "id": a.get("resource-id", "") or "",
            "cls": a.get("class", "") or "", "clickable": a.get("clickable"),
            "box": (x0, y0, x1, y1), "area": bw * bh,
            # corner zones = the 4 quarters of the top 22% band, plus bottom 22%
            "band": ("top" if cy < 0.22 * H else
                     "bottom" if cy > 0.78 * H else "mid"),
            "side": ("L" if cx < 0.5 * W else "R"),
        })
    return nodes


def has_skip(n):
    t = (n["text"] + n["desc"]).lower()
    return any(w.lower() in t for w in SKIP_WORDS)


# ------------------------------------------------------- pixel capsule finder
# Reuses the measurement that localized the real capsule in the previous round:
# distance from the local background median, binary closing, aspect/fill gates.


def _dilate(mask, it):
    """Same implementation as tools/gen_skip_corpus.py (shifted ORs, Chebyshev)."""
    m = mask.copy()
    for _ in range(it):
        out = m.copy()
        out[1:, :] |= m[:-1, :]
        out[:-1, :] |= m[1:, :]
        out[:, 1:] |= m[:, :-1]
        out[:, :-1] |= m[:, 1:]
        m = out
    return m


def _erode(mask, it):
    m = mask.copy()
    for _ in range(it):
        out = m.copy()
        out[1:, :] &= m[:-1, :]
        out[:-1, :] &= m[1:, :]
        out[:, 1:] &= m[:, :-1]
        out[:, :-1] &= m[:, 1:]
        m = out
    return m


def label_4conn(mask):
    """Two-pass union-find labelling. Returns list of (area, box) for each blob."""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    parent = [0]

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    nxt = 1
    for y in range(h):
        row = mask[y]
        for x in range(w):
            if not row[x]:
                continue
            up = lab[y - 1, x] if y else 0
            left = lab[y, x - 1] if x else 0
            if up == 0 and left == 0:
                lab[y, x] = nxt
                parent.append(nxt)
                nxt += 1
            elif up and left:
                lab[y, x] = min(up, left)
                union(up, left)
            else:
                lab[y, x] = up or left
    stats = {}
    for y in range(h):
        for x in range(w):
            v = lab[y, x]
            if v == 0:
                continue
            r = find(v)
            s = stats.get(r)
            if s is None:
                stats[r] = [1, x, x, y, y]
            else:
                s[0] += 1
                if x < s[1]:
                    s[1] = x
                elif x > s[2]:
                    s[2] = x
                if y < s[3]:
                    s[3] = y
                elif y > s[4]:
                    s[4] = y
    return [(s[0], (s[1], s[3], s[2] + 1, s[4] + 1)) for s in stats.values()]


def find_visual_capsule(png_bytes, W, H):
    """Most capsule-like blob in the top-right band. Returns (box, aspect, fill).

    Gates are the ones that successfully localized the real capsule last round:
    distance from the local background median, binary closing, then
    1.3<=aspect<=6.5, fill>0.35, and the blob must not span the whole band.
    """
    im = np.asarray(Image.open(io.BytesIO(png_bytes)).convert("RGB"), np.int16)
    bx0 = int(0.55 * W)
    by0 = int(0.04 * H)
    by1 = int(0.20 * H)
    sub = im[by0:by1, bx0:]
    bg = np.median(sub.reshape(-1, 3), axis=0)
    dist = np.abs(sub - bg).sum(axis=2)
    mask = _erode(_dilate(dist > 40, 3), 2)
    best = None
    for area, (x0, y0, x1, y1) in label_4conn(mask):
        bw, bh = x1 - x0, y1 - y0
        if bw < 60 or bh < 25:
            continue
        aspect = bw / float(bh)
        fill = area / float(bw * bh)
        if not (1.3 <= aspect <= 6.5 and fill > 0.35):
            continue
        if bw > 0.55 * (mask.shape[1]) or bh > 0.35 * (mask.shape[0]):
            continue
        if best is None or area > best[0]:
            best = (area, (bx0 + x0, by0 + y0, bx0 + x1, by0 + y1), aspect, fill)
    if best is None:
        return None
    return best[1], best[2], best[3]


# ---------------------------------------------------------------------- main


def save_png(path, png_bytes):
    with open(path, "wb") as f:
        f.write(png_bytes)


def crop_to(path, png_bytes, box, pad=2):
    im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    x0, y0, x1, y1 = box
    x0 = max(0, x0 - pad); y0 = max(0, y0 - pad)
    x1 = min(im.width, x1 + pad); y1 = min(im.height, y1 + pad)
    im.crop((x0, y0, x1, y1)).save(path)
    return (x1 - x0, y1 - y0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--launches", type=int, default=12)
    ap.add_argument("--pkg", default="com.byfen.market")
    ap.add_argument("--out", default=r"R:\skipnet_real")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--warm", type=float, default=0.8, help="s after launch before first probe")
    ap.add_argument("--attempts", type=int, default=7)
    ap.add_argument("--gap", type=float, default=0.35, help="s between probes")
    ap.add_argument("--recheck", type=float, default=1.2, help="s between launch rounds")
    args = ap.parse_args()

    adb = find_adb()
    ser = pick_serial(adb, args.serial)
    dev = Dev(adb, ser)
    W, H = screen_size(dev)
    print(f"[dev] serial={ser} {W}x{H}")

    # resolve launcher activity once
    out = dev.sh(["cmd", "package", "resolve-activity", "--brief", args.pkg])
    comp = out.strip().splitlines()[-1] if out.strip() else ""
    if "/" not in comp:
        comp = None
    print(f"[launch] component={comp or 'monkey'}")

    for sub in ["pos", "neg", "visonly", "frames", "noshell"]:
        os.makedirs(os.path.join(args.out, sub), exist_ok=True)
    man_path = os.path.join(args.out, "manifest.csv")
    new = not os.path.exists(man_path)
    man = open(man_path, "a", newline="", encoding="utf-8")
    wr = csv.writer(man)
    if new:
        wr.writerow(["launch", "kind", "file", "box", "wh", "text", "desc",
                     "id", "cls", "band", "side", "probes"])

    tally = dict(pos=0, neg=0, visonly=0, noshell=0, hit=0, miss=0)
    for li in range(args.launches):
        dev.sh(["am", "force-stop", args.pkg])
        time.sleep(args.recheck)
        if comp:
            dev.sh(["am", "start", "-n", comp])
        else:
            dev.sh(["monkey", "-p", args.pkg,
                    "-c", "android.intent.category.LAUNCHER", "1"])
        time.sleep(args.warm)

        focus = dev.sh(["dumpsys", "window", "displays"]).splitlines()
        focus = [ln.strip() for ln in focus if "mCurrentFocus" in ln
                 or "mFocusedApp" in ln]

        got = None
        png = None
        nodes = []
        for at in range(args.attempts):
            t0 = time.time()
            png = screencap(dev)
            xml_text, how = uiautomator_xml(dev)
            if png and xml_text:
                try:
                    nodes = parse_nodes(xml_text, W, H)
                except ET.ParseError:
                    nodes = []
            hits = [n for n in nodes if has_skip(n)]
            if hits:
                got = hits
                break
            time.sleep(max(0.0, args.gap - (time.time() - t0)))

        tag = f"L{li:02d}"
        frame = f"{tag}.png"
        if got and not png:
            print(f"[{tag}] skip nodes present but frame capture failed -> retry")
            got = None
        if png:
            save_png(os.path.join(args.out, "frames", frame), png)
        for ln in focus:
            print(f"[{tag}] focus {ln}")
        print(f"[{tag}] probes={len(nodes) if png else '-'} "
              f"skip_nodes={0 if not got else len(got)}")

        if got:
            tally["hit"] += 1
            for k, n in enumerate(got):
                f = f"{tag}_{k}.png"
                wh = crop_to(os.path.join(args.out, "pos", f), png, n["box"])
                wr.writerow([tag, "pos", f"pos/{f}", n["box"], wh, n["text"],
                             n["desc"], n["id"], n["cls"], n["band"], n["side"],
                             len(nodes)])
                tally["pos"] += 1
            # negatives: other top/bottom-band controls that are NOT skip nodes
            for k, n in enumerate(n for n in nodes
                                 if n["band"] != "mid" and not has_skip(n)):
                if n["area"] < 60 * 40:
                    continue
                f = f"{tag}_{k}.png"
                wh = crop_to(os.path.join(args.out, "neg", f), png, n["box"])
                wr.writerow([tag, "neg", f"neg/{f}", n["box"], wh, n["text"],
                             n["desc"], n["id"], n["cls"], n["band"], n["side"],
                             len(nodes)])
                tally["neg"] += 1
        else:
            tally["miss"] += 1
            if png:
                v = find_visual_capsule(png, W, H)
                if v:
                    box, asp, fill = v
                    f = f"{tag}.png"
                    wh = crop_to(os.path.join(args.out, "visonly", f), png, box)
                    wr.writerow([tag, "visonly", f"visonly/{f}", box, wh,
                                 "", "", "", f"aspect={asp:.2f};fill={fill:.2f}",
                                 "top", "R", len(nodes)])
                    tally["visonly"] += 1
                    print(f"[{tag}] VISUAL capsule {box} aspect={asp:.2f} "
                          f"fill={fill:.2f} but tree had no skip word")
                else:
                    shutil.copy(os.path.join(args.out, "frames", frame),
                                os.path.join(args.out, "noshell", frame))
                    tally["noshell"] += 1
        man.flush()

    dev.sh(["rm", "-f", "/sdcard/harvest/d_*.xml"])
    dev.sh(["rmdir", "/sdcard/harvest"])
    print("\n[tally] " + "  ".join(f"{k}={v}" for k, v in sorted(tally.items())))
    print(f"[out] {args.out}")
    print(f"[space] {dir_bytes(args.out) / 1e6:.1f} MB")
    man.close()


def dir_bytes(d):
    tot = 0
    for _, _, fs in os.walk(d):
        for f in fs:
            tot += os.path.getsize(os.path.join(d, f))
    return tot


if __name__ == "__main__":
    main()
