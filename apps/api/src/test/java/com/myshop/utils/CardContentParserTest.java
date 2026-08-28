package com.myshop.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardContentParserTest {

    @Test
    void singleContentColumnPreservesAllRows() {
        CardContentParser.ParsedColumns parsed = CardContentParser.parse(List.of("A\nB\nC"));

        assertEquals(List.of(List.of("A", "B", "C")), parsed.columns());
        assertEquals(List.of(List.of("A"), List.of("B"), List.of("C")), parsed.rows());
    }

    @Test
    void singleContentColumnIsParsed() {
        CardContentParser.ParsedColumns parsed = CardContentParser.parse(List.of("A\nB"));

        assertEquals(List.of(List.of("A"), List.of("B")), parsed.rows());
    }

    @Test
    void twoColumnsZipByRow() {
        CardContentParser.ParsedColumns parsed = CardContentParser.parse(List.of("1\n2\n3\n4", "a\nb\nc\nd"));

        assertEquals(List.of(
                List.of("1", "a"), List.of("2", "b"),
                List.of("3", "c"), List.of("4", "d")), parsed.rows());
    }

    @Test
    void threeAndFourColumnsRemainOrdered() {
        CardContentParser.ParsedColumns three = CardContentParser.parse(List.of("1\n2", "a\nb", "x\ny"));
        CardContentParser.ParsedColumns four = CardContentParser.parse(List.of("1", "a", "x", "last"));

        assertEquals(List.of(List.of("1", "a", "x"), List.of("2", "b", "y")), three.rows());
        assertEquals(List.of(List.of("1", "a", "x", "last")), four.rows());
    }

    @Test
    void unequalColumnCountsAreRejectedWithActionableMessage() {
        CardContentParser.InvalidContentException exception = assertThrows(
                CardContentParser.InvalidContentException.class,
                () -> CardContentParser.parse(List.of("1\n2\n3\n4", "a\nb\nc")));

        assertEquals("各卡券内容数量不一致：第1列 4 条，第2列 3 条，请补齐后再导入", exception.getMessage());
    }

    @Test
    void internalBlankLineIsRejectedInsteadOfCompactingTheColumn() {
        CardContentParser.InvalidContentException exception = assertThrows(
                CardContentParser.InvalidContentException.class,
                () -> CardContentParser.parse(List.of("1\n\n3", "a\nb\nc")));

        assertEquals("第1列第2行为空，请补充内容或删除对应行", exception.getMessage());
    }

    @Test
    void outerWhitespaceIsRemovedButEachValueIsTrimmed() {
        CardContentParser.ParsedColumns parsed = CardContentParser.parse(List.of("\n  A  \n B\t \n\n"));

        assertEquals(List.of(List.of("A"), List.of("B")), parsed.rows());
    }

    @Test
    void crlfAndLfHaveTheSameMeaning() {
        CardContentParser.ParsedColumns crlf = CardContentParser.parse(List.of("A\r\nB\r\n"));
        CardContentParser.ParsedColumns lf = CardContentParser.parse(List.of("A\nB\n"));

        assertEquals(lf.rows(), crlf.rows());
    }

    @Test
    void fingerprintIncludesOrderedPartsWithoutAStringSeparator() {
        assertEquals(CardContentParser.fingerprint(List.of("1", "a")),
                CardContentParser.fingerprint(List.of("1", "a")));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                CardContentParser.fingerprint(List.of("1", "a")),
                CardContentParser.fingerprint(List.of("a", "1")));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                CardContentParser.fingerprint(List.of("1", "2", "3")),
                CardContentParser.fingerprint(List.of("1", "2:3")));
    }
}
