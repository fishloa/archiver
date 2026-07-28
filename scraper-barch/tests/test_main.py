"""Tests for the scraper-barch CLI orchestration (ingest_record, ingest_from_zip, main)."""

import zipfile

import pytest

from scraper_barch.main import ingest_from_zip, ingest_record, load_uuid_file, main

from .conftest import SIGNATURE, UUID


class FakeSession:
    """Stand-in for InvenioSession — no network involved."""

    def __init__(self, html: str | None = None):
        self.html = html
        self.viewer_calls: list[str] = []
        self.downloaded: list[str] = []

    def get_viewer_page(self, uuid: str) -> str:
        self.viewer_calls.append(uuid)
        return self.html

    def download_image(self, url: str) -> bytes:
        self.downloaded.append(url)
        return b"\xff\xd8fake-jpeg-bytes"


class FakeClient:
    """Stand-in for BackendClient — no network involved."""

    def __init__(self, statuses: dict | None = None):
        self._statuses = statuses or {}
        self.created: list[tuple] = []
        self.uploaded: list[tuple] = []
        self.completed: list[str] = []

    def get_status(self, source_system, source_record_id):
        return self._statuses.get(source_record_id, {})

    def create_record(self, source_system, source_record_id, metadata):
        record_id = f"rec-{len(self.created) + 1}"
        self.created.append((source_system, source_record_id, metadata))
        return record_id

    def upload_page(self, record_id, seq, image_bytes, metadata=None):
        self.uploaded.append((record_id, seq, image_bytes, metadata))
        return f"page-{seq}"

    def complete_ingest(self, record_id):
        self.completed.append(record_id)

    def heartbeat(self, *a, **kw):
        pass


class TestIngestRecordDryRun:
    def test_dry_run_does_not_touch_backend_or_download_images(
        self, sample_viewer_html
    ):
        session = FakeSession(sample_viewer_html)
        result = ingest_record(None, session, UUID, dry_run=True)
        assert result == "ok"
        assert session.viewer_calls == [UUID]
        assert session.downloaded == []  # dry-run never downloads page images


class TestIngestRecordSkipAndForce:
    def test_skips_when_already_exists(self, sample_viewer_html):
        session = FakeSession(sample_viewer_html)
        client = FakeClient(statuses={SIGNATURE: {"status": "ocr_pending"}})
        result = ingest_record(client, session, UUID)
        assert result == "skipped"
        assert client.created == []
        assert session.downloaded == []

    def test_force_overrides_skip(self, sample_viewer_html):
        session = FakeSession(sample_viewer_html)
        client = FakeClient(statuses={SIGNATURE: {"status": "ocr_pending"}})
        result = ingest_record(client, session, UUID, force=True)
        assert result == "ok"
        assert len(client.created) == 1
        assert client.created[0][1] == SIGNATURE
        assert len(client.uploaded) == 3  # sample_data has 3 files
        assert client.completed == ["rec-1"]

    def test_ingests_when_no_existing_status(self, sample_viewer_html):
        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        result = ingest_record(client, session, UUID)
        assert result == "ok"
        assert len(client.created) == 1


class TestIngestRecordThrottling:
    def test_sleeps_between_each_page_download(self, sample_viewer_html, monkeypatch):
        sleeps = []
        monkeypatch.setattr("scraper_barch.main.time.sleep", lambda s: sleeps.append(s))

        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        result = ingest_record(client, session, UUID)

        assert result == "ok"
        assert len(client.uploaded) == 3
        # One throttle sleep per page download — never actually slept in the test.
        assert len(sleeps) == 3

    def test_max_pages_caps_downloads_and_sleeps(self, sample_viewer_html, monkeypatch):
        sleeps = []
        monkeypatch.setattr("scraper_barch.main.time.sleep", lambda s: sleeps.append(s))

        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        ingest_record(client, session, UUID, max_pages=1)

        assert len(client.uploaded) == 1
        assert len(sleeps) == 1


class TestIngestFromZip:
    def _make_zip(self, tmp_path, n_pages=3):
        zip_path = tmp_path / "record.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            for i in range(1, n_pages + 1):
                zf.writestr(f"R_43_II_1326_{i:04d}.jpg", f"page-{i}".encode())
        return str(zip_path)

    def test_dry_run_validates_without_uploading(self, tmp_path, sample_viewer_html):
        zip_path = self._make_zip(tmp_path, n_pages=3)
        session = FakeSession(sample_viewer_html)
        result = ingest_from_zip(
            None, session, zip_path, record_uuid=UUID, dry_run=True
        )
        assert result == "ok"

    def test_ingests_from_zip_with_uuid_metadata(self, tmp_path, sample_viewer_html):
        zip_path = self._make_zip(tmp_path, n_pages=3)
        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        result = ingest_from_zip(client, session, zip_path, record_uuid=UUID)
        assert result == "ok"
        assert len(client.uploaded) == 3
        assert client.created[0][1] == SIGNATURE
        # Pages come from the zip content, not from a download.
        assert session.downloaded == []
        # seqs must follow numeric order 1, 2, 3
        assert [u[1] for u in client.uploaded] == [1, 2, 3]

    def test_page_count_mismatch_raises(self, tmp_path, sample_viewer_html):
        """sample_viewer_html declares 3 files but the zip only has 2 pages."""
        zip_path = self._make_zip(tmp_path, n_pages=2)
        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        with pytest.raises(ValueError, match="does not match"):
            ingest_from_zip(client, session, zip_path, record_uuid=UUID)
        assert client.created == []

    def test_offline_signature_path_skips_cross_check_and_session(self, tmp_path):
        zip_path = self._make_zip(tmp_path, n_pages=2)
        client = FakeClient()
        # No session at all — the offline path never talks to Invenio.
        result = ingest_from_zip(
            client, None, zip_path, signature="R 43-II/1326 (offline)"
        )
        assert result == "ok"
        assert client.created[0][1] == "R 43-II/1326 (offline)"
        assert len(client.uploaded) == 2

    def test_skip_if_exists_and_force_override(self, tmp_path, sample_viewer_html):
        zip_path = self._make_zip(tmp_path, n_pages=3)
        session = FakeSession(sample_viewer_html)
        client = FakeClient(statuses={SIGNATURE: {"status": "complete"}})

        skipped = ingest_from_zip(client, session, zip_path, record_uuid=UUID)
        assert skipped == "skipped"
        assert client.created == []

        forced = ingest_from_zip(
            client, session, zip_path, record_uuid=UUID, force=True
        )
        assert forced == "ok"
        assert len(client.created) == 1

    def test_no_throttle_sleep_between_zip_page_uploads(
        self, tmp_path, sample_viewer_html, monkeypatch
    ):
        sleeps = []
        monkeypatch.setattr("scraper_barch.main.time.sleep", lambda s: sleeps.append(s))
        zip_path = self._make_zip(tmp_path, n_pages=3)
        session = FakeSession(sample_viewer_html)
        client = FakeClient()
        ingest_from_zip(client, session, zip_path, record_uuid=UUID)
        assert sleeps == []  # no throttle for local zip pages


class TestLoadUuidFile:
    def test_skips_blank_lines_and_comments(self, tmp_path):
        f = tmp_path / "uuids.txt"
        f.write_text(
            "\n".join(
                [
                    "# a comment",
                    "aaaa1111-bbbb-cccc-dddd-eeee00001111",
                    "",
                    "bbbb2222-cccc-dddd-eeee-ffff00002222  # trailing comment",
                    "   ",
                ]
            )
        )
        uuids = load_uuid_file(str(f))
        assert uuids == [
            "aaaa1111-bbbb-cccc-dddd-eeee00001111",
            "bbbb2222-cccc-dddd-eeee-ffff00002222",
        ]


class TestCliFromZipValidation:
    def test_from_zip_requires_uuid_or_signature(self, tmp_path, monkeypatch, capsys):
        zip_path = tmp_path / "record.zip"
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("R_TEST_0001.jpg", b"x")

        monkeypatch.setattr("sys.argv", ["scraper-barch", "--from-zip", str(zip_path)])
        with pytest.raises(SystemExit) as exc_info:
            main()
        assert exc_info.value.code != 0
        captured = capsys.readouterr()
        assert "--uuid" in captured.err or "--uuid" in captured.out

    def test_no_uuids_and_no_zip_is_an_error(self, monkeypatch):
        monkeypatch.setattr("sys.argv", ["scraper-barch"])
        with pytest.raises(SystemExit) as exc_info:
            main()
        assert exc_info.value.code != 0
