package indi.bookmarkx.relocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSimilarityTest {

    @Test
    void shouldReturnZeroDistanceForIdenticalStrings() {
        assertEquals(0, TextSimilarity.levenshtein("abc", "abc"));
    }

    @Test
    void shouldReturnOneDistanceForSingleCharSubstitution() {
        assertEquals(1, TextSimilarity.levenshtein("abc", "abd"));
    }

    @Test
    void shouldReturnOneDistanceForSingleCharInsertion() {
        assertEquals(1, TextSimilarity.levenshtein("abc", "abcd"));
    }

    @Test
    void shouldReturnOtherLengthWhenOneStringIsEmpty() {
        assertEquals(3, TextSimilarity.levenshtein("", "abc"));
        assertEquals(3, TextSimilarity.levenshtein("abc", ""));
    }

    @Test
    void shouldTreatNullAsEmptyString() {
        assertEquals(0, TextSimilarity.levenshtein(null, null));
        assertEquals(3, TextSimilarity.levenshtein(null, "abc"));
        assertEquals(3, TextSimilarity.levenshtein("abc", null));
        assertEquals(1.0, TextSimilarity.similarity(null, null), 1e-9);
    }

    @Test
    void shouldReturnFullSimilarityForIdenticalStrings() {
        assertEquals(1.0, TextSimilarity.similarity("abc", "abc"), 1e-9);
    }

    @Test
    void shouldReturnFullSimilarityForTwoEmptyStrings() {
        assertEquals(1.0, TextSimilarity.similarity("", ""), 1e-9);
    }

    @Test
    void shouldReturnZeroSimilarityForCompletelyDifferentStrings() {
        assertEquals(0.0, TextSimilarity.similarity("aaa", "bbb"), 1e-9);
    }

    @Test
    void shouldScoreRenamedVariableAboveThreshold() {
        double score = TextSimilarity.similarity(
                "int userCount = repository.count();",
                "int userTotal = repository.count();");
        assertTrue(score >= AnchorMatcher.SIMILARITY_THRESHOLD, "实际相似度=" + score);
    }

    @Test
    void shouldScoreUnrelatedLinesBelowThreshold() {
        double score = TextSimilarity.similarity(
                "int userCount = repository.count();",
                "return ResponseEntity.ok(body);");
        assertTrue(score < AnchorMatcher.SIMILARITY_THRESHOLD, "实际相似度=" + score);
    }

    @Test
    void shouldBeSymmetric() {
        String a = "public void run() {";
        String b = "public void walk() {";
        assertEquals(TextSimilarity.levenshtein(a, b), TextSimilarity.levenshtein(b, a));
        assertEquals(TextSimilarity.similarity(a, b), TextSimilarity.similarity(b, a), 1e-9);
    }
}
