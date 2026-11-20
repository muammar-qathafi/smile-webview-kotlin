from PIL import Image
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(ROOT)
res = os.path.join(PROJECT, "composeApp", "src", "androidMain", "res")

# Sumber: tools/source/smile_logo.png atau env SMILE_LOGO_SRC
_default_src = os.path.join(ROOT, "source", "smile_logo.png")
_legacy_src = (
    r"C:\Users\YOGA\.cursor\projects\c-Project-multifab-mfg-datacore-webview-smile-2-1\assets"
    r"\c__Users_YOGA_AppData_Roaming_Cursor_User_workspaceStorage_b8ef3174a554b65aa99f35757455b30a"
    r"_images_image-89b72eea-b938-4b31-bef4-bb636b96c97e.png"
)
src = os.environ.get("SMILE_LOGO_SRC") or (
    _default_src if os.path.isfile(_default_src) else _legacy_src
)
if not os.path.isfile(src):
    sys.exit(f"Logo source not found: {src}")

# Adaptive icon: konten aman ~66% diameter — hindari potong di launcher (Honor/MIUI).
LAUNCHER_PAD = 0.18
ADAPTIVE_FOREGROUND_PAD = 0.22
# Logo persegi putih — padding seragam agar proporsional di large icon (~64dp).
NOTIFICATION_PAD = 0.12
# Monokrom status bar — sedikit padding agar script "Smile" + subtext terbaca saat di-tint merah.
STAT_PAD = 0.14

img = Image.open(src).convert("RGBA")
pixels = img.load()
w, h = img.size
minx, miny, maxx, maxy = w, h, 0, 0
for y in range(h):
    for x in range(w):
        r, g, b, a = pixels[x, y]
        if a < 8:
            continue
        if r > 245 and g > 245 and b > 245:
            continue
        if r < 20 and g < 20 and b < 20:
            continue
        minx = min(minx, x)
        miny = min(miny, y)
        maxx = max(maxx, x)
        maxy = max(maxy, y)

pad = 4
minx = max(0, minx - pad)
miny = max(0, miny - pad)
maxx = min(w - 1, maxx + pad)
maxy = min(h - 1, maxy + pad)
cropped = img.crop((minx, miny, maxx + 1, maxy + 1))


def fit_square(im, size, bg=(0, 0, 0, 0), pad_ratio=0.10):
    box = Image.new("RGBA", (size, size), bg)
    pad_px = int(size * pad_ratio)
    avail = size - 2 * pad_px
    iw, ih = im.size
    scale = min(avail / iw, avail / ih)
    nw, nh = max(1, int(iw * scale)), max(1, int(ih * scale))
    resized = im.resize((nw, nh), Image.Resampling.LANCZOS)
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    box.paste(resized, (ox, oy), resized)
    return box


def white_silhouette_from_color(im: Image.Image) -> Image.Image:
    """Status bar: siluet putih opaque dari logo merah (Smile + Multifab Mobile)."""
    from PIL import ImageFilter

    cw, ch = im.size
    sil = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    sp, cp = sil.load(), im.load()
    for y in range(ch):
        for x in range(cw):
            r, g, b, a = cp[x, y]
            if a < 12:
                continue
            if r > 245 and g > 245 and b > 245:
                continue
            sp[x, y] = (255, 255, 255, 255)
    alpha = sil.split()[3]
    alpha = alpha.filter(ImageFilter.MaxFilter(3))
    out = Image.new("RGBA", (cw, ch), (255, 255, 255, 255))
    out.putalpha(alpha)
    return out


def save_rgb(square_rgba: Image.Image, path: str) -> None:
    square_rgba.convert("RGB").save(path, optimize=True)


# ── Notifikasi (large / tray) ───────────────────────────────────────────────
for folder, size in {
    "drawable-mdpi": 96,
    "drawable-hdpi": 144,
    "drawable-xhdpi": 192,
    "drawable-xxhdpi": 288,
    "drawable-xxxhdpi": 384,
}.items():
    outdir = os.path.join(res, folder)
    os.makedirs(outdir, exist_ok=True)
    fit_square(cropped, size, bg=(255, 255, 255, 255), pad_ratio=NOTIFICATION_PAD).save(
        os.path.join(outdir, "ic_notification_smile_logo.png"),
        optimize=True,
    )

# ── Launcher — proporsional, tidak terpotong ────────────────────────────────
# Foreground layer = 108dp → px = launcher_px * (108/48)
for folder, launcher_px in {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}.items():
    outdir = os.path.join(res, folder)
    os.makedirs(outdir, exist_ok=True)
    legacy = fit_square(cropped, launcher_px, bg=(255, 255, 255, 255), pad_ratio=LAUNCHER_PAD)
    save_rgb(legacy, os.path.join(outdir, "ic_launcher.png"))
    save_rgb(legacy, os.path.join(outdir, "ic_launcher_round.png"))

    fg_px = int(round(launcher_px * 108 / 48))
    fg = fit_square(cropped, fg_px, bg=(255, 255, 255, 255), pad_ratio=ADAPTIVE_FOREGROUND_PAD)
    save_rgb(fg, os.path.join(outdir, "ic_launcher_foreground.png"))

# ── ic_stat_smile (semua density, ukuran standar Android) ───────────────────
sil = white_silhouette_from_color(cropped)
for folder, size in {
    "drawable-mdpi": 24,
    "drawable-hdpi": 36,
    "drawable-xhdpi": 48,
    "drawable-xxhdpi": 72,
    "drawable-xxxhdpi": 96,
}.items():
    outdir = os.path.join(res, folder)
    os.makedirs(outdir, exist_ok=True)
    fit_square(sil, size, bg=(0, 0, 0, 0), pad_ratio=STAT_PAD).save(
        os.path.join(outdir, "ic_stat_smile.png"),
        optimize=True,
    )

# Ekspor untuk Chrome/web push (FCM background)
web_icons_dir = os.path.normpath(
    os.path.join(os.path.dirname(os.path.dirname(PROJECT)), "mfg-datacore-smile", "public", "icons")
)
if os.path.isdir(web_icons_dir):
    for name, px in {"notification-192.png": 192, "notification-512.png": 512}.items():
        fit_square(cropped, px, bg=(255, 255, 255, 255), pad_ratio=NOTIFICATION_PAD).convert("RGB").save(
            os.path.join(web_icons_dir, name),
            optimize=True,
        )
    print("web icons updated in mfg-datacore-smile/public/icons")

print("done — launcher safe-zone padding, stat + notification from color logo")
