package com.codevisualizer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParserServiceTest {

    private final ParserService parser = new ParserService();

    @Test
    void acceptsACompleteClass() {
        ParserService.Outcome outcome = parser.parse("""
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.parsed().lineOffset()).isZero();
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void reportsTheLineOfAMissingSemicolon() {
        ParserService.Outcome outcome = parser.parse("""
                public class Broken {
                    public static void main(String[] args) {
                        int x = 5
                        System.out.println(x);
                    }
                }
                """);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).isNotEmpty();
        assertThat(outcome.errors()).anyMatch(error -> error.line() == 3 || error.line() == 4);
        assertThat(outcome.errors().get(0).phase()).isEqualTo("syntax");
    }

    @Test
    void reportsAnUnclosedBrace() {
        ParserService.Outcome outcome = parser.parse("""
                public class Broken {
                    public static void main(String[] args) {
                        if (true) {
                            System.out.println("x");
                }
                """);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).isNotEmpty();
    }

    @Test
    void wrapsStandaloneStatements() {
        ParserService.Outcome outcome = parser.parse("int x = 5;\nSystem.out.println(x);");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.parsed().lineOffset()).isEqualTo(2);
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("wrapped"));
    }

    @Test
    void mapsWrappedErrorLinesBackToThePastedCode() {
        ParserService.Outcome outcome = parser.parse("int x = 5\nSystem.out.println(x);");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).anyMatch(error -> error.line() == 1);
    }

    @Test
    void rejectsEmptyCode() {
        ParserService.Outcome outcome = parser.parse("   ");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors()).isNotEmpty();
    }

    @Test
    void rejectsOversizedCode() {
        ParserService.Outcome outcome = parser.parse("x".repeat(ParserService.MAX_CODE_LENGTH + 1));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errors().get(0).message()).contains("10000");
    }
}
