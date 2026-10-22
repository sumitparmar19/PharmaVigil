"""Calendar quarters for FAERS releases, and the download URL of each quarter's ASCII extract.

FDA publishes FAERS as one zip per quarter. Quarters up to 2012Q3 use the older AERS layout
(ISR/CASE numbers, no case version); from 2012Q4 the FAERS layout (primaryid/caseid/caseversion).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

FAERS_BASE_URL = "https://fis.fda.gov/content/Exports"
FIRST_FAERS_QUARTER = "2012Q4"

_QUARTER_RE = re.compile(r"^(\d{4})[Qq]([1-4])$")


@dataclass(frozen=True, order=True)
class Quarter:
    year: int
    q: int

    @classmethod
    def parse(cls, text: str) -> Quarter:
        m = _QUARTER_RE.match(text.strip())
        if not m:
            raise ValueError(f"not a quarter like 2019Q1: {text!r}")
        return cls(int(m.group(1)), int(m.group(2)))

    def __str__(self) -> str:
        return f"{self.year}Q{self.q}"

    @property
    def short(self) -> str:
        """Two-digit form used inside FDA file names, e.g. 19Q1."""
        return f"{self.year % 100:02d}Q{self.q}"

    @property
    def is_legacy_aers(self) -> bool:
        """True for quarters published in the older AERS layout (2004Q1 to 2012Q3)."""
        return self < Quarter.parse(FIRST_FAERS_QUARTER)

    def next(self) -> Quarter:
        return Quarter(self.year + 1, 1) if self.q == 4 else Quarter(self.year, self.q + 1)

    def zip_name(self) -> str:
        prefix = "aers" if self.is_legacy_aers else "faers"
        return f"{prefix}_ascii_{self.year}q{self.q}.zip"

    def url(self, base: str = FAERS_BASE_URL) -> str:
        return f"{base}/{self.zip_name()}"


def quarter_range(first: str | Quarter, last: str | Quarter) -> list[Quarter]:
    """All quarters from first to last, inclusive."""
    a = first if isinstance(first, Quarter) else Quarter.parse(first)
    b = last if isinstance(last, Quarter) else Quarter.parse(last)
    if b < a:
        raise ValueError(f"{b} is before {a}")
    out = [a]
    while out[-1] < b:
        out.append(out[-1].next())
    return out
