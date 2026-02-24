"""Zoomify tile download and stitching.

Ported from download_scans.py -- reconstructs full-resolution images
from Zoomify tile pyramids served by the VadeMeCum mrimage service.
"""

import io
import re
import math
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

import urllib.request

from PIL import Image

from .config import get_config
from .session import VadeMeCumSession, MRIMAGE_BASE

log = logging.getLogger(__name__)

# Zoomify base path for Czech National Archives
ZOOMIFY_PATH = "zoomify//cz/archives/CZ-100000010/inventare/dao/images"


def get_tile_info(
    folder: str, uuid: str, user_agent: str | None = None,
) -> dict | None:
    """Fetch Zoomify ImageProperties.xml and parse tile metadata.

    Args:
        folder: Image folder number.
        uuid: Image UUID.
        user_agent: Optional UA string.

    Returns:
        Dict with width, height, tile_size, num_tiles, or None on failure.
    """
    cfg = get_config()
    ua = user_agent or cfg.user_agent

    url = f"{MRIMAGE_BASE}/{ZOOMIFY_PATH}/{folder}/{uuid}.jpg/ImageProperties.xml"
    try:
        req = urllib.request.Request(url)
        req.add_header("User-Agent", ua)
        resp = urllib.request.urlopen(req, timeout=15)
        xml = resp.read().decode()
        m = re.search(
            r'WIDTH="(\d+)"\s+HEIGHT="(\d+)"\s+NUMTILES="(\d+)"'
            r'\s+NUMIMAGES="\d+"\s+VERSION="[^"]+"\s+TILESIZE="(\d+)"',
            xml,
        )
        if m:
            return {
                "width": int(m.group(1)),
                "height": int(m.group(2)),
                "num_tiles": int(m.group(3)),
                "tile_size": int(m.group(4)),
            }
    except Exception as e:
        log.warning("Failed to fetch tile info for %s/%s: %s", folder, uuid, e)
    return None


def _download_tile_direct(url: str, user_agent: str) -> bytes | None:
    """Download a single Zoomify tile (no session needed)."""
    try:
        req = urllib.request.Request(url)
        req.add_header("User-Agent", user_agent)
        resp = urllib.request.urlopen(req, timeout=15)
        return resp.read()
    except Exception:
        return None


def download_and_stitch(
    folder: str,
    uuid: str,
    session: VadeMeCumSession | None = None,
    max_workers: int = 8,
) -> Image.Image | None:
    """Download all Zoomify tiles at max zoom level and stitch into a full image.

    Args:
        folder: Image folder number.
        uuid: Image UUID.
        session: Optional session (tiles are publicly accessible, so not required).
        max_workers: Thread pool size for concurrent tile downloads.

    Returns:
        PIL Image at full resolution, or None on failure.
    """
    cfg = get_config()
    ua = cfg.user_agent

    props = get_tile_info(folder, uuid, user_agent=ua)
    if not props:
        return None

    w, h, ts = props["width"], props["height"], props["tile_size"]

    # Calculate zoom levels
    max_level = 0
    temp = max(w, h)
    while temp > ts:
        temp = (temp + 1) // 2
        max_level += 1

    # Count tiles across all lower levels to compute TileGroup assignments
    base_tile_idx = 0
    for level in range(max_level):
        lw, lh = w, h
        for _ in range(max_level - level):
            lw = (lw + 1) // 2
            lh = (lh + 1) // 2
        base_tile_idx += math.ceil(lw / ts) * math.ceil(lh / ts)

    cols = math.ceil(w / ts)
    rows = math.ceil(h / ts)

    zoomify_base = f"{MRIMAGE_BASE}/{ZOOMIFY_PATH}/{folder}/{uuid}.jpg"

    # Build tile URL list with grid positions
    tile_jobs = []
    for row in range(rows):
        for col in range(cols):
            tile_idx = base_tile_idx + row * cols + col
            tile_group = tile_idx // 256
            tile_url = (
                f"{zoomify_base}/TileGroup{tile_group}/{max_level}-{col}-{row}.jpg"
            )
            tile_jobs.append((tile_url, col, row))

    # Download tiles concurrently
    tile_results: dict[tuple[int, int], bytes] = {}

    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {
            pool.submit(_download_tile_direct, url, ua): (col, row)
            for url, col, row in tile_jobs
        }
        for future in as_completed(futures):
            col, row = futures[future]
            data = future.result()
            if data:
                tile_results[(col, row)] = data

    if not tile_results:
        log.warning("No tiles downloaded for %s/%s", folder, uuid)
        return None

    # Stitch tiles into full image
    full_img = Image.new("RGB", (w, h), (255, 255, 255))
    for (col, row), data in tile_results.items():
        tile_img = Image.open(io.BytesIO(data))
        full_img.paste(tile_img, (col * ts, row * ts))

    log.debug("Stitched %s/%s: %dx%d from %d tiles", folder, uuid, w, h, len(tile_results))
    return full_img


def image_to_jpeg_bytes(img: Image.Image, quality: int = 95) -> bytes:
    """Convert a PIL Image to JPEG bytes."""
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()
