"""Generate the SpkPrac app icon: teal rounded square, white microphone, symmetric sound arcs."""
from PIL import Image, ImageDraw, ImageFilter
import math, os

S = 1024
SS = 3
W = S * SS

# ---------------------------------------------------------------- background
bg = Image.new("RGB", (W, W))
top = (16, 92, 88)
bot = (10, 58, 62)
px = bg.load()
for y in range(W):
    t = y / (W - 1)
    for x in range(0, W, 1):
        pass
# faster: draw horizontal lines
d = ImageDraw.Draw(bg)
for y in range(W):
    t = y / (W - 1)
    c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
    d.line([(0, y), (W, y)], fill=c)

# soft radial glow in the upper-left, built as a radial alpha ramp (no hard edge)
glow = Image.new("L", (W, W), 0)
gp = glow.load()
gcx, gcy = W * 0.22, W * 0.16
gr = W * 0.85
for y in range(0, W, 2):
    for x in range(0, W, 2):
        dist = math.hypot(x - gcx, y - gcy) / gr
        v = max(0.0, 1.0 - dist) ** 2.2
        val = int(v * 90)
        for dy in (0, 1):
            for dx in (0, 1):
                if y + dy < W and x + dx < W:
                    gp[x + dx, y + dy] = val
glow = glow.filter(ImageFilter.GaussianBlur(W * 0.02))
tint = Image.new("RGB", (W, W), (120, 255, 235))
bg = Image.composite(tint, bg, glow.point(lambda v: v))

# rounded-square mask
radius = int(W * 0.215)
mask = Image.new("L", (W, W), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=radius, fill=255)
img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
img.paste(bg, (0, 0), mask)

# ---------------------------------------------------------------- glyph
glyph = Image.new("RGBA", (W, W), (0, 0, 0, 0))
g = ImageDraw.Draw(glyph)

cx = W / 2
cy = W * 0.5
WHITE = (255, 255, 255, 255)

# microphone capsule
cap_w = W * 0.150
cap_h = W * 0.215
cap_top = W * 0.245
g.rounded_rectangle(
    [cx - cap_w / 2, cap_top, cx + cap_w / 2, cap_top + cap_h],
    radius=cap_w / 2, fill=WHITE,
)

# cradle arc under the capsule (semi-circle, opening upward)
cradle_r = W * 0.135
cradle_cy = cap_top + cap_h * 0.86
lw = int(W * 0.036)
g.arc(
    [cx - cradle_r, cradle_cy - cradle_r, cx + cradle_r, cradle_cy + cradle_r],
    start=20, end=160, fill=WHITE, width=lw,
)

# stem + base
stem_bottom = cradle_cy + W * 0.125
g.line([(cx, cradle_cy + cradle_r * math.sin(math.radians(20)) - lw * 0.2), (cx, stem_bottom)],
       fill=WHITE, width=lw)
base_w = W * 0.115
g.rounded_rectangle(
    [cx - base_w / 2, stem_bottom - lw * 0.5, cx + base_w / 2, stem_bottom + lw * 0.5],
    radius=lw / 2, fill=WHITE,
)

# sound arcs, symmetric left and right around the capsule centre
arc_cy = cap_top + cap_h * 0.46
for i, r in enumerate((W * 0.235, W * 0.315)):
    alpha = 190 - i * 70
    w = int(W * (0.030 - i * 0.007))
    col = (150, 248, 228, alpha)
    box = [cx - r, arc_cy - r, cx + r, arc_cy + r]
    g.arc(box, start=-34, end=34, fill=col, width=w)      # right
    g.arc(box, start=146, end=214, fill=col, width=w)     # left

img.alpha_composite(glyph)

# ---------------------------------------------------------------- export
icon = img.resize((S, S), Image.LANCZOS)
sizes = [16, 24, 32, 48, 64, 128, 256]
icon.save("icons/icon.ico", format="ICO", sizes=[(s, s) for s in sizes])
for s in (32, 128, 256):
    icon.resize((s, s), Image.LANCZOS).save(f"icons/{s}x{s}.png")
icon.resize((512, 512), Image.LANCZOS).save("icons/128x128@2x.png")
icon.save("icons/icon.png")
print("icons written:", sorted(os.listdir("icons")))
