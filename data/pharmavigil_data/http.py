"""Shared HTTP download helper: streaming, retries with backoff, and a clear "source unavailable"
error so callers can switch to the documented fallback instead of crashing (engineering rule 2)."""

from __future__ import annotations

import hashlib
import time
from pathlib import Path

import requests

USER_AGENT = "PharmaVigil-data/0.1 (research; https://github.com/sumitparmar19)"


class SourceUnavailable(RuntimeError):
    """The remote source refused or failed after retries (network policy, outage, 4xx/5xx)."""


def download(url: str, dest: Path, *, retries: int = 4, backoff_s: float = 2.0,
             timeout_s: float = 60.0, session: requests.Session | None = None) -> str:
    """Download url to dest atomically and return the file's SHA-256."""
    http = session or requests.Session()
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            with http.get(url, stream=True, timeout=timeout_s,
                          headers={"User-Agent": USER_AGENT}) as resp:
                if resp.status_code in (401, 403, 404):
                    raise SourceUnavailable(f"{url} answered {resp.status_code}")
                resp.raise_for_status()
                digest = hashlib.sha256()
                with open(tmp, "wb") as fh:
                    for chunk in resp.iter_content(chunk_size=1 << 20):
                        fh.write(chunk)
                        digest.update(chunk)
            tmp.replace(dest)
            return digest.hexdigest()
        except SourceUnavailable:
            raise
        except requests.RequestException as exc:
            last_error = exc
            if attempt + 1 < retries:
                time.sleep(backoff_s * (2**attempt))
    raise SourceUnavailable(f"{url} failed after {retries} attempts: {last_error}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()
