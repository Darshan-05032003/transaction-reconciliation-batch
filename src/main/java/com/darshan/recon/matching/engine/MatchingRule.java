package com.darshan.recon.matching.engine;

import java.util.Optional;

public interface MatchingRule {

    Optional<MatchOutcome> evaluate(CandidatePair pair);
}
