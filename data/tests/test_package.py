"""Smoke test: the data package imports and exposes a version."""

import pharmavigil_data


def test_version() -> None:
    assert pharmavigil_data.__version__ == "0.1.0"
