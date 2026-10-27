package com.codevisualizer.service;

import com.codevisualizer.dto.FlowEdge;
import com.codevisualizer.dto.FlowNode;
import com.codevisualizer.dto.MethodFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowchartServiceTest {

    private final ParserService parser = new ParserService();
    private final FlowchartService flowchart = new FlowchartService();

    private MethodFlow mainOf(String code) {
        ParserService.Outcome outcome = parser.parse(code);
        assertThat(outcome.ok()).isTrue();
        List<MethodFlow> methods = flowchart.build(outcome.parsed());
        assertThat(methods).isNotEmpty();
        return methods.stream().filter(method -> method.name().equals("main")).findFirst().orElseThrow();
    }

    @Test
    void ifElseProducesDecisionBranchesAndMerge() {
        MethodFlow flow = mainOf("""
                public class Choice {
                    public static void main(String[] args) {
                        int n = 3;
                        if (n > 2) {
                            System.out.println("big");
                        } else {
                            System.out.println("small");
                        }
                    }
                }
                """);

        assertThat(flow.nodes()).extracting(FlowNode::type)
                .contains("start", "decision", "io", "merge", "end");
        assertThat(flow.edges()).extracting(FlowEdge::label).contains("Yes", "No");
        assertThat(flow.nodes()).anyMatch(node -> node.type().equals("decision")
                && node.label().contains("n > 2"));
    }

    @Test
    void forLoopHasABackEdge() {
        MethodFlow flow = mainOf("""
                public class Sum {
                    public static void main(String[] args) {
                        int sum = 0;
                        for (int i = 0; i < 3; i++) {
                            sum += i;
                        }
                        System.out.println(sum);
                    }
                }
                """);

        FlowNode decision = flow.nodes().stream()
                .filter(node -> node.type().equals("decision"))
                .findFirst().orElseThrow();
        assertThat(flow.nodes()).extracting(FlowNode::label)
                .anyMatch(label -> label.contains("i < 3"));
        assertThat(flow.edges()).anyMatch(edge -> edge.to().equals(decision.id())
                && !edge.from().equals("n1"));
    }

    @Test
    void printlnBecomesAnInputOutputNode() {
        MethodFlow flow = mainOf("""
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        assertThat(flow.nodes()).anyMatch(node -> node.type().equals("io")
                && node.label().contains("println"));
    }

    @Test
    void wrappedStatementsKeepUserLineNumbers() {
        ParserService.Outcome outcome = parser.parse("int x = 5;\nif (x > 0) {\n    x++;\n}");
        assertThat(outcome.ok()).isTrue();

        MethodFlow flow = flowchart.build(outcome.parsed()).get(0);
        assertThat(flow.nodes()).allSatisfy(node -> assertThat(node.line()).isBetween(1, 4));
        assertThat(flow.nodes()).anyMatch(node -> node.type().equals("decision") && node.line() == 2);
    }

    @Test
    void breakInsideLoopGetsAMergeNode() {
        MethodFlow flow = mainOf("""
                public class Stop {
                    public static void main(String[] args) {
                        int i = 0;
                        while (true) {
                            if (i > 2) {
                                break;
                            }
                            i++;
                        }
                        System.out.println(i);
                    }
                }
                """);

        assertThat(flow.nodes()).anyMatch(node -> node.type().equals("merge"));
        assertThat(flow.nodes()).anyMatch(node -> node.label().equals("break"));
    }
}
