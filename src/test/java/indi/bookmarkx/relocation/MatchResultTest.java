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

    @Test
    void shouldTreatOnlySimilarityAsFuzzyTextMatch() {
        // 只有相似度兜底不保证行内文本与锚点相同；其余都是精确匹配，
        // 一旦落在原行号上，AnchorMatcher 的快速路径就会先返回 EXACT_AT_ORIGIN。
        assertTrue(MatchResult.Confidence.SIMILARITY.isFuzzyTextMatch());

        for (MatchResult.Confidence confidence : MatchResult.Confidence.values()) {
            if (confidence == MatchResult.Confidence.SIMILARITY) {
                continue;
            }
            assertFalse(confidence.isFuzzyTextMatch(),
                    confidence + " 建立在精确文本匹配之上，不应被当作模糊命中");
        }
    }
}
