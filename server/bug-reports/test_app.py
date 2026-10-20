import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from app import HEADER, create_app


def bug_bytes():
    output = io.BytesIO()
    output.write(HEADER)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.properties", "game=alpha-0.2.4\n")
        archive.writestr("description.txt", "Проверка загрузки")
    return output.getvalue()


class ReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.app = create_app(self.root)
        self.app.testing = True
        self.client = self.app.test_client()

    def tearDown(self):
        self.temp.cleanup()

    def upload(self, body=None, name="test.bug", meta=None):
        with self.client.post("/upload", data={
                "meta": json.dumps(meta if meta is not None else {"game": "alpha-0.2.4"}),
                "bug": (io.BytesIO(bug_bytes() if body is None else body), name),
        }) as response:
            response.get_data()
            response.request.input_stream.close()
            return response

    def test_round_trip_and_duplicate_names(self):
        first = self.upload()
        second = self.upload()
        self.assertEqual(first.status_code, 201)
        self.assertNotEqual(first.json["id"], second.json["id"])
        self.assertEqual(first.json["size"], len(bug_bytes()))
        response = self.client.get("/reports/" + first.json["id"] + "/download")
        self.assertEqual(response.data, bug_bytes())
        self.assertIn("attachment", response.headers["Content-Disposition"])
        response.close()

    def test_invalid_inputs_leave_no_files(self):
        for body, name, meta in [(b"not a report", "x.bug", {}),
                                 (bug_bytes(), "x.html", {}), (bug_bytes(), "x.bug", [])]:
            self.assertEqual(self.upload(body, name, meta).status_code, 400)
        self.assertEqual(list(self.root.glob("*.bug")), [])
        self.assertEqual(list(self.root.glob("*.part")), [])
        self.assertEqual(self.client.post("/upload", data={"meta": "{"}).status_code, 400)

    def test_large_file_spools_to_disk(self):
        output = io.BytesIO()
        output.write(HEADER)
        with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("attachments/world.bin", bytes(range(256)) * 8192)
        response = self.upload(body=output.getvalue())
        self.assertEqual(response.status_code, 201)
        downloaded = self.client.get("/reports/" + response.json["id"] + "/download")
        self.assertEqual(downloaded.data, output.getvalue())
        downloaded.close()

    def test_metadata_limit(self):
        self.assertEqual(self.upload(meta={"text": "x" * 65536}).status_code, 413)

    def test_names_cannot_escape_storage_or_inject_html(self):
        response = self.upload(name="../../<script>alert(1)</script>.bug", meta={"game": "<script>bad</script>"})
        self.assertEqual(response.status_code, 201)
        self.assertTrue((self.root / (response.json["id"] + ".bug")).is_file())
        html = self.client.get("/reports").get_data(as_text=True)
        self.assertNotIn("<script>", html)
        self.assertIn("&lt;script&gt;bad&lt;/script&gt;", html)
        self.assertEqual(self.client.get("/reports/..%2F..%2Fetc%2Fpasswd/download").status_code, 404)

    def test_file_size_limit_cleans_partial_file(self):
        with patch("app.MAX_BUG_BYTES", 20):
            self.assertEqual(self.upload().status_code, 413)
        self.assertFalse(list(self.root.glob("*.part")))
        self.assertFalse(list(self.root.glob("*.bug")))

    def test_storage_quota(self):
        with patch("app.MAX_STORAGE_BYTES", 0):
            self.assertEqual(self.upload().status_code, 507)

    def test_pagination(self):
        with patch("app.PAGE_SIZE", 1):
            self.upload(name="first.bug")
            self.upload(name="second.bug")
            html = self.client.get("/reports").get_data(as_text=True)
            self.assertEqual(html.count('class="download"'), 1)
            self.assertIn("page=2", html)
            self.assertEqual(self.client.get("/reports?page=2").get_data(as_text=True).count('class="download"'), 1)

    def test_unknown_download_and_status(self):
        self.assertEqual(self.client.get("/reports/" + "0" * 32 + "/download").status_code, 404)
        response = self.client.get("/api/status")
        self.assertEqual(response.json["name"], "Genomeia")
        self.assertTrue(response.json["time"].endswith("Z"))


if __name__ == "__main__":
    unittest.main()
