"""Report corpus for SafetyFormer: every unique FAERS case as a set of tokens.

A report becomes: its age band, sex and seriousness (context tokens), its drugs (RxNorm ingredients,
each with a role: primary suspect, secondary suspect, concomitant, interacting) and its reactions
(MedDRA PTs). Sets have no order, so there are no positions, only token types.

The corpus is held in compact numpy arrays (CSR style: one flat array of ids plus offsets per report)
so tens of millions of reports fit in memory. The vocabulary keeps drugs and PTs seen in at least
`min_count` reports *of the training window*, so a model trained before a date never learns a token
that only appears later.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import duckdb
import numpy as np

SPECIALS = ("[PAD]", "[MASK]", "[NODRUG]", "[MASKDRUG]")
PAD, MASK, NODRUG, MASKDRUG = range(4)
AGE_BANDS = ("0-1", "2-11", "12-17", "18-44", "45-64", "65-74", "75+", "unknown")
SEXES = ("M", "F", "UNK")
SERIOUS = ("serious", "non-serious", "unknown")
ROLES = {"none": 0, "PS": 1, "SS": 2, "C": 3, "I": 4}
TYPE_PAD, TYPE_CONTEXT, TYPE_DRUG, TYPE_PT, TYPE_MASK = range(5)


@dataclass
class Vocab:
    tokens: list[str] = field(default_factory=list)
    index: dict[str, int] = field(default_factory=dict)
    pt_tokens: list[int] = field(default_factory=list)  # vocab ids of PT tokens, in PT-head order
    drug_tokens: list[int] = field(default_factory=list)

    def add(self, token: str) -> int:
        if token not in self.index:
            self.index[token] = len(self.tokens)
            self.tokens.append(token)
        return self.index[token]

    @property
    def n_pt(self) -> int:
        return len(self.pt_tokens)

    def pt_names(self) -> list[str]:
        return [self.tokens[t][3:] for t in self.pt_tokens]

    def drug_rxcuis(self) -> list[str]:
        return [self.tokens[t][3:] for t in self.drug_tokens]

    def save(self, path: Path) -> None:
        path.write_text(json.dumps({"tokens": self.tokens, "pt_tokens": self.pt_tokens,
                                    "drug_tokens": self.drug_tokens}))

    @classmethod
    def load(cls, path: Path) -> Vocab:
        d = json.loads(path.read_text())
        v = cls(tokens=d["tokens"], pt_tokens=d["pt_tokens"], drug_tokens=d["drug_tokens"])
        v.index = {t: i for i, t in enumerate(v.tokens)}
        return v


def context_token(kind: str, value: str) -> str:
    return f"{kind}:{value}"


def base_vocab() -> Vocab:
    v = Vocab()
    for t in SPECIALS:
        v.add(t)
    for a in AGE_BANDS:
        v.add(context_token("age", a))
    for s in SEXES:
        v.add(context_token("sex", s))
    for s in SERIOUS:
        v.add(context_token("serious", s))
    return v


@dataclass
class Corpus:
    """CSR arrays over reports. Drug/PT ids are vocabulary ids; pt_targets are PT-head indices."""

    vocab: Vocab
    report_ids: np.ndarray
    fda_days: np.ndarray  # days since 1970-01-01
    context: np.ndarray  # (n_reports, 3) vocab ids: age, sex, serious
    drug_offsets: np.ndarray
    drug_ids: np.ndarray
    drug_roles: np.ndarray
    pt_offsets: np.ndarray
    pt_ids: np.ndarray

    def __len__(self) -> int:
        return len(self.report_ids)

    def drugs(self, i: int) -> tuple[np.ndarray, np.ndarray]:
        s, e = self.drug_offsets[i], self.drug_offsets[i + 1]
        return self.drug_ids[s:e], self.drug_roles[s:e]

    def pts(self, i: int) -> np.ndarray:
        return self.pt_ids[self.pt_offsets[i]:self.pt_offsets[i + 1]]

    def subset(self, mask: np.ndarray) -> Corpus:
        idx = np.flatnonzero(mask)
        d_lens = np.diff(self.drug_offsets)[idx]
        p_lens = np.diff(self.pt_offsets)[idx]
        d_take = np.concatenate([np.arange(self.drug_offsets[i], self.drug_offsets[i + 1]) for i in idx]) \
            if len(idx) else np.array([], dtype=np.int64)
        p_take = np.concatenate([np.arange(self.pt_offsets[i], self.pt_offsets[i + 1]) for i in idx]) \
            if len(idx) else np.array([], dtype=np.int64)
        return Corpus(
            vocab=self.vocab, report_ids=self.report_ids[idx], fda_days=self.fda_days[idx],
            context=self.context[idx],
            drug_offsets=np.concatenate([[0], np.cumsum(d_lens)]), drug_ids=self.drug_ids[d_take],
            drug_roles=self.drug_roles[d_take],
            pt_offsets=np.concatenate([[0], np.cumsum(p_lens)]), pt_ids=self.pt_ids[p_take],
        )


def _days(d: date | None) -> int | None:
    return None if d is None else (d - date(1970, 1, 1)).days


def load_corpus(con: duckdb.DuckDBPyConnection, before: date | None = None, min_count: int = 5,
                vocab: Vocab | None = None) -> Corpus:
    """Build the corpus (and, unless given, the vocabulary) from gold views `src_report*`.

    Only reports with at least one mapped drug and at least one PT are kept, and only those received
    before `before` when it is set.
    """
    date_filter = f"WHERE r.fda_dt < DATE '{before.isoformat()}'" if before else ""
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE sf_reports AS
        SELECT r.primaryid, r.fda_dt, coalesce(r.age_band, 'unknown') AS age_band,
               coalesce(r.sex, 'UNK') AS sex, r.serious
        FROM src_report r {date_filter}
    """)
    con.execute("""
        CREATE OR REPLACE TEMP TABLE sf_drugs AS
        SELECT DISTINCT d.primaryid, d.rxcui, d.role FROM src_report_drug d JOIN sf_reports USING (primaryid)
    """)
    con.execute("""
        CREATE OR REPLACE TEMP TABLE sf_pts AS
        SELECT DISTINCT e.primaryid, e.pt FROM src_report_reaction e JOIN sf_reports USING (primaryid)
    """)
    if vocab is None:
        vocab = base_vocab()
        drugs = con.execute(f"""SELECT rxcui FROM sf_drugs GROUP BY rxcui
                                HAVING count(DISTINCT primaryid) >= {int(min_count)} ORDER BY rxcui""").fetchall()
        pts = con.execute(f"""SELECT pt FROM sf_pts GROUP BY pt
                              HAVING count(DISTINCT primaryid) >= {int(min_count)} ORDER BY pt""").fetchall()
        vocab.drug_tokens = [vocab.add(f"rx:{r[0]}") for r in drugs]
        vocab.pt_tokens = [vocab.add(f"pt:{p[0]}") for p in pts]

    con.register("sf_drug_vocab", _frame({"rxcui": vocab.drug_rxcuis(), "tok": vocab.drug_tokens}))
    con.register("sf_pt_vocab", _frame({"pt": vocab.pt_names(), "tok": vocab.pt_tokens}))
    rows = con.execute("""
        WITH d AS (SELECT primaryid, list(v.tok ORDER BY v.tok) AS dt, list(role ORDER BY v.tok) AS dr
                   FROM sf_drugs JOIN sf_drug_vocab v USING (rxcui) GROUP BY primaryid),
             p AS (SELECT primaryid, list(v.tok ORDER BY v.tok) AS pt
                   FROM sf_pts JOIN sf_pt_vocab v USING (pt) GROUP BY primaryid)
        SELECT r.primaryid, r.fda_dt, r.age_band, r.sex, r.serious, d.dt, d.dr, p.pt
        FROM sf_reports r JOIN d USING (primaryid) JOIN p USING (primaryid)
        ORDER BY r.fda_dt, r.primaryid
    """).fetchall()
    return _to_corpus(vocab, rows)


def _frame(cols: dict[str, list]) -> object:
    import pandas as pd

    return pd.DataFrame(cols)


def _to_corpus(vocab: Vocab, rows: list[tuple]) -> Corpus:
    n = len(rows)
    context = np.empty((n, 3), dtype=np.int64)
    fda_days = np.empty(n, dtype=np.int64)
    d_off, p_off = [0], [0]
    d_ids: list[int] = []
    d_roles: list[int] = []
    p_ids: list[int] = []
    for i, (_, fda_dt, age, sex, serious, dt, dr, pt) in enumerate(rows):
        context[i] = (
            vocab.index[context_token("age", age if age in AGE_BANDS else "unknown")],
            vocab.index[context_token("sex", sex if sex in SEXES else "UNK")],
            vocab.index[context_token("serious", "unknown" if serious is None else
                                      ("serious" if serious else "non-serious"))],
        )
        fda_days[i] = _days(fda_dt) if fda_dt is not None else 0
        d_ids.extend(dt)
        d_roles.extend(ROLES.get(r, 0) for r in dr)
        p_ids.extend(pt)
        d_off.append(len(d_ids))
        p_off.append(len(p_ids))
    return Corpus(
        vocab=vocab, report_ids=np.array([r[0] for r in rows], dtype=object), fda_days=fda_days,
        context=context, drug_offsets=np.array(d_off, dtype=np.int64), drug_ids=np.array(d_ids, dtype=np.int64),
        drug_roles=np.array(d_roles, dtype=np.int64), pt_offsets=np.array(p_off, dtype=np.int64),
        pt_ids=np.array(p_ids, dtype=np.int64),
    )
