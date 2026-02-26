"""Persistent JSON progress tracker for crash recovery.

Tracks which XIDs have been probed and ingested per NAD, enabling
the scraper to resume after crashes without re-probing thousands
of records.
"""

import json
import logging
import os
from datetime import datetime, timezone

log = logging.getLogger(__name__)

_AUTOSAVE_INTERVAL = 50


class ProgressTracker:
    """Track probe/ingest progress per NAD, persisted to a JSON file.

    If path is None, operates as a no-op (all queries return False,
    all mutations are silently ignored).

    File format::

        {
          "1583": {
            "started_at": "2026-02-26T14:00:00+00:00",
            "probed_xids": ["xid-1", "xid-2"],
            "hidden_xids": ["xid-2"],
            "ingested_xids": ["xid-1", "xid-2"]
          }
        }
    """

    def __init__(self, path: str | None = None):
        self._path = path
        self._data: dict[str, dict] = {}
        self._dirty = 0
        if path and os.path.exists(path):
            with open(path) as f:
                self._data = json.load(f)
            log.info("Loaded progress from %s (%d NADs)", path, len(self._data))

    def _ensure_nad(self, nad: str) -> dict:
        nad = str(nad)
        if nad not in self._data:
            self._data[nad] = {
                "started_at": datetime.now(timezone.utc).isoformat(),
                "probed_xids": [],
                "hidden_xids": [],
                "ingested_xids": [],
            }
        return self._data[nad]

    # -- queries ---------------------------------------------------------------

    def is_probed(self, nad: int | str, xid: str) -> bool:
        if self._path is None:
            return False
        entry = self._data.get(str(nad))
        return entry is not None and xid in entry.get("probed_xids", [])

    def is_ingested(self, nad: int | str, xid: str) -> bool:
        if self._path is None:
            return False
        entry = self._data.get(str(nad))
        return entry is not None and xid in entry.get("ingested_xids", [])

    def get_hidden_xids(self, nad: int | str) -> list[str]:
        entry = self._data.get(str(nad))
        return list(entry.get("hidden_xids", [])) if entry else []

    def probed_count(self, nad: int | str) -> int:
        entry = self._data.get(str(nad))
        return len(entry.get("probed_xids", [])) if entry else 0

    # -- mutations -------------------------------------------------------------

    def mark_probed(self, nad: int | str, xid: str) -> None:
        if self._path is None:
            return
        entry = self._ensure_nad(nad)
        if xid not in entry["probed_xids"]:
            entry["probed_xids"].append(xid)
            self._dirty += 1
            if self._dirty >= _AUTOSAVE_INTERVAL:
                self.save()

    def mark_hidden(self, nad: int | str, xid: str) -> None:
        if self._path is None:
            return
        entry = self._ensure_nad(nad)
        if xid not in entry["hidden_xids"]:
            entry["hidden_xids"].append(xid)

    def mark_ingested(self, nad: int | str, xid: str) -> None:
        if self._path is None:
            return
        entry = self._ensure_nad(nad)
        if xid not in entry["ingested_xids"]:
            entry["ingested_xids"].append(xid)
            self._dirty += 1
            if self._dirty >= _AUTOSAVE_INTERVAL:
                self.save()

    # -- persistence -----------------------------------------------------------

    def save(self) -> None:
        if self._path is None:
            return
        tmp = self._path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self._data, f, indent=2)
        os.replace(tmp, self._path)
        self._dirty = 0
        log.debug("Progress saved to %s", self._path)
