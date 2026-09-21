package indi.bookmarkx.relocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnchorTextNormalizerTest {

    @Test
    void shouldTrimLeadingAndTrailingWhitespace() {
        assertEquals("int a = 1;", AnchorTextNormalizer.normalize("    int a = 1;   "));
    }

    @Test
    void shouldCollapseInnerWhitespaceAndTabs() {
        assertEquals("int a = 1;", AnchorTextNormalizer.normalize("int\t\ta   =  1;"));
    }

    @Test
    void shouldNormalizeNullAndBlankToEmpty() {
        assertEquals("", AnchorTextNormalizer.normalize(null));
        assertEquals("", AnchorTextNormalizer.normalize("     "));
    }

    @Test
    void shouldTruncateLongLineTo200Chars() {
        String longLine = "a".repeat(250);
        String result = AnchorTextNormalizer.normalize(longLine);
        assertEquals(200, result.length());
        assertEquals("a".repeat(200), result);
    }

    @Test
    void shouldKeepExactly200CharsIntact() {
        String line = "b".repeat(200);
        assertEquals(line, AnchorTextNormalizer.normalize(line));
    }

    @Test
    void shouldTreatIdeographicSpaceAsWhitespace() {
        // U+3000 全角空格：中文输入法全角模式下敲出的空格，\s 与 trim() 均不识别。
        String raw = "　　int a　= 1;　　";
        assertEquals("int a = 1;", AnchorTextNormalizer.normalize(raw));
    }

    @Test
    void shouldTreatNbspAsWhitespace() {
        // U+00A0 不换行空格：网页复制粘贴代码常带的字符，\s、trim()、strip() 均不识别。
        String raw = "  int a = 1;  ";
        assertEquals("int a = 1;", AnchorTextNormalizer.normalize(raw));
    }
}
