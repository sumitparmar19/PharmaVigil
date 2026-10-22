"""Reference-set helpers and the VAERS narrative builder."""

import zipfile

import pandas as pd

from pharmavigil_data.refsets import outcome_pt_map, source_url, summarize
from pharmavigil_data.vaers import build_narratives, read_zip

KNOWN_OUTCOMES = {
    "OMOP Acute Liver Failure 1", "OMOP Acute myocardial Infarction  1", "OMOP Acute Renal Failure 1",
    "HOI Upper GI #3", "Anaphylaxis #1", "OMOP Aplastic Anemia 1",
    "Leukopenia Including Neutropenia and Agranulocytosis", "Stevens-Johnson Syndrome #1",
    "Rhabdomyolysis #1", "Cardiac Valve Fibrosis #1",
}


def test_outcome_map_covers_every_reference_outcome() -> None:
    m = outcome_pt_map()
    assert set(m["outcome_name"]) == KNOWN_OUTCOMES
    assert (m["pt"] == m["pt"].str.lower()).all()
    assert not m.duplicated().any()


def test_source_url_is_pinned_to_a_commit() -> None:
    assert "/d7305910f6c6a390a9e423ffc1685a97c017183e/data/omopReferenceSet.rda" in source_url("omop")


def test_summarize() -> None:
    df = pd.DataFrame({"exposure_name": ["a", "b", "b"], "outcome_name": ["x", "x", "y"],
                       "ground_truth": [1, 0, 0]})
    assert summarize(df) == {"pairs": 3, "positives": 1, "negatives": 2, "outcomes": 2, "drugs": 2}


def _vaers_frames():
    data = pd.DataFrame({"VAERS_ID": ["1", "2", "3"], "RECVDATE": ["01/02/2023", "03/04/2023", "05/06/2023"],
                         "SYMPTOM_TEXT": ["Pain at site, then fever.", "  ", "Rash on arms."]})
    symptoms = pd.DataFrame({
        "VAERS_ID": ["1", "1", "3"],
        "SYMPTOM1": ["Pyrexia", "Injection site pain", "Rash"],
        "SYMPTOM2": ["Injection site pain", "", ""],
        "SYMPTOM3": ["", "", ""], "SYMPTOM4": ["", "", ""], "SYMPTOM5": ["", "", ""],
    })
    return data, symptoms


def test_build_narratives_joins_and_dedups_pts() -> None:
    out = build_narratives(*_vaers_frames())
    assert list(out["vaers_id"]) == ["1", "3"]  # report 2 has no text
    assert out.loc[0, "pts"] == ["injection site pain", "pyrexia"]
    assert int(out.loc[0, "year"]) == 2023


def test_read_zip(tmp_path) -> None:
    data, symptoms = _vaers_frames()
    z = tmp_path / "2023VAERSData.zip"
    with zipfile.ZipFile(z, "w") as zf:
        zf.writestr("2023VAERSDATA.csv", data.to_csv(index=False))
        zf.writestr("2023VAERSSYMPTOMS.csv", symptoms.to_csv(index=False))
    assert len(read_zip(z)) == 2
