"""Where the data platform reads and writes. Both roots are git-ignored and can be moved with
environment variables (PV_LAKE for tables, PV_RAW for downloads that are deleted after use)."""

from __future__ import annotations

import os
from pathlib import Path

_DATA_DIR = Path(__file__).resolve().parent.parent


def lake_root() -> Path:
    return Path(os.environ.get("PV_LAKE", _DATA_DIR / "lake"))


def raw_root() -> Path:
    return Path(os.environ.get("PV_RAW", _DATA_DIR / "raw"))


def bronze(*parts: str) -> Path:
    return lake_root().joinpath("bronze", *parts)


def silver(*parts: str) -> Path:
    return lake_root().joinpath("silver", *parts)


def gold(*parts: str) -> Path:
    return lake_root().joinpath("gold", *parts)
