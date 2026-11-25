# Draws the WygOTA icons: a broadcast tower with signal arcs on a navy-to-teal gradient.
import os, sys
from PIL import Image, ImageDraw, ImageFont
R = sys.argv[1]  # res dir
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
NAVY, TEAL, GOLD, WHITE = (12, 24, 52), (16, 92, 110), (242, 186, 72), (245, 248, 252)

def gradient(w, h):
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / float(w + h)
            px[x, y] = tuple(int(NAVY[i] + (TEAL[i] - NAVY[i]) * t) for i in range(3)) + (255,)
    return im

def tower(d, cx, top, bottom, s):
    """Lattice tower from (cx, top) down to bottom, with signal arcs; s = scale (line width basis)."""
    lw = max(1, int(s))
    half = (bottom - top) * 0.30
    d.line([(cx, top), (cx - half, bottom)], fill=WHITE, width=lw)
    d.line([(cx, top), (cx + half, bottom)], fill=WHITE, width=lw)
    for k in (0.35, 0.62, 0.86):
        y = top + (bottom - top) * k; w = half * k
        d.line([(cx - w, y), (cx + w, y)], fill=WHITE, width=max(1, lw * 2 // 3))
    r = s * 1.6
    d.ellipse([cx - r, top - r, cx + r, top + r], fill=GOLD)
    for i, rad in enumerate((s * 5, s * 8.5)):
        box = [cx - rad, top - rad, cx + rad, top + rad]
        d.arc(box, 200, 250, fill=GOLD, width=lw)
        d.arc(box, 290, 340, fill=GOLD, width=lw)

def foreground(n):
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    tower(d, n / 2, n * 0.36, n * 0.70, n / 54.0)
    f = ImageFont.truetype(FONT, int(n * 0.09))
    d.text((n / 2, n * 0.76), "OTA", font=f, fill=GOLD, anchor="mm")
    return im

def legacy(n, round_):
    bg = gradient(n, n)
    m = int(n * 1.4); o = (m - n) // 2; fg = foreground(m).crop((o, o + n // 20, o + n, o + n + n // 20))
    im = Image.alpha_composite(bg, fg)
    mask = Image.new("L", (n, n), 0); md = ImageDraw.Draw(mask)
    if round_: md.ellipse([0, 0, n - 1, n - 1], fill=255)
    else: md.rounded_rectangle([0, 0, n - 1, n - 1], radius=n // 6, fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0)); out.paste(im, (0, 0), mask); return out

for dens, base in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    d = os.path.join(R, "mipmap-" + dens); os.makedirs(d, exist_ok=True)
    big = int(base * 2.25)
    gradient(big, big).save(os.path.join(d, "ic_launcher_background.png"))
    foreground(big).save(os.path.join(d, "ic_launcher_foreground.png"))
    legacy(base, False).save(os.path.join(d, "ic_launcher.png"))
    legacy(base, True).save(os.path.join(d, "ic_launcher_round.png"))

# TV banner 320x180
b = gradient(320, 180); d = ImageDraw.Draw(b)
tower(d, 62, 52, 140, 2.6)
d.text((118, 78), "WygOTA", font=ImageFont.truetype(FONT, 40), fill=WHITE, anchor="lm")
d.text((120, 116), "ANTENNA TV", font=ImageFont.truetype(FONT, 16), fill=GOLD, anchor="lm")
os.makedirs(os.path.join(R, "drawable-xhdpi"), exist_ok=True)
b.save(os.path.join(R, "drawable-xhdpi", "ic_banner.png"))
if len(sys.argv) > 2: legacy(512, False).save(sys.argv[2])
