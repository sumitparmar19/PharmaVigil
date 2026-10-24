"""RxNav client against mocked API responses: exact and approximate matches, ingredient reduction,
caching, and graceful failure when the API is unreachable."""

import re

import pytest
import responses

from pharmavigil_data.rxnorm import RxNavClient

BASE = "https://rxnav.test/REST"


@pytest.fixture
def client(tmp_path):
    return RxNavClient(tmp_path / "cache.sqlite", base_url=BASE, per_second=1000, retries=1)


def _props(rxcui, tty, name):
    responses.get(f"{BASE}/rxcui/{rxcui}/properties.json",
                  json={"properties": {"rxcui": rxcui, "tty": tty, "name": name}})


@responses.activate
def test_exact_match_on_ingredient(client) -> None:
    responses.get(re.compile(rf"{BASE}/rxcui\.json.*name=ATORVASTATIN"), json={"idGroup": {"rxnormId": ["83367"]}})
    _props("83367", "IN", "atorvastatin")
    m = client.resolve("Atorvastatin 20 mg")
    assert m.method == "exact"
    assert [i.rxcui for i in m.ingredients] == ["83367"]


@responses.activate
def test_approximate_match_reduced_to_ingredients(client) -> None:
    responses.get(re.compile(rf"{BASE}/rxcui\.json"), json={"idGroup": {}})
    responses.get(re.compile(rf"{BASE}/approximateTerm\.json"),
                  json={"approximateGroup": {"candidate": [{"rxcui": "857005", "score": "11.5"}]}})
    _props("857005", "SCD", "acetaminophen 325 MG / hydrocodone 5 MG Oral Tablet")
    responses.get(f"{BASE}/rxcui/857005/related.json", json={"relatedGroup": {"conceptGroup": [
        {"tty": "IN", "conceptProperties": [{"rxcui": "5489", "name": "hydrocodone"},
                                            {"rxcui": "161", "name": "acetaminophen"}]}]}})
    m = client.resolve("NORCO TABS")
    assert m.method == "approximate"
    assert [i.rxcui for i in m.ingredients] == ["161", "5489"]


@responses.activate
def test_low_score_is_unmapped_and_cached(client) -> None:
    responses.get(re.compile(rf"{BASE}/rxcui\.json"), json={"idGroup": {}})
    responses.get(re.compile(rf"{BASE}/approximateTerm\.json"),
                  json={"approximateGroup": {"candidate": [{"rxcui": "1", "score": "2.0"}]}})
    m = client.resolve("ZZYZX BRAND TONIC")
    assert m.method == "none" and not m.mapped
    calls = len(responses.calls)
    assert client.resolve("Zzyzx brand tonic").method == "none"
    assert len(responses.calls) == calls  # served from the cache


@responses.activate
def test_unreachable_api_is_not_cached(client) -> None:
    responses.get(re.compile(rf"{BASE}/.*"), status=503)
    m = client.resolve("ASPIRIN")
    assert m.method == "error"
    assert client.cached("ASPIRIN") is None


@responses.activate
def test_resolve_many_stops_when_api_is_down(client) -> None:
    responses.get(re.compile(rf"{BASE}/.*"), status=503)
    out = client.resolve_many([f"DRUG {chr(65 + i)}{chr(65 + i)}" for i in range(10)], stop_after_errors=3)
    assert len(out) == 3


@responses.activate
def test_export_rows(client) -> None:
    responses.get(re.compile(rf"{BASE}/rxcui\.json"), json={"idGroup": {"rxnormId": ["161"]}})
    _props("161", "IN", "acetaminophen")
    client.resolve("TYLENOL")
    assert client.export_rows() == [
        {"name_key": "TYLENOL", "method": "exact", "rxcui": "161", "ingredient": "acetaminophen"}
    ]
