// Enumerations of the case model: where a case came from, its workflow status, and its seriousness.
package io.pharmavigil.caseservice.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class CaseEnums {

    private CaseEnums() {
    }

    public enum Source { PHARMACY, PARTNER, E2B_IMPORT }

    public enum Seriousness { UNKNOWN, SERIOUS, NON_SERIOUS }

    public enum CodeSource { NONE, MODEL, REVIEWER, IMPORT }

    public enum NarrativeSource { TEMPLATE, LLM, REVIEWER }

    /** Workflow status with the transitions the pipeline and reviewers may make. */
    public enum Status {
        RECEIVED, NEEDS_MANUAL_CODING, CODED, TRIAGED, IN_REVIEW, APPROVED, SUBMITTED, ACKNOWLEDGED, REJECTED;

        private static final Map<Status, Set<Status>> NEXT = Map.of(
                RECEIVED, EnumSet.of(CODED, NEEDS_MANUAL_CODING, TRIAGED),
                NEEDS_MANUAL_CODING, EnumSet.of(TRIAGED, IN_REVIEW),
                CODED, EnumSet.of(TRIAGED),
                TRIAGED, EnumSet.of(IN_REVIEW),
                IN_REVIEW, EnumSet.of(APPROVED),
                APPROVED, EnumSet.of(SUBMITTED, IN_REVIEW),
                SUBMITTED, EnumSet.of(ACKNOWLEDGED, REJECTED),
                REJECTED, EnumSet.of(IN_REVIEW),
                ACKNOWLEDGED, EnumSet.of(IN_REVIEW)); // a follow-up reopens the case

        public boolean canMoveTo(Status next) {
            return NEXT.getOrDefault(this, Set.of()).contains(next);
        }
    }
}
