"""Tests for the progress tracker."""

import json
import os

from scraper_cz.progress import ProgressTracker


class TestProgressTracker:

    def test_noop_when_no_path(self):
        """None path = no-op tracker; all queries return False."""
        pt = ProgressTracker(None)
        pt.mark_probed(1583, "xid-1")
        pt.mark_hidden(1583, "xid-1")
        pt.mark_ingested(1583, "xid-1")
        assert not pt.is_probed(1583, "xid-1")
        assert not pt.is_ingested(1583, "xid-1")
        assert pt.get_hidden_xids(1583) == []
        pt.save()  # should not raise

    def test_mark_and_query(self, tmp_path):
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)

        assert not pt.is_probed(1583, "xid-1")
        pt.mark_probed(1583, "xid-1")
        assert pt.is_probed(1583, "xid-1")
        assert pt.probed_count(1583) == 1

        pt.mark_hidden(1583, "xid-1")
        assert pt.get_hidden_xids(1583) == ["xid-1"]

        pt.mark_ingested(1583, "xid-1")
        assert pt.is_ingested(1583, "xid-1")

    def test_save_and_reload(self, tmp_path):
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        pt.mark_probed(1583, "xid-1")
        pt.mark_hidden(1583, "xid-1")
        pt.mark_ingested(1583, "xid-1")
        pt.save()

        # Reload from file
        pt2 = ProgressTracker(path)
        assert pt2.is_probed(1583, "xid-1")
        assert pt2.get_hidden_xids(1583) == ["xid-1"]
        assert pt2.is_ingested(1583, "xid-1")

    def test_atomic_write(self, tmp_path):
        """Save uses atomic write (tmp + rename)."""
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        pt.mark_probed(100, "a")
        pt.save()

        assert os.path.exists(path)
        assert not os.path.exists(path + ".tmp")
        with open(path) as f:
            data = json.load(f)
        assert "100" in data

    def test_no_duplicates(self, tmp_path):
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        pt.mark_probed(1, "x")
        pt.mark_probed(1, "x")
        pt.mark_probed(1, "x")
        assert pt.probed_count(1) == 1

    def test_multiple_nads(self, tmp_path):
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        pt.mark_probed(1583, "a")
        pt.mark_probed(1464, "b")
        assert pt.is_probed(1583, "a")
        assert not pt.is_probed(1583, "b")
        assert pt.is_probed(1464, "b")
        assert not pt.is_probed(1464, "a")

    def test_autosave(self, tmp_path):
        """Should auto-save after 50 probes."""
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        for i in range(50):
            pt.mark_probed(1, f"xid-{i}")

        # After 50 mark_probed calls, file should exist (auto-saved)
        assert os.path.exists(path)
        with open(path) as f:
            data = json.load(f)
        assert len(data["1"]["probed_xids"]) == 50

    def test_str_and_int_nad_interchangeable(self, tmp_path):
        path = str(tmp_path / "progress.json")
        pt = ProgressTracker(path)
        pt.mark_probed(1583, "x")
        assert pt.is_probed("1583", "x")
        assert pt.is_probed(1583, "x")
