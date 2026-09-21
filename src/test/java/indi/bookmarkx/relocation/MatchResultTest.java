package indi.bookmarkx.relocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchResultTest {

    @Test
    void shouldCarryLineAndConfidenceWhenFound() {
        MatchResult result = MatchResult.found(42, MatchResult.Confidence.GIT_VERIFIED);

        assertTrue(result.isFound());
        assertEquals(42, result.getLine());
        assertEquals(MatchResult.Confidence.GIT_VERIFIED, result.getConfidence());
    }

    @Test
    void shouldReportNegativeLineWhenLost() {
        MatchResult result = MatchResult.lost();

        assertFalse(result.isFound());
        assertEquals(-1, result.getLine());
        assertEquals(MatchResult.Confidence.LOST, result.getConfidence());
    }

    @Test
    void shouldDeclareAllConfidenceLevels() {
        // 锁定枚举集合，防止后续改动误删某一档置信度
        assertEquals(7, MatchResult.Confidence.values().length);
        MatchResult.Confidence.valueOf("EXACT_AT_ORIGIN");
        MatchResult.Confidence.valueOf("GIT_VERIFIED");
        MatchResult.Confidence.valueOf("CONTENT_UNIQUE");
        MatchResult.Confidence.valueOf("CONTENT_SCORED");
        MatchResult.Confidence.valueOf("CONTENT_AMBIGUOUS");
        MatchResult.Confidence.valueOf("SIMILARITY");
        MatchResult.Confidence.valueOf("LOST");
    }

    @Test
    void shouldTreatEveryNonLostConfidenceAsFound() {
        for (MatchResult.Confidence confidence : MatchResult.Confidence.values()) {
            MatchResult result = MatchResult.found(1, confidence);
            if (confidence == MatchResult.Confidence.LOST) {
                assertFalse(result.isFound(), "LOST 不应被视为命中");
            } else {
                assertTrue(result.isFound(), confidence + " 应被视为命中");
            }
        }
    }
}
