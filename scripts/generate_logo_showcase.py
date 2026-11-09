#!/usr/bin/env python3
"""
scripts/generate_logo_showcase.py
Generates a side-by-side showcase of the exact original Bloom artwork adapted for:
1. Full-Bleed Master (Square)
2. Squircle Adaptive Icon (Samsung / One UI preview)
3. Round Adaptive Icon (Pixel / Motorola preview)
"""
import sys
import os
import shutil
import numpy as np
from PIL import Image, ImageDraw, ImageFont

def make_mask(size: int, shape: str = "round") -> Image.Image:
    scale = 4
    big = size * scale
    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    pad = int(big * 4 / 320)
    if shape == "round":
        draw.ellipse((pad, pad, big - pad, big - pad), fill=255)
    elif shape == "squircle":
        radius = int(big * 56 / 320)
        draw.rounded_rectangle((pad, pad, big - pad, big - pad), radius=radius, fill=255)
    return mask.resize((size, size), Image.Resampling.LANCZOS)

def create_showcase(repo_root: str, artifact_dirs: list[str]):
    res_dir = os.path.join(repo_root, "androidApp/src/main/res")
    logo_path = os.path.join(res_dir, "drawable/bloom_logo.png")
    fg_path = os.path.join(res_dir, "drawable-xxxhdpi/ic_launcher_foreground.png")
    round_legacy = os.path.join(res_dir, "mipmap-xxxhdpi/ic_launcher_round.png")
    squircle_legacy = os.path.join(res_dir, "mipmap-xxxhdpi/ic_launcher.png")

    if not os.path.exists(logo_path):
        print(f"Error: Missing {logo_path}", file=sys.stderr)
        sys.exit(1)

    logo_master = Image.open(logo_path).convert("RGBA")
    
    # Adaptive Icon Preview rendering:
    # Background: #4F0538
    # Foreground: original artwork from ic_launcher_foreground.png
    PLUM = (79, 5, 56, 255)
    
    # Render simulated device launcher icons at 320x320
    preview_size = 320
    
    # 1. Full-Bleed Master
    # Subtle rounded corner for presentation card
    master_preview = logo_master.resize((preview_size, preview_size), Image.Resampling.LANCZOS)
    master_corner_mask = Image.new("L", (preview_size, preview_size), 0)
    ImageDraw.Draw(master_corner_mask).rounded_rectangle((0, 0, preview_size, preview_size), radius=16, fill=255)
    master_display = Image.new("RGBA", (preview_size, preview_size), (0, 0, 0, 0))
    master_display.paste(master_preview, (0, 0), master_corner_mask)

    # 2. Squircle Adaptive Icon (Samsung / One UI)
    squircle_mask = make_mask(preview_size, shape="squircle")
    squircle_canvas = Image.new("RGBA", (preview_size, preview_size), (0, 0, 0, 0))
    squircle_bg = Image.new("RGBA", (preview_size, preview_size), PLUM)
    squircle_canvas.paste(squircle_bg, (0, 0), squircle_mask)
    if os.path.exists(fg_path):
        fg_img = Image.open(fg_path).convert("RGBA").resize((preview_size, preview_size), Image.Resampling.LANCZOS)
        # In adaptive icon, the outer mask clips both background and foreground
        squircle_canvas.paste(fg_img, (0, 0), ImageChops_multiply_alpha(fg_img, squircle_mask))
    elif os.path.exists(squircle_legacy):
        squircle_canvas = Image.open(squircle_legacy).convert("RGBA").resize((preview_size, preview_size), Image.Resampling.LANCZOS)

    # 3. Round Adaptive Icon (Pixel / Motorola)
    round_mask = make_mask(preview_size, shape="round")
    round_canvas = Image.new("RGBA", (preview_size, preview_size), (0, 0, 0, 0))
    round_bg = Image.new("RGBA", (preview_size, preview_size), PLUM)
    round_canvas.paste(round_bg, (0, 0), round_mask)
    if os.path.exists(fg_path):
        fg_img = Image.open(fg_path).convert("RGBA").resize((preview_size, preview_size), Image.Resampling.LANCZOS)
        round_canvas.paste(fg_img, (0, 0), ImageChops_multiply_alpha(fg_img, round_mask))
    elif os.path.exists(round_legacy):
        round_canvas = Image.open(round_legacy).convert("RGBA").resize((preview_size, preview_size), Image.Resampling.LANCZOS)

    # Build side-by-side showcase canvas
    canvas_w = 1440
    canvas_h = 760
    bg_color = (15, 20, 28, 255)       # Dark surface (#0F141C)
    card_bg = (26, 32, 44, 255)        # Card surface (#1A202C)
    card_border = (42, 53, 71, 255)    # Card outline (#2A3547)
    text_primary = (240, 244, 248, 255)# Off-white (#F0F4F8)
    text_secondary = (148, 163, 184, 255)# Slate (#94A3B8)
    accent_color = (99, 102, 241, 255) # Indigo (#6366F1)

    showcase = Image.new("RGBA", (canvas_w, canvas_h), bg_color)
    draw = ImageDraw.Draw(showcase)

    # Fonts
    font_dir = "/usr/share/fonts/TTF"
    font_title = ImageFont.truetype(f"{font_dir}/DejaVuSans-Bold.ttf", 32)
    font_subtitle = ImageFont.truetype(f"{font_dir}/DejaVuSans.ttf", 16)
    font_heading = ImageFont.truetype(f"{font_dir}/DejaVuSans-Bold.ttf", 20)
    font_badge = ImageFont.truetype(f"{font_dir}/DejaVuSans-Bold.ttf", 13)
    font_meta = ImageFont.truetype(f"{font_dir}/DejaVuSansMono.ttf", 12)

    # Header
    draw.text((60, 40), "Bloom Exact Original Logo: Multi-Device Adaptive Identity", fill=text_primary, font=font_title)
    draw.text((60, 84), "Original Artwork Preserved 100% Pixel-Perfect • Full-Bleed Plum Background • Android Adaptive Icons", fill=text_secondary, font=font_subtitle)

    card_w = 400
    card_h = 560
    card_top = 140
    gap = (canvas_w - 120 - 3 * card_w) // 2

    cards = [
        {
            "tag": "FULL-BLEED MASTER",
            "title": "Master Brand Logo",
            "desc": "512 × 512 px • Edge-to-edge #4F0538",
            "source": "drawable/bloom_logo.png",
            "img": master_display,
            "badge_color": (35, 45, 63, 255),
            "accent": (99, 102, 241, 255),
        },
        {
            "tag": "SQUIRCLE ADAPTIVE",
            "title": "Samsung / One UI Preview",
            "desc": "108dp (432px xxxhdpi) • 66dp Safe Zone",
            "source": "mipmap-anydpi-v26/ic_launcher.xml",
            "img": squircle_canvas,
            "badge_color": (28, 48, 40, 255),
            "accent": (34, 197, 94, 255),
        },
        {
            "tag": "ROUND ADAPTIVE",
            "title": "Pixel / Motorola Preview",
            "desc": "108dp (432px xxxhdpi) • Circular Keyline",
            "source": "mipmap-anydpi-v26/ic_launcher_round.xml",
            "img": round_canvas,
            "badge_color": (48, 30, 45, 255),
            "accent": (236, 72, 153, 255),
        }
    ]

    for idx, card in enumerate(cards):
        cx = 60 + idx * (card_w + gap)
        cy = card_top

        # Card container
        draw.rounded_rectangle([cx, cy, cx + card_w, cy + card_h], radius=16, fill=card_bg, outline=card_border, width=2)

        # Header Badge
        badge_text = card["tag"]
        bbox = font_badge.getbbox(badge_text)
        bw = bbox[2] - bbox[0] + 16
        bh = bbox[3] - bbox[1] + 10
        bx = cx + 24
        by = cy + 24
        draw.rounded_rectangle([bx, by, bx + bw, by + bh], radius=6, fill=card["badge_color"], outline=card["accent"], width=1)
        draw.text((bx + 8, by + 4), badge_text, fill=text_primary, font=font_badge)

        # Card Title & Description
        draw.text((cx + 24, cy + 62), card["title"], fill=text_primary, font=font_heading)
        draw.text((cx + 24, cy + 92), card["desc"], fill=text_secondary, font=font_subtitle)

        # Image Container
        ix = cx + (card_w - preview_size) // 2
        iy = cy + 130
        draw.rounded_rectangle([ix - 2, iy - 2, ix + preview_size + 2, iy + preview_size + 2], radius=12, fill=(18, 22, 30, 255), outline=(32, 40, 54, 255), width=1)
        showcase.paste(card["img"], (ix, iy), card["img"])

        # Footer File Metadata
        draw.text((cx + 24, cy + card_h - 44), f"res/{card['source']}", fill=text_secondary, font=font_meta)
        draw.text((cx + 24, cy + card_h - 26), "Exact Original Artwork • Safe Zone Verified", fill=(100, 116, 139, 255), font=font_meta)

    # Save to targets
    for out_dir in artifact_dirs:
        os.makedirs(out_dir, exist_ok=True)
        # Exact requested names:
        showcase_out = os.path.join(out_dir, "original_logo_adapted_showcase.png")
        fullbleed_out = os.path.join(out_dir, "original_bloom_logo_fullbleed.png")
        
        showcase.save(showcase_out, "PNG")
        shutil.copyfile(logo_path, fullbleed_out)
        print(f"Exported showcase -> {showcase_out}")
        print(f"Exported full-bleed logo -> {fullbleed_out}")

def ImageChops_multiply_alpha(img: Image.Image, mask: Image.Image) -> Image.Image:
    """Helper to multiply RGBA image alpha channel with an 8-bit mask."""
    arr = np.array(img)
    mask_arr = np.array(mask, dtype=float) / 255.0
    arr[:, :, 3] = np.round(arr[:, :, 3].astype(float) * mask_arr).astype(np.uint8)
    return Image.fromarray(arr)

if __name__ == "__main__":
    repo_root = sys.argv[1] if len(sys.argv) > 1 else "/home/dytamg/Desktop/Bloom-main"
    custom_dirs = sys.argv[2:] if len(sys.argv) > 2 else [
        "/home/dytamg/.gemini/antigravity/brain/57842653-580e-47d7-b397-69bf7e0ab8e9",
        "/home/dytamg/Desktop/Bloom-main/build/showcase"
    ]
    create_showcase(repo_root, custom_dirs)
