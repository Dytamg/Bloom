#!/usr/bin/env python3
"""
scripts/generate_brand_assets.py
Extracts and adapts the EXACT original Bloom logo artwork (from commit 15869b5)
for Android adaptive icons, Compose multiplatform app logo, and legacy mipmaps.
Preserves the user's beloved original artwork pixel-perfect without redrawing.
"""

import os
import subprocess
import numpy as np
from PIL import Image, ImageDraw

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORIGINAL_LOGO_TMP = "/tmp/original_bloom_logo.png"
COMMIT_ORIGINAL = "15869b5"

# Exact original colors
PLUM_RGB = (79, 5, 56)      # #4F0538
PLUM_RGBA = (79, 5, 56, 255)
CYAN_RGB = (51, 194, 232)   # #33C2E8 (Flower)
PINK_RGB = (224, 70, 134)   # #E04686 (Eggshell & fragment)

def extract_original_logo():
    """Extracts original bloom_logo.png from git commit 15869b5 if needed."""
    if not os.path.exists(ORIGINAL_LOGO_TMP) or os.path.getsize(ORIGINAL_LOGO_TMP) == 0:
        print(f"Extracting original bloom_logo.png from {COMMIT_ORIGINAL}...")
        subprocess.run(
            f"git show {COMMIT_ORIGINAL}:androidApp/src/main/res/drawable/bloom_logo.png > {ORIGINAL_LOGO_TMP}",
            shell=True,
            check=True
        )
    print(f"Original logo ready at {ORIGINAL_LOGO_TMP}")

def extract_clean_artwork(original_path: str) -> Image.Image:
    """
    Decomposes original_bloom_logo.png to extract the original artwork elements
    (flower, eggshell, detached fragment) with pixel-perfect antialiasing on transparent alpha.
    """
    orig_img = Image.open(original_path).convert("RGBA")
    arr = np.array(orig_img, dtype=float)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3]

    B = np.array(PLUM_RGB, dtype=float)
    cyan = np.array(CYAN_RGB, dtype=float)
    pink = np.array(PINK_RGB, dtype=float)

    # Artwork candidate zone (within original circular badge)
    y, x = np.ogrid[:500, :500]
    dist_from_center = np.sqrt((x - 249.5)**2 + (y - 249.5)**2)
    art_zone = (dist_from_center < 145)

    delta = rgb - B
    diff = np.linalg.norm(delta, axis=-1)

    v_c = cyan - B
    len_sq_c = np.sum(v_c**2)
    v_p = pink - B
    len_sq_p = np.sum(v_p**2)

    alpha_c = np.sum(delta * v_c, axis=-1) / len_sq_c
    alpha_p = np.sum(delta * v_p, axis=-1) / len_sq_p

    recon_c = B + np.outer(np.clip(alpha_c, 0, 1).ravel(), v_c).reshape(rgb.shape)
    recon_p = B + np.outer(np.clip(alpha_p, 0, 1).ravel(), v_p).reshape(rgb.shape)

    err_c = np.linalg.norm(rgb - recon_c, axis=-1)
    err_p = np.linalg.norm(rgb - recon_p, axis=-1)

    art_mask = art_zone & (diff > 2.0) & (alpha > 200)

    closer_to_c = err_c < err_p
    alpha_est = np.zeros(arr.shape[:2])
    fg_est = np.zeros(rgb.shape)

    alpha_est[closer_to_c] = np.clip(alpha_c[closer_to_c], 0, 1)
    fg_est[closer_to_c] = cyan

    alpha_est[~closer_to_c] = np.clip(alpha_p[~closer_to_c], 0, 1)
    fg_est[~closer_to_c] = pink

    # Seam blend between cyan & pink
    A = np.column_stack([v_c, v_p])
    A_pinv = np.linalg.pinv(A)

    seam_mask = art_mask & (np.minimum(err_c, err_p) > 3.0)
    seam_deltas = delta[seam_mask]
    seam_alphas = np.dot(seam_deltas, A_pinv.T)
    seam_alpha_c = np.clip(seam_alphas[:, 0], 0, 1)
    seam_alpha_p = np.clip(seam_alphas[:, 1], 0, 1)
    total_a = seam_alpha_c + seam_alpha_p

    seam_fg = (seam_alpha_c[:, None] * cyan + seam_alpha_p[:, None] * pink) / np.maximum(total_a[:, None], 1e-6)

    alpha_est[seam_mask] = np.clip(total_a, 0, 1)
    fg_est[seam_mask] = seam_fg

    alpha_est[~art_mask] = 0.0
    fg_est[~art_mask] = 0.0

    rgba = np.zeros((500, 500, 4), dtype=np.uint8)
    rgba[:, :, :3] = np.round(np.clip(fg_est, 0, 255)).astype(np.uint8)
    rgba[:, :, 3] = np.round(np.clip(alpha_est * 255, 0, 255)).astype(np.uint8)

    full_extracted = Image.fromarray(rgba)
    # Crop to exact bounding box (139..333, 126..338)
    # 139..334, 126..339 gives 195x213
    cropped = full_extracted.crop((139, 126, 334, 339))
    print(f"Extracted original artwork bbox size: {cropped.size} (W={cropped.width}, H={cropped.height})")
    return cropped

def render_artwork_on_canvas(artwork_crop: Image.Image, canvas_size: int, scale_factor: float, bg_color=None) -> Image.Image:
    """
    Scales the original artwork motif by scale_factor and centers it on canvas_size x canvas_size.
    If bg_color is provided, fills canvas with solid bg_color. Otherwise creates transparent canvas.
    """
    target_w = int(round(artwork_crop.width * scale_factor))
    target_h = int(round(artwork_crop.height * scale_factor))
    scaled_art = artwork_crop.resize((target_w, target_h), Image.Resampling.LANCZOS)

    if bg_color:
        canvas = Image.new("RGBA", (canvas_size, canvas_size), bg_color)
    else:
        canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))

    x0 = (canvas_size - target_w) // 2
    y0 = (canvas_size - target_h) // 2
    canvas.paste(scaled_art, (x0, y0), scaled_art)
    return canvas

def make_antialiased_mask(size: int, shape: str = "round") -> Image.Image:
    """Creates a high-quality supersampled antialiased mask for legacy icons."""
    scale = 4
    big_size = size * scale
    mask = Image.new("L", (big_size, big_size), 0)
    draw = ImageDraw.Draw(mask)
    pad = int(big_size * 2 / 48)
    if shape == "round":
        draw.ellipse((pad, pad, big_size - pad, big_size - pad), fill=255)
    else:
        radius = int(big_size * 8 / 48)
        draw.rounded_rectangle((pad, pad, big_size - pad, big_size - pad), radius=radius, fill=255)
    return mask.resize((size, size), Image.Resampling.LANCZOS)

def generate_legacy_icon(artwork_crop: Image.Image, size: int, shape: str) -> Image.Image:
    """Renders a legacy pre-scaled Android launcher icon with shaped background badge."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = make_antialiased_mask(size, shape)
    bg = Image.new("RGBA", (size, size), PLUM_RGBA)
    canvas.paste(bg, (0, 0), mask)

    # Scale artwork to fit comfortably inside the legacy badge safe zone
    # Motif should occupy ~58% of badge size
    target_art_dim = size * 0.58
    art_scale = target_art_dim / max(artwork_crop.width, artwork_crop.height)
    w = max(1, int(round(artwork_crop.width * art_scale)))
    h = max(1, int(round(artwork_crop.height * art_scale)))
    scaled_art = artwork_crop.resize((w, h), Image.Resampling.LANCZOS)

    x0 = (size - w) // 2
    y0 = (size - h) // 2
    canvas.paste(scaled_art, (x0, y0), scaled_art)
    return canvas

def main():
    print("=== Bloom Brand Assets Generation (Exact Original Artwork) ===")
    extract_original_logo()
    artwork_crop = extract_clean_artwork(ORIGINAL_LOGO_TMP)

    # 1. Full-Bleed 512x512 Master Logo
    # Safe zone for 512 canvas: 62% safe zone diameter is ~317px (radius 158.7px)
    # Scaling factor of 1.10 yields ~215x234, sitting comfortably inside central safe zone
    print("Generating full-bleed master logos (512x512)...")
    fullbleed_512 = render_artwork_on_canvas(artwork_crop, canvas_size=512, scale_factor=1.10, bg_color=PLUM_RGBA)

    bloom_logo_path = os.path.join(REPO_ROOT, "androidApp/src/main/res/drawable/bloom_logo.png")
    app_logo_path = os.path.join(REPO_ROOT, "shared/src/commonMain/composeResources/drawable/app_logo.png")

    os.makedirs(os.path.dirname(bloom_logo_path), exist_ok=True)
    os.makedirs(os.path.dirname(app_logo_path), exist_ok=True)

    fullbleed_512.save(bloom_logo_path)
    print(f"Saved {bloom_logo_path}")
    fullbleed_512.save(app_logo_path)
    print(f"Saved {app_logo_path}")

    # 2. Android Adaptive Icon Foreground PNGs
    # In Android adaptive icons, canvas is 108dp x 108dp. Safe zone is 66dp.
    # At xxxhdpi (4x): 432x432 canvas, 264px safe zone.
    # Motif scaled by 432/512 * 1.10 = 0.928 yields ~181x198, inside 264px safe zone.
    print("Generating adaptive icon foreground drawables...")
    # Density specifications: (density_name, canvas_px)
    fg_densities = [
        ("drawable-mdpi", 108),
        ("drawable-hdpi", 162),
        ("drawable-xhdpi", 216),
        ("drawable-xxhdpi", 324),
        ("drawable-xxxhdpi", 432),
    ]

    for dir_name, c_size in fg_densities:
        target_dir = os.path.join(REPO_ROOT, "androidApp/src/main/res", dir_name)
        os.makedirs(target_dir, exist_ok=True)
        scale = (c_size / 512.0) * 1.10
        fg_img = render_artwork_on_canvas(artwork_crop, canvas_size=c_size, scale_factor=scale, bg_color=None)
        out_file = os.path.join(target_dir, "ic_launcher_foreground.png")
        fg_img.save(out_file)
        print(f"Saved {out_file} ({c_size}x{c_size})")

    # Also save primary fallback drawable/ic_launcher_foreground.png at 432x432 (xxxhdpi)
    fallback_fg = os.path.join(REPO_ROOT, "androidApp/src/main/res/drawable/ic_launcher_foreground.png")
    render_artwork_on_canvas(artwork_crop, canvas_size=432, scale_factor=(432/512.0)*1.10, bg_color=None).save(fallback_fg)
    print(f"Saved primary fallback {fallback_fg}")

    # Remove conflicting ic_launcher_foreground.xml if present
    xml_conflict = os.path.join(REPO_ROOT, "androidApp/src/main/res/drawable/ic_launcher_foreground.xml")
    if os.path.exists(xml_conflict):
        os.remove(xml_conflict)
        print(f"Removed conflicting vector XML: {xml_conflict}")

    # 3. Pre-scaled Legacy Mipmap Densities
    # Standard: ic_launcher.png (squircle)
    # Round: ic_launcher_round.png (circle)
    legacy_densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    print("Generating pre-scaled legacy mipmap densities...")
    for dir_name, size in legacy_densities.items():
        target_dir = os.path.join(REPO_ROOT, "androidApp/src/main/res", dir_name)
        os.makedirs(target_dir, exist_ok=True)

        standard_icon = generate_legacy_icon(artwork_crop, size, shape="squircle")
        round_icon = generate_legacy_icon(artwork_crop, size, shape="round")

        std_path = os.path.join(target_dir, "ic_launcher.png")
        rnd_path = os.path.join(target_dir, "ic_launcher_round.png")

        standard_icon.save(std_path)
        round_icon.save(rnd_path)
        print(f"Saved {std_path} and {rnd_path} ({size}x{size})")

    print("=== All Brand Assets Generated Successfully! ===")

if __name__ == "__main__":
    main()
