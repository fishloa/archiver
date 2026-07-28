"""Ingest pages from a local zip file (Invenio's whole-file download).

Invenio's `{base}/download/<uuid>` endpoint returns a zip of full-size page
JPEGs. Operators often already have this zip on disk (it can be ~450MB for
a 216-page record) — this module lets the scraper ingest directly from it
instead of re-downloading every page image individually.

Zip entries are named ``<SIGNATURE_WITH_UNDERSCORES>_<NNNN>.jpg``, e.g.
``R_43_II_1326_0001.jpg`` .. ``R_43_II_1326_0216.jpg``. The zip's own
filename (e.g. ``R 43-II_1326.zip``) uses a different, unreliable form and
must never be used to derive the signature.
"""

import re
import zipfile
from collections import Counter

_PAGE_NUM_RE = re.compile(r"_(\d+)\.jpe?g$", re.IGNORECASE)


def list_zip_pages(zip_path: str) -> list[tuple[int, str]]:
    """Return ``[(page_number, zip_entry_name), ...]`` sorted by page number.

    Page numbers are parsed from the numeric suffix in each entry's
    filename — never from zip entry order or lexical sort (which would
    order "0010" before "0002" incorrectly... actually the reverse: lexical
    sort of zero-padded numbers is fine, but we do not rely on it, since
    entry order within the zip itself is not guaranteed to be numeric at
    all).

    Raises ValueError if:
      * an entry's filename doesn't match the expected pattern,
      * any page number appears more than once (duplicates),
      * the page numbers are not contiguous starting at 1 (gaps).
    """
    with zipfile.ZipFile(zip_path) as zf:
        entries = [n for n in zf.namelist() if not n.endswith("/")]

    numbered: list[tuple[int, str]] = []
    for name in entries:
        m = _PAGE_NUM_RE.search(name)
        if not m:
            raise ValueError(
                f"Zip entry '{name}' does not match the expected "
                "<SIGNATURE>_<NNNN>.jpg naming pattern"
            )
        numbered.append((int(m.group(1)), name))

    numbers = [n for n, _ in numbered]
    counts = Counter(numbers)
    duplicates = sorted(n for n, c in counts.items() if c > 1)
    if duplicates:
        raise ValueError(f"Duplicate page numbers in zip {zip_path!r}: {duplicates}")

    numbered.sort(key=lambda t: t[0])

    expected = set(range(1, len(numbered) + 1))
    actual = set(numbers)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing or unexpected:
        raise ValueError(
            f"Zip {zip_path!r} page numbering is not contiguous from 1: "
            f"missing={missing} unexpected={unexpected}"
        )

    return numbered


def read_zip_page(zip_path: str, entry_name: str) -> bytes:
    """Read a single zip entry's bytes into memory (does not extract to disk)."""
    with zipfile.ZipFile(zip_path) as zf, zf.open(entry_name) as f:
        return f.read()
