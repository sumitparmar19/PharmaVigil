"""Map FAERS drug strings to RxNorm ingredients (RxCUIs) with the NLM RxNav API.

FAERS drug names are free text ("LIPITOR 20MG", "ATORVASTATIN CALCIUM"). Each distinct name key is
resolved once and cached in SQLite, so re-runs cost no API calls. Resolution order:
1. exact normalized match (`rxcui.json?search=2`), then
2. approximate match (`approximateTerm.json`) above a score threshold,
then the matched concept is reduced to its ingredient(s) (TTY=IN). Requests are rate-limited below
NLM's published limit of 20 per second, and an unavailable API returns "unmapped (network)" without
caching, so the pipeline keeps going (engineering rule 2).
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

import requests

from .http import USER_AGENT
from .names import name_key

RXNAV_BASE_URL = "https://rxnav.nlm.nih.gov/REST"
MAX_REQUESTS_PER_SECOND = 15.0
APPROXIMATE_MIN_SCORE = 8.0


@dataclass(frozen=True)
class Ingredient:
    rxcui: str
    name: str


@dataclass(frozen=True)
class Mapping:
    key: str
    method: str  # exact | approximate | none | error
    ingredients: tuple[Ingredient, ...]

    @property
    def mapped(self) -> bool:
        return bool(self.ingredients)


class RateLimiter:
    def __init__(self, per_second: float) -> None:
        self._interval = 1.0 / per_second
        self._next = 0.0
        self._lock = threading.Lock()

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            if now < self._next:
                time.sleep(self._next - now)
            self._next = max(now, self._next) + self._interval


class RxNavClient:
    def __init__(self, cache_path: Path, base_url: str = RXNAV_BASE_URL,
                 session: requests.Session | None = None, per_second: float = MAX_REQUESTS_PER_SECOND,
                 retries: int = 3) -> None:
        self.base_url = base_url.rstrip("/")
        self.http = session or requests.Session()
        self.http.headers["User-Agent"] = USER_AGENT
        self.limiter = RateLimiter(per_second)
        self.retries = retries
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(cache_path)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS name_map (key TEXT PRIMARY KEY, method TEXT, ingredients TEXT)"
        )
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS concept_ingredients (rxcui TEXT PRIMARY KEY, ingredients TEXT)"
        )
        self.api_calls = 0

    # -- HTTP -----------------------------------------------------------------------------------
    def _get(self, path: str, params: dict[str, str] | None = None) -> dict:
        last: Exception | None = None
        for attempt in range(self.retries):
            self.limiter.wait()
            try:
                self.api_calls += 1
                resp = self.http.get(f"{self.base_url}/{path}", params=params, timeout=30)
                if resp.status_code == 429 or resp.status_code >= 500:
                    raise requests.HTTPError(f"status {resp.status_code}")
                resp.raise_for_status()
                return resp.json()
            except (requests.RequestException, ValueError) as exc:
                last = exc
                time.sleep(1.5 * (2**attempt))
        raise ConnectionError(f"RxNav {path} failed: {last}")

    # -- concept helpers ------------------------------------------------------------------------
    def ingredients_of(self, rxcui: str) -> tuple[Ingredient, ...]:
        row = self.db.execute("SELECT ingredients FROM concept_ingredients WHERE rxcui=?", (rxcui,)).fetchone()
        if row:
            return tuple(Ingredient(**i) for i in json.loads(row[0]))
        props = self._get(f"rxcui/{rxcui}/properties.json").get("properties") or {}
        if props.get("tty") == "IN":
            found = (Ingredient(rxcui, props.get("name", "")),)
        else:
            related = self._get(f"rxcui/{rxcui}/related.json", {"tty": "IN"})
            groups = (related.get("relatedGroup") or {}).get("conceptGroup") or []
            concepts = [c for g in groups for c in g.get("conceptProperties") or []]
            found = tuple(sorted({Ingredient(c["rxcui"], c["name"]) for c in concepts}, key=lambda i: i.rxcui))
        self.db.execute(
            "INSERT OR REPLACE INTO concept_ingredients VALUES (?, ?)",
            (rxcui, json.dumps([i.__dict__ for i in found])),
        )
        return found

    def _exact(self, text: str) -> str | None:
        ids = (self._get("rxcui.json", {"name": text, "search": "2"}).get("idGroup") or {}).get("rxnormId")
        return ids[0] if ids else None

    def _approximate(self, text: str) -> str | None:
        group = self._get("approximateTerm.json", {"term": text, "maxEntries": "1", "option": "1"})
        candidates = (group.get("approximateGroup") or {}).get("candidate") or []
        for cand in candidates:
            if float(cand.get("score", 0)) >= APPROXIMATE_MIN_SCORE and cand.get("rxcui"):
                return str(cand["rxcui"])
        return None

    # -- public API -----------------------------------------------------------------------------
    def cached(self, key: str) -> Mapping | None:
        row = self.db.execute("SELECT method, ingredients FROM name_map WHERE key=?", (key,)).fetchone()
        if row is None:
            return None
        return Mapping(key, row[0], tuple(Ingredient(**i) for i in json.loads(row[1])))

    def resolve(self, raw_name: str) -> Mapping | None:
        """Resolve one drug string; None when the name has no usable key."""
        key = name_key(raw_name)
        if key is None:
            return None
        hit = self.cached(key)
        if hit is not None:
            return hit
        try:
            rxcui = self._exact(key)
            method = "exact"
            if rxcui is None:
                rxcui = self._approximate(key)
                method = "approximate"
            ingredients = self.ingredients_of(rxcui) if rxcui else ()
        except ConnectionError:
            return Mapping(key, "error", ())  # not cached: retried on the next run
        if not ingredients:
            method = "none"
        self.db.execute(
            "INSERT OR REPLACE INTO name_map VALUES (?, ?, ?)",
            (key, method, json.dumps([i.__dict__ for i in ingredients])),
        )
        self.db.commit()
        return Mapping(key, method, ingredients)

    def resolve_many(self, names: Iterable[str], stop_after_errors: int = 25) -> list[Mapping]:
        out: list[Mapping] = []
        errors_in_a_row = 0
        for name in names:
            m = self.resolve(name)
            if m is None:
                continue
            out.append(m)
            errors_in_a_row = errors_in_a_row + 1 if m.method == "error" else 0
            if errors_in_a_row >= stop_after_errors:
                break  # the API is down; stop instead of hammering it
        self.db.commit()
        return out

    def export_rows(self) -> list[dict[str, str]]:
        """Flat (key, method, rxcui, ingredient) rows for the Spark join."""
        rows = []
        for key, method, ingredients in self.db.execute("SELECT key, method, ingredients FROM name_map"):
            for ing in json.loads(ingredients):
                rows.append({"name_key": key, "method": method, "rxcui": ing["rxcui"], "ingredient": ing["name"]})
        return rows
