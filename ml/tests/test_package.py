"""Smoke test: the ml package imports and exposes a version."""

import pharmavigil_ml


def test_version() -> None:
    assert pharmavigil_ml.__version__ == "0.1.0"
