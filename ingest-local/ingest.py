#!/usr/bin/env python3
"""Interactive local ingestor for the archiver pipeline.

Uploads a directory/zip of JPG scans to archiver.icomb.place, prompts
for basic metadata, and triggers OCR + downstream processing.

Usage:
    python ingest.py /path/to/scans-or-zipfile
    python ingest.py                            # prompts for path
"""

import argparse
import json
import sys
import zipfile
from pathlib import Path

import httpx

BACKEND_URL = "https://archiver.icomb.place"
TIMEOUT = 60.0


def get_client() -> httpx.Client:
    return httpx.Client(base_url=BACKEND_URL, timeout=TIMEOUT)


# ── Archive CRUD helpers ─────────────────────────────────────────────────

def list_archives(client: httpx.Client) -> list[dict]:
    resp = client.get("/api/ingest/archives")
    resp.raise_for_status()
    return resp.json()


def get_archive(client: httpx.Client, archive_id: int) -> dict | None:
    resp = client.get(f"/api/ingest/archives/{archive_id}")
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.json()


def create_archive(client: httpx.Client, name: str, country: str) -> dict:
    resp = client.post("/api/ingest/archives", json={"name": name, "country": country})
    resp.raise_for_status()
    return resp.json()


def update_archive(client: httpx.Client, archive_id: int, **fields) -> dict:
    resp = client.put(f"/api/ingest/archives/{archive_id}", json=fields)
    resp.raise_for_status()
    return resp.json()


def delete_archive(client: httpx.Client, archive_id: int) -> bool:
    resp = client.delete(f"/api/ingest/archives/{archive_id}")
    return resp.status_code == 204


def find_or_create_archive(client: httpx.Client, name: str, country: str) -> dict:
    """Find archive by name or create it."""
    for a in list_archives(client):
        if a["name"].lower() == name.lower():
            return a
    archive = create_archive(client, name, country)
    print(f"  Created archive [{archive['id']}] {archive['name']} ({archive['country']})")
    return archive


# ── Interactive prompts ──────────────────────────────────────────────────

def prompt_archive(client: httpx.Client) -> int:
    archives = list_archives(client)
    print("\nExisting archives:")
    for a in archives:
        print(f"  [{a['id']}] {a['name']} ({a['country']})")
    print()
    while True:
        choice = input("Archive ID (or 'new' to create one): ").strip()
        if choice == "new":
            name = input("  Archive name: ").strip()
            country = input("  Country code (e.g. GB, AT, CZ): ").strip().upper()
            archive = create_archive(client, name, country)
            print(f"  Created archive [{archive['id']}] {archive['name']} ({archive['country']})")
            return archive["id"]
        try:
            aid = int(choice)
            if any(a["id"] == aid for a in archives):
                return aid
            print(f"  No archive with ID {aid}")
        except ValueError:
            print("  Enter a number or 'new'")


def prompt_metadata() -> dict:
    print("\n--- Record metadata ---")
    title = input("Title: ").strip()
    description = input("Description (optional): ").strip() or None
    date_range = input("Date range text (e.g. '1950-1955', optional): ").strip() or None
    ref_code = input("Reference code (optional): ").strip() or None
    source_url = input("Source URL (optional): ").strip() or None
    lang = input("Content language [en]: ").strip() or "en"
    metadata_lang = input("Metadata language [en]: ").strip() or "en"

    return {
        "title": title,
        "description": description,
        "dateRangeText": date_range,
        "referenceCode": ref_code,
        "sourceUrl": source_url,
        "lang": lang,
        "metadataLang": metadata_lang,
    }


# ── Image collection ─────────────────────────────────────────────────────

def collect_images(path: Path) -> list[tuple[str, bytes]]:
    """Return sorted list of (filename, bytes) for all JPGs in path (dir or zip)."""
    images = []

    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as zf:
            for name in sorted(zf.namelist()):
                if name.lower().endswith((".jpg", ".jpeg")) and not name.startswith("__MACOSX"):
                    images.append((Path(name).name, zf.read(name)))
    elif path.is_dir():
        for f in sorted(path.iterdir()):
            if f.suffix.lower() in (".jpg", ".jpeg"):
                images.append((f.name, f.read_bytes()))
    else:
        print(f"Error: {path} is not a directory or zip file")
        sys.exit(1)

    return images


# ── Ingest API calls ─────────────────────────────────────────────────────

def create_record(client: httpx.Client, archive_id: int, source_id: str, meta: dict) -> int:
    body = {
        "archiveId": archive_id,
        "sourceSystem": "local-ingest",
        "sourceRecordId": source_id,
        **{k: v for k, v in meta.items() if v is not None},
    }
    resp = client.post("/api/ingest/records", json=body)
    resp.raise_for_status()
    return resp.json()["id"]


def upload_page(client: httpx.Client, record_id: int, seq: int, filename: str, data: bytes):
    files = {
        "image": (filename, data, "image/jpeg"),
        "metadata": (
            "metadata.json",
            json.dumps({"pageLabel": Path(filename).stem}).encode(),
            "application/json",
        ),
    }
    resp = client.post(
        f"/api/ingest/records/{record_id}/pages",
        files=files,
        params={"seq": seq},
    )
    resp.raise_for_status()
    return resp.json()


def complete_ingest(client: httpx.Client, record_id: int):
    resp = client.post(f"/api/ingest/records/{record_id}/complete")
    resp.raise_for_status()
    return resp.json()


# ── Main ─────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Ingest local scans into archiver")
    parser.add_argument("path", nargs="?", help="Path to directory of JPGs or a zip file")
    args = parser.parse_args()

    if args.path:
        scan_path = Path(args.path)
    else:
        raw = input("Path to JPGs directory or zip file: ").strip()
        scan_path = Path(raw)

    if not scan_path.exists():
        print(f"Error: {scan_path} does not exist")
        sys.exit(1)

    images = collect_images(scan_path)
    if not images:
        print(f"Error: no JPG files found in {scan_path}")
        sys.exit(1)

    print(f"Found {len(images)} images:")
    for name, data in images:
        print(f"  {name} ({len(data) / 1024:.0f} KB)")

    client = get_client()

    archive_id = prompt_archive(client)
    meta = prompt_metadata()

    # Use the scan path stem as source record ID
    source_id = scan_path.stem
    print(f"\nSource record ID: {source_id}")
    override = input("Override source ID? (enter to keep): ").strip()
    if override:
        source_id = override

    # Confirm
    print(f"\n--- Summary ---")
    print(f"  Archive ID:  {archive_id}")
    print(f"  Source ID:   local-ingest/{source_id}")
    print(f"  Title:       {meta['title']}")
    print(f"  Language:    {meta['lang']}")
    print(f"  Pages:       {len(images)}")
    print()
    if input("Proceed? [Y/n] ").strip().lower() in ("n", "no"):
        print("Aborted.")
        sys.exit(0)

    # Create record
    print("\nCreating record...", end=" ", flush=True)
    record_id = create_record(client, archive_id, source_id, meta)
    print(f"OK (id={record_id})")

    # Upload pages
    for seq, (filename, data) in enumerate(images, start=1):
        print(f"  Uploading page {seq}/{len(images)}: {filename}...", end=" ", flush=True)
        upload_page(client, record_id, seq, filename, data)
        print("OK")

    # Complete ingest → triggers OCR pipeline
    print("Completing ingest (triggering OCR)...", end=" ", flush=True)
    result = complete_ingest(client, record_id)
    print(f"OK (status={result.get('status', '?')})")

    print(f"\nDone! Record ID: {record_id}")
    print(f"View at: https://archiver.icomb.place/records/{record_id}")
    print(f"API:     {BACKEND_URL}/api/v1/documents/{record_id}")
    client.close()


if __name__ == "__main__":
    main()
