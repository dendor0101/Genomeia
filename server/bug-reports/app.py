"""Приём .bug без распаковки: файлы игроков никогда не исполняются на сервере."""

import hashlib
import json
import os
import re
import shutil
import sqlite3
import struct
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, abort, g, jsonify, render_template, request, send_file
from werkzeug.exceptions import HTTPException

MAX_BUG_BYTES = 100 * 1024 * 1024
MAX_STORAGE_BYTES = 5 * 1024 * 1024 * 1024
HEADER = b"genombug" + struct.pack(">I", 1)
PAGE_SIZE = 50


class InsufficientStorage(HTTPException):
    code = 507
    description = "Report storage is full"


def create_app(data_dir=None):
    app = Flask(__name__)
    app.config.update(
        MAX_CONTENT_LENGTH=MAX_BUG_BYTES + 128 * 1024,
        # Буфер multipart-парсера — 64 КиБ плюс хвост границы: лимит ровно 64 КиБ
        # ошибочно отвергает и большой файловый part, который должен уйти на диск.
        MAX_FORM_MEMORY_SIZE=256 * 1024,
        MAX_FORM_PARTS=2,
    )
    root = Path(data_dir or os.environ.get("GENOMEIA_DATA", "/var/lib/genomeia-bugs"))
    root.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(root / "reports.sqlite3") as db:
        db.execute("""CREATE TABLE IF NOT EXISTS reports (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL,
            created TEXT NOT NULL, sha256 TEXT NOT NULL, meta TEXT NOT NULL)""")
        db.execute("CREATE INDEX IF NOT EXISTS reports_created ON reports(created DESC, id DESC)")

    # Один процесс Gunicorn, четыре потока; только один приём одновременно. Так лимит
    # хранилища не обходится одновременными загрузками, а список/скачивание не ждут их.
    upload_lock = threading.Lock()

    def database():
        if "db" not in g:
            g.db = sqlite3.connect(root / "reports.sqlite3")
            g.db.row_factory = sqlite3.Row
        return g.db

    @app.teardown_appcontext
    def close_database(_error):
        if "db" in g:
            g.db.close()

    @app.after_request
    def headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = (
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"
        )
        return response

    @app.errorhandler(HTTPException)
    def http_error(error):
        return jsonify(error=error.name, message=error.description), error.code

    @app.get("/")
    @app.get("/api/status")
    def status():
        return jsonify(name="Genomeia", version="0.2.4 alpha", time=utc_now())

    @app.post("/upload")
    def upload():
        if not upload_lock.acquire(blocking=False):
            return jsonify(error="upload_busy"), 429
        temporary = None
        final = None
        committed = False
        try:
            # Проверка до разбора multipart: его временные файлы тоже занимают диск.
            if shutil.disk_usage(root).free < 3 * MAX_BUG_BYTES:
                raise InsufficientStorage("Not enough free disk space")
            used = database().execute("SELECT COALESCE(SUM(size), 0) FROM reports").fetchone()[0]
            if used + MAX_BUG_BYTES > MAX_STORAGE_BYTES:
                raise InsufficientStorage("Report storage quota reached")
            if len(request.files.getlist("bug")) != 1 or len(request.files) != 1:
                abort(400, description="Expected one bug file")
            raw_meta = request.form.get("meta", "")
            if len(raw_meta.encode("utf-8")) > 64 * 1024:
                abort(413, description="Metadata is too large")
            try:
                meta = json.loads(raw_meta)
            except (ValueError, RecursionError):
                abort(400, description="Invalid meta JSON")
            if not isinstance(meta, dict):
                abort(400, description="Expected meta object")
            bug = request.files["bug"]
            # Исходное имя — только для показа. Путь на диске задаёт серверный UUID.
            name = (bug.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
            name = "".join(c for c in name if c.isprintable())[:180]
            if not name.lower().endswith(".bug"):
                abort(400, description="Expected a .bug file")
            header = bug.stream.read(16)
            if header[:12] != HEADER or header[12:] not in (b"PK\x03\x04", b"PK\x05\x06"):
                abort(400, description="Invalid Genomeia .bug header")
            report_id = uuid.uuid4().hex
            temporary = root / (report_id + ".part")
            final = root / (report_id + ".bug")
            digest = hashlib.sha256(header)
            size = len(header)
            with temporary.open("xb") as output:
                output.write(header)
                while chunk := bug.stream.read(64 * 1024):
                    size += len(chunk)
                    if size > MAX_BUG_BYTES:
                        abort(413)
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(final)
            with database() as db:
                db.execute("INSERT INTO reports VALUES (?, ?, ?, ?, ?, ?)", (
                    report_id, name, size, utc_now(), digest.hexdigest(),
                    json.dumps(meta, ensure_ascii=False),
                ))
            committed = True
            return jsonify(uploaded=True, id=report_id, size=size, sha256=digest.hexdigest()), 201
        finally:
            if temporary:
                temporary.unlink(missing_ok=True)
            if final and not committed:
                final.unlink(missing_ok=True)
            upload_lock.release()

    @app.get("/reports")
    def reports():
        page = max(1, min(request.args.get("page", 1, type=int), 1_000_000))
        rows = database().execute(
            "SELECT * FROM reports ORDER BY created DESC, id DESC LIMIT ? OFFSET ?",
            (PAGE_SIZE + 1, (page - 1) * PAGE_SIZE),
        ).fetchall()
        items = []
        for row in rows[:PAGE_SIZE]:
            item = dict(row)
            meta = json.loads(item.pop("meta"))
            platform = meta.get("platform")
            item["platform"] = platform.get("type", "—") if isinstance(platform, dict) else "—"
            item["game"] = meta.get("game", "—")
            item["kind"] = meta.get("kind", "—")
            items.append(item)
        return render_template("reports.html", reports=items, page=page, has_next=len(rows) > PAGE_SIZE)

    @app.get("/reports/<report_id>/download")
    def download(report_id):
        if not re.fullmatch(r"[0-9a-f]{32}", report_id):
            abort(404)
        row = database().execute("SELECT name FROM reports WHERE id = ?", (report_id,)).fetchone()
        if row is None:
            abort(404)
        path = root / (report_id + ".bug")
        if not path.is_file():
            abort(404)
        return send_file(path, mimetype="application/octet-stream", as_attachment=True,
                         download_name=row["name"], conditional=True)

    return app


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
