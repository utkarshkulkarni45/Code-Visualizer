package com.codevisualizer.service;

import com.codevisualizer.dto.TraceStep;
import com.codevisualizer.dto.VariableInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

class TraceServiceTest {

    private final ParserService parser = new ParserService();
    private final TraceService tracer = new TraceService();

    @Test
    @Timeout(30)
    void tracesAForLoopAndCapturesOutput() {
        ParserService.Outcome outcome = parser.parse("""
                public class Factorial {
                    public static void main(String[] args) {
                        int n = 5;
                        long fact = 1;
                        for (int i = 1; i <= n; i++) {
                            fact = fact * i;
                        }
                        System.out.println("5! = " + fact);
                    }
                }
                """);

        assertThat(outcome.ok()).isTrue();
        TraceService.TraceResult result = tracer.trace(outcome.parsed());

        assertThat(result.errors()).isEmpty();
        assertThat(result.steps()).hasSizeGreaterThan(10);
        assertThat(result.steps()).anyMatch(step -> step.locals().stream()
                .anyMatch(variable -> variable.name().equals("fact")));
        assertThat(result.steps()).anyMatch(step -> step.stack().stream()
                .anyMatch(frame -> frame.startsWith("main()")));
        TraceStep last = result.steps().get(result.steps().size() - 1);
        assertThat(last.output()).contains("5! = 120");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @Timeout(30)
    void recordsRecursionStackFrames() {
        ParserService.Outcome outcome = parser.parse("""
                public class Fibonacci {
                    public static void main(String[] args) {
                        int result = fib(4);
                        System.out.println("fib(4) = " + result);
                    }

                    static int fib(int n) {
                        if (n <= 1) {
                            return n;
                        }
                        return fib(n - 1) + fib(n - 2);
                    }
                }
                """);

        TraceService.TraceResult result = tracer.trace(outcome.parsed());

        assertThat(result.errors()).isEmpty();
        assertThat(result.steps()).anyMatch(step -> step.stack().size() >= 3
                && step.stack().stream().anyMatch(frame -> frame.startsWith("fib()")));
        assertThat(result.steps().get(result.steps().size() - 1).output()).contains("fib(4) = 3");
    }

    @Test
    @Timeout(30)
    void reportsCompileErrorsForSemanticallyInvalidCode() {
        ParserService.Outcome outcome = parser.parse("""
                public class Wrong {
                    public static void main(String[] args) {
                        System.out.println(missingVariable);
                    }
                }
                """);

        assertThat(outcome.ok()).isTrue();
        TraceService.TraceResult result = tracer.trace(outcome.parsed());

        assertThat(result.steps()).isEmpty();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0).phase()).isEqualTo("compile");
        assertThat(result.errors().get(0).line()).isEqualTo(3);
    }

    @Test
    @Timeout(30)
    void showsArraysAsBracketLists() {
        ParserService.Outcome outcome = parser.parse("""
                public class Arr {
                    public static void main(String[] args) {
                        int[] values = {1, 2, 3};
                        values[0] = 9;
                        System.out.println(values[0]);
                    }
                }
                """);

        TraceService.TraceResult result = tracer.trace(outcome.parsed());

        assertThat(result.errors()).isEmpty();
        assertThat(result.steps()).anyMatch(step -> {
            VariableInfo values = step.locals().stream()
                    .filter(variable -> variable.name().equals("values"))
                    .findFirst().orElse(null);
            return values != null && values.value().contains("[1, 2, 3]");
        });
    }
}
