"""Una backend: словарь команд и память.

Небольшой сервис, который даёт приложению две вещи:
  * /config  — словарь стемов и порог триггера, чтобы править формулировки без пересборки APK;
  * /memory  — факты, заметки и напоминания (SQLite), чтобы данные переживали переустановку.

Авторизация — заголовок X-Una-Token. Никаких внешних зависимостей, кроме FastAPI.
"""

import json
import os
import secrets
import sqlite3
import time
from contextlib import closing

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel

APP_DIR = os.environ.get("UNA_DIR", "/opt/una")
DB_PATH = os.path.join(APP_DIR, "una.db")
TOKEN_PATH = os.path.join(APP_DIR, "token")
CONFIG_PATH = os.path.join(APP_DIR, "config.json")
AUDIO_DIR = os.path.join(APP_DIR, "audio")
MAX_AUDIO_BYTES = 3 * 1024 * 1024
VERSION_PATH = os.path.join(APP_DIR, "version.json")
APK_PATH = os.path.join(APP_DIR, "download", "una.apk")

app = FastAPI(title="Una backend", docs_url=None, redoc_url=None, openapi_url=None)

# ---------------------------------------------------------------- auth


def expected_token() -> str:
    try:
        with open(TOKEN_PATH, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def auth(x_una_token: str | None = Header(default=None)) -> int | None:
    """Accepts the master token or a per-device token.

    Returns the device id for a device token and `None` for the master token, so that stored rows
    can be attributed to a specific phone. Every device gets its own token on registration, which
    means one leaked token does not expose the whole installation.
    """
    if not x_una_token:
        raise HTTPException(status_code=401, detail="bad token")
    wanted = expected_token()
    if wanted and secrets.compare_digest(x_una_token, wanted):
        return None
    try:
        with closing(db()) as c:
            row = c.execute("SELECT id FROM devices WHERE token = ?", (x_una_token,)).fetchone()
            if row:
                c.execute(
                    "UPDATE devices SET last_seen = ? WHERE id = ?", (int(time.time()), row[0])
                )
                c.commit()
                return int(row[0])
    except sqlite3.Error:
        pass
    raise HTTPException(status_code=401, detail="bad token")


# ---------------------------------------------------------------- db


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    os.makedirs(APP_DIR, exist_ok=True)
    with closing(db()) as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                created INTEGER NOT NULL,
                updated INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                at INTEGER NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                text TEXT NOT NULL,
                result TEXT NOT NULL DEFAULT '',
                meta TEXT NOT NULL DEFAULT '{}',
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS audio (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                device INTEGER NOT NULL DEFAULT 0,
                path TEXT NOT NULL,
                bytes INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                token TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL,
                last_seen INTEGER NOT NULL DEFAULT 0
            );
            """
        )
        c.commit()
        # older databases may not have the device column yet
        for table in ("journal", "audio"):
            columns = {row[1] for row in c.execute(f"PRAGMA table_info({table})")}
            if "device" not in columns:
                c.execute(f"ALTER TABLE {table} ADD COLUMN device INTEGER NOT NULL DEFAULT 0")
        c.commit()


init_db()

# ---------------------------------------------------------------- config

# The dictionary lives in /opt/una/config.json so that it can be edited in place: the file is
# re-read automatically whenever its modification time changes, no service restart needed.
CONFIG = {
    "configVersion": 1,
    "wake": {"word": "юна", "minConfidence": 0.85},
    "media": {
        "previous": ["предыдущ", "предыду", "прошл", "верни", "назад", "обратно"],
        "next": ["следующ", "следущ", "следую", "переключ", "пропуст", "промота", "дальше"],
        "pause": ["пауз", "останов", "приостанов", "стоп", "замолч", "затихн", "прекрат", "выключ"],
        "play": ["продолж", "игра", "воспроизв", "плей", "включи"],
        "context": ["музык", "трек", "песн", "плеер", "плеере", "композици"],
    },
    "volume": {
        "up": ["громче", "погромче", "прибав"],
        "down": ["тише", "потише", "убав", "сбав"],
        "words": {
            "ноль": 0, "один": 1, "одну": 1, "два": 2, "две": 2, "три": 3, "четыре": 4,
            "пять": 5, "шесть": 6, "семь": 7, "восемь": 8, "девять": 9, "десять": 10,
            "одиннадцать": 11, "двенадцать": 12, "тринадцать": 13, "четырнадцать": 14,
            "пятнадцать": 15,
        },
    },
    "yandex": {
        "open": ["включ", "постав", "запуст", "откр"],
        "wave": ["мою волн", "моя волн", "моей волн", "волну", "волна"],
        "liked": ["нравит", "любимы", "любимое"],
    },
}

_config_cache: dict = {"mtime": 0.0, "data": None}


def current_config() -> dict:
    """Returns the dictionary from disk, re-reading it when the file changes."""
    try:
        mtime = os.path.getmtime(CONFIG_PATH)
        if _config_cache["data"] is None or mtime != _config_cache["mtime"]:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                _config_cache["data"] = json.load(f)
            _config_cache["mtime"] = mtime
        return _config_cache["data"]
    except (OSError, ValueError):
        return CONFIG


def write_default_config() -> None:
    """Creates config.json on the first run so that it is easy to find and edit."""
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(CONFIG, f, ensure_ascii=False, indent=2)


write_default_config()

# ---------------------------------------------------------------- models


class FactIn(BaseModel):
    text: str


class NoteIn(BaseModel):
    title: str
    body: str = ""


class ReminderIn(BaseModel):
    text: str
    at: int  # unix seconds


# ---------------------------------------------------------------- routes


@app.get("/health")
def health() -> dict:
    return {"ok": True, "time": int(time.time())}


@app.get("/config")
def config(x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    return current_config()


@app.get("/version")
def version() -> dict:
    """Returns the newest APK version, so that the app can update itself."""
    try:
        with open(VERSION_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"versionCode": 0, "versionName": "unknown"}


@app.get("/download/una.apk")
def download_apk() -> FileResponse:
    if not os.path.exists(APK_PATH):
        raise HTTPException(status_code=404, detail="no apk uploaded")
    return FileResponse(APK_PATH, media_type="application/vnd.android.package-archive",
                        filename="una.apk")


# --- facts


@app.get("/memory/facts")
def facts_list(x_una_token: str | None = Header(default=None)) -> list:
    auth(x_una_token)
    with closing(db()) as c:
        rows = c.execute("SELECT id, text, created FROM facts ORDER BY id").fetchall()
    return [dict(r) for r in rows]


@app.post("/memory/facts")
def facts_add(item: FactIn, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        cur = c.execute(
            "INSERT INTO facts(text, created) VALUES (?, ?)",
            (item.text.strip(), int(time.time())),
        )
        c.commit()
        return {"id": cur.lastrowid}


@app.delete("/memory/facts/{fact_id}")
def facts_delete(fact_id: int, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        c.execute("DELETE FROM facts WHERE id = ?", (fact_id,))
        c.commit()
    return {"ok": True}


# --- notes


@app.get("/memory/notes")
def notes_list(x_una_token: str | None = Header(default=None)) -> list:
    auth(x_una_token)
    with closing(db()) as c:
        rows = c.execute(
            "SELECT id, title, body, created, updated FROM notes ORDER BY updated DESC"
        ).fetchall()
    return [dict(r) for r in rows]


@app.post("/memory/notes")
def notes_add(item: NoteIn, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    now = int(time.time())
    with closing(db()) as c:
        cur = c.execute(
            "INSERT INTO notes(title, body, created, updated) VALUES (?, ?, ?, ?)",
            (item.title.strip() or "Заметка", item.body, now, now),
        )
        c.commit()
        return {"id": cur.lastrowid}


@app.delete("/memory/notes/{note_id}")
def notes_delete(note_id: int, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        c.execute("DELETE FROM notes WHERE id = ?", (note_id,))
        c.commit()
    return {"ok": True}


# --- reminders


@app.get("/memory/reminders")
def reminders_list(x_una_token: str | None = Header(default=None)) -> list:
    auth(x_una_token)
    with closing(db()) as c:
        rows = c.execute(
            "SELECT id, text, at, done, created FROM reminders ORDER BY at"
        ).fetchall()
    return [dict(r) for r in rows]


@app.post("/memory/reminders")
def reminders_add(item: ReminderIn, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        cur = c.execute(
            "INSERT INTO reminders(text, at, done, created) VALUES (?, ?, 0, ?)",
            (item.text.strip(), item.at, int(time.time())),
        )
        c.commit()
        return {"id": cur.lastrowid}


@app.post("/memory/reminders/{reminder_id}/done")
def reminders_done(reminder_id: int, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        c.execute("UPDATE reminders SET done = 1 WHERE id = ?", (reminder_id,))
        c.commit()
    return {"ok": True}


@app.delete("/memory/reminders/{reminder_id}")
def reminders_delete(reminder_id: int, x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        c.execute("DELETE FROM reminders WHERE id = ?", (reminder_id,))
        c.commit()
    return {"ok": True}


class RegisterIn(BaseModel):
    name: str = ""


@app.post("/register")
def register(payload: RegisterIn) -> dict:
    """Gives every phone its own token, so one leaked token does not expose everything."""
    token = secrets.token_hex(24)
    now = int(time.time())
    with closing(db()) as c:
        cur = c.execute(
            "INSERT INTO devices(token, name, created, last_seen) VALUES (?, ?, ?, ?)",
            (token, payload.name[:60], now, now),
        )
        c.commit()
        return {"deviceId": cur.lastrowid, "token": token}


# ---------------------------------------------------------------- journal (recognition feedback)




class JournalEntry(BaseModel):
    ts: int
    text: str
    result: str = ""
    meta: dict | None = None


class JournalIn(BaseModel):
    entries: list[JournalEntry]


@app.post("/journal")
def journal_add(payload: JournalIn, x_una_token: str | None = Header(default=None)) -> dict:
    """Stores recognised phrases reported by the app (text only, no audio)."""
    device = auth(x_una_token)
    now = int(time.time())
    rows = [
        (e.ts, e.text[:500], e.result[:100], json.dumps(e.meta or {}, ensure_ascii=False), now, device or 0)
        for e in payload.entries[:200]
    ]
    if rows:
        with closing(db()) as c:
            c.executemany(
                "INSERT INTO journal(ts, text, result, meta, created, device) VALUES (?, ?, ?, ?, ?, ?)", rows
            )
            c.commit()
    return {"ok": True, "count": len(rows)}


@app.get("/journal/stats")
def journal_stats(x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        total = c.execute("SELECT COUNT(*) FROM journal").fetchone()[0]
        rejected = c.execute("SELECT COUNT(*) FROM journal WHERE result = 'rejected'").fetchone()[0]
        by_result = {
            row[0]: row[1]
            for row in c.execute(
                "SELECT result, COUNT(*) FROM journal GROUP BY result ORDER BY 2 DESC"
            ).fetchall()
        }
        top = [
            {"text": row[0], "n": row[1]}
            for row in c.execute(
                "SELECT text, COUNT(*) AS n FROM journal WHERE result = 'rejected' "
                "GROUP BY lower(text) ORDER BY n DESC LIMIT 20"
            ).fetchall()
        ]
        clips = c.execute("SELECT COUNT(*) FROM audio").fetchone()[0]
    return {
        "total": total,
        "rejected": rejected,
        "byResult": by_result,
        "topRejected": top,
        "audioClips": clips,
    }


# ---------------------------------------------------------------- false-trigger audio


@app.post("/audio")
async def audio_add(
    request: Request,
    reason: str = "unknown",
    x_una_token: str | None = Header(default=None),
) -> dict:
    """Stores a short WAV clip of a wake word that the strict check rejected."""
    device = auth(x_una_token)
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    if len(body) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="clip too large")
    os.makedirs(AUDIO_DIR, exist_ok=True)
    name = f"{int(time.time())}-{secrets.token_hex(4)}.wav"
    path = os.path.join(AUDIO_DIR, name)
    with open(path, "wb") as handle:
        handle.write(body)
    with closing(db()) as c:
        c.execute(
            "INSERT INTO audio(ts, reason, path, bytes, created, device) VALUES (?, ?, ?, ?, ?, ?)",
            (int(time.time()), reason[:40], path, len(body), int(time.time()), device or 0),
        )
        c.commit()
    return {"ok": True, "name": name, "bytes": len(body)}


@app.get("/audio/list")
def audio_list(x_una_token: str | None = Header(default=None)) -> dict:
    auth(x_una_token)
    with closing(db()) as c:
        rows = c.execute(
            "SELECT id, ts, reason, bytes, path FROM audio ORDER BY id DESC LIMIT 100"
        ).fetchall()
    return {"items": [dict(row) for row in rows]}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8090)
