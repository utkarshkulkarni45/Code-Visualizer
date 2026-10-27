package com.codevisualizer.service;

import com.codevisualizer.dto.FlowEdge;
import com.codevisualizer.dto.FlowNode;
import com.codevisualizer.dto.MethodFlow;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Converts method bodies into a small flowchart graph. Loops become decision
 * nodes with a back edge, if/else branches merge again, and every node keeps
 * the source line so the UI can highlight the diagram while stepping.
 */
@Service
public class FlowchartService {

    private static final int MAX_LABEL = 86;

    public List<MethodFlow> build(ParserService.Parsed parsed) {
        List<MethodFlow> flows = new ArrayList<>();
        for (TypeDeclaration<?> type : parsed.unit().getTypes()) {
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof MethodDeclaration method && method.getBody().isPresent()) {
                    flows.add(buildMethod(type, method, parsed.lineOffset()));
                }
            }
        }
        return flows;
    }

    private MethodFlow buildMethod(TypeDeclaration<?> type, MethodDeclaration method, int offset) {
        Graph graph = new Graph(offset);
        int methodLine = graph.lineOf(method);
        String start = graph.node("start", "Start", methodLine);
        String end = graph.node("end", "End", methodLine);
        graph.endId = end;

        List<Tail> tails = List.of(new Tail(start, null));
        for (Statement statement : method.getBody().orElseThrow().getStatements()) {
            tails = graph.emit(statement, tails);
            if (tails.isEmpty()) {
                break;
            }
        }
        graph.connectAll(tails, end);

        String className = type.getNameAsString();
        String signature = className + "." + method.getNameAsString() + "(" + parameters(method) + ")";
        return new MethodFlow(method.getNameAsString(), signature, className, methodLine,
                graph.nodes, graph.edges);
    }

    private static String parameters(MethodDeclaration method) {
        return method.getParameters().stream()
                .map(parameter -> parameter.getType().asString() + (parameter.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(", "));
    }

    private record Tail(String id, String label) {
    }

    private static final class ControlCtx {
        private final boolean loop;
        private String continueTarget;
        private final List<String> breaks = new ArrayList<>();
        private final List<String> continues = new ArrayList<>();

        private ControlCtx(boolean loop, String continueTarget) {
            this.loop = loop;
            this.continueTarget = continueTarget;
        }
    }

    private static final class Graph {
        private final int offset;
        private final List<FlowNode> nodes = new ArrayList<>();
        private final List<FlowEdge> edges = new ArrayList<>();
        private final Deque<ControlCtx> controls = new ArrayDeque<>();
        private String endId;
        private int sequence;

        private Graph(int offset) {
            this.offset = offset;
        }

        private String node(String type, String label, int line) {
            String id = "n" + (++sequence);
            nodes.add(new FlowNode(id, type, label == null ? "" : truncate(label), line));
            return id;
        }

        private void edge(String from, String to) {
            edge(from, to, null);
        }

        private void edge(String from, String to, String label) {
            if (from == null || to == null) {
                return;
            }
            for (FlowEdge existing : edges) {
                if (existing.from().equals(from) && existing.to().equals(to)
                        && Objects.equals(existing.label(), label)) {
                    return;
                }
            }
            edges.add(new FlowEdge(from, to, label));
        }

        private void connectAll(List<Tail> tails, String to) {
            for (Tail tail : tails) {
                edge(tail.id(), to, tail.label());
            }
        }

        private int lineOf(Node node) {
            return node.getBegin().map(position -> Math.max(1, position.line - offset)).orElse(0);
        }

        private List<Tail> emit(Statement statement, List<Tail> incoming) {
            if (incoming.isEmpty()) {
                return List.of();
            }
            if (statement instanceof BlockStmt block) {
                List<Tail> tails = incoming;
                for (Statement child : block.getStatements()) {
                    tails = emit(child, tails);
                    if (tails.isEmpty()) {
                        break;
                    }
                }
                return tails;
            }
            if (statement instanceof ExpressionStmt expressionStmt) {
                Expression expression = expressionStmt.getExpression();
                String type = expression instanceof MethodCallExpr call && isPrint(call)
                        ? "io"
                        : expression instanceof MethodCallExpr ? "call" : "process";
                String node = node(type, text(expressionStmt), lineOf(statement));
                connectAll(incoming, node);
                return List.of(new Tail(node, null));
            }
            if (statement instanceof IfStmt ifStmt) {
                return emitIf(ifStmt, incoming);
            }
            if (statement instanceof ForStmt forStmt) {
                return emitFor(forStmt, incoming);
            }
            if (statement instanceof ForEachStmt forEachStmt) {
                return emitForEach(forEachStmt, incoming);
            }
            if (statement instanceof WhileStmt whileStmt) {
                return emitWhile(whileStmt, incoming);
            }
            if (statement instanceof DoStmt doStmt) {
                return emitDoWhile(doStmt, incoming);
            }
            if (statement instanceof SwitchStmt switchStmt) {
                return emitSwitch(switchStmt, incoming);
            }
            if (statement instanceof ReturnStmt returnStmt) {
                String label = returnStmt.getExpression()
                        .map(expression -> "return " + expression)
                        .orElse("return");
                String node = node("process", label, lineOf(statement));
                connectAll(incoming, node);
                edge(node, endId);
                return List.of();
            }
            if (statement instanceof ThrowStmt throwStmt) {
                String node = node("process", "throw " + throwStmt.getExpression(), lineOf(statement));
                connectAll(incoming, node);
                edge(node, endId);
                return List.of();
            }
            if (statement instanceof BreakStmt) {
                String node = node("process", "break", lineOf(statement));
                connectAll(incoming, node);
                ControlCtx context = controls.peek();
                if (context != null) {
                    context.breaks.add(node);
                } else {
                    edge(node, endId);
                }
                return List.of();
            }
            if (statement instanceof ContinueStmt) {
                String node = node("process", "continue", lineOf(statement));
                connectAll(incoming, node);
                ControlCtx loop = controls.stream().filter(context -> context.loop).findFirst().orElse(null);
                if (loop == null) {
                    edge(node, endId);
                } else if (loop.continueTarget != null) {
                    edge(node, loop.continueTarget);
                } else {
                    loop.continues.add(node);
                }
                return List.of();
            }
            if (statement instanceof TryStmt tryStmt) {
                List<Tail> tails = emit(tryStmt.getTryBlock(), incoming);
                if (tryStmt.getFinallyBlock().isPresent()) {
                    tails = emit(tryStmt.getFinallyBlock().get(), tails);
                }
                return tails;
            }
            if (statement instanceof LabeledStmt labeledStmt) {
                return emit(labeledStmt.getStatement(), incoming);
            }
            if (statement instanceof SynchronizedStmt synchronizedStmt) {
                return emit(synchronizedStmt.getBody(), incoming);
            }
            if (statement instanceof LocalClassDeclarationStmt localClass) {
                String node = node("process", "class " + localClass.getClassDeclaration().getNameAsString(),
                        lineOf(statement));
                connectAll(incoming, node);
                return List.of(new Tail(node, null));
            }

            String node = node("process", text(statement), lineOf(statement));
            connectAll(incoming, node);
            return List.of(new Tail(node, null));
        }

        private List<Tail> emitIf(IfStmt ifStmt, List<Tail> incoming) {
            String decision = node("decision", "if (" + ifStmt.getCondition() + ")", lineOf(ifStmt));
            connectAll(incoming, decision);

            List<Tail> thenTails = emit(ifStmt.getThenStmt(), List.of(new Tail(decision, "Yes")));
            List<Tail> elseTails = ifStmt.getElseStmt()
                    .map(elseStmt -> emit(elseStmt, List.of(new Tail(decision, "No"))))
                    .orElseGet(() -> List.of(new Tail(decision, "No")));

            if (thenTails.isEmpty() && elseTails.isEmpty()) {
                return List.of();
            }
            String merge = node("merge", "", lineOf(ifStmt));
            connectAll(thenTails, merge);
            connectAll(elseTails, merge);
            return List.of(new Tail(merge, null));
        }

        private List<Tail> emitFor(ForStmt forStmt, List<Tail> incoming) {
            List<Tail> current = incoming;
            if (!forStmt.getInitialization().isEmpty()) {
                String init = node("process", joinExpressions(forStmt.getInitialization()), lineOf(forStmt));
                connectAll(current, init);
                current = List.of(new Tail(init, null));
            }
            String condition = forStmt.getCompare().map(Object::toString).orElse("true");
            String decision = node("decision", condition, lineOf(forStmt));
            connectAll(current, decision);

            ControlCtx context = new ControlCtx(true, null);
            controls.push(context);
            List<Tail> bodyTails = emit(forStmt.getBody(), List.of(new Tail(decision, "Yes")));
            String continueTarget = decision;
            if (!forStmt.getUpdate().isEmpty()) {
                String update = node("process", joinExpressions(forStmt.getUpdate()), lineOf(forStmt));
                connectAll(bodyTails, update);
                edge(update, decision);
                continueTarget = update;
            } else {
                connectAll(bodyTails, decision);
            }
            controls.pop();
            for (String cont : context.continues) {
                edge(cont, continueTarget);
            }
            return loopExit(decision, context, lineOf(forStmt));
        }

        private List<Tail> emitForEach(ForEachStmt forEachStmt, List<Tail> incoming) {
            String decision = node("decision",
                    "for (" + forEachStmt.getVariable() + " : " + forEachStmt.getIterable() + ")",
                    lineOf(forEachStmt));
            connectAll(incoming, decision);

            ControlCtx context = new ControlCtx(true, decision);
            controls.push(context);
            List<Tail> bodyTails = emit(forEachStmt.getBody(), List.of(new Tail(decision, "Yes")));
            controls.pop();
            connectAll(bodyTails, decision);
            return loopExit(decision, context, lineOf(forEachStmt));
        }

        private List<Tail> emitWhile(WhileStmt whileStmt, List<Tail> incoming) {
            String decision = node("decision", "while (" + whileStmt.getCondition() + ")", lineOf(whileStmt));
            connectAll(incoming, decision);

            ControlCtx context = new ControlCtx(true, decision);
            controls.push(context);
            List<Tail> bodyTails = emit(whileStmt.getBody(), List.of(new Tail(decision, "Yes")));
            controls.pop();
            connectAll(bodyTails, decision);
            return loopExit(decision, context, lineOf(whileStmt));
        }

        private List<Tail> emitDoWhile(DoStmt doStmt, List<Tail> incoming) {
            int before = nodes.size();
            ControlCtx context = new ControlCtx(true, null);
            controls.push(context);
            List<Tail> bodyTails = emit(doStmt.getBody(), incoming);
            String entry = nodes.size() > before ? nodes.get(before).id() : null;
            String decision = node("decision", "while (" + doStmt.getCondition() + ")", lineOf(doStmt));
            connectAll(bodyTails, decision);
            if (entry != null) {
                edge(decision, entry, "Yes");
            }
            controls.pop();
            for (String cont : context.continues) {
                edge(cont, decision);
            }
            return loopExit(decision, context, lineOf(doStmt));
        }

        private List<Tail> emitSwitch(SwitchStmt switchStmt, List<Tail> incoming) {
            String decision = node("decision", "switch (" + switchStmt.getSelector() + ")", lineOf(switchStmt));
            connectAll(incoming, decision);

            ControlCtx context = new ControlCtx(false, null);
            controls.push(context);
            List<Tail> entryTails = new ArrayList<>();
            List<Tail> emptyEntries = new ArrayList<>();
            boolean hasDefault = false;
            for (SwitchEntry entry : switchStmt.getEntries()) {
                hasDefault |= entry.getLabels().isEmpty();
                String label = entry.getLabels().isEmpty()
                        ? "default"
                        : "case " + entry.getLabels().stream().map(Object::toString)
                                .collect(Collectors.joining(", "));
                if (entry.getStatements().isEmpty()) {
                    emptyEntries.add(new Tail(decision, label));
                    continue;
                }
                List<Tail> tails = List.of(new Tail(decision, label));
                for (Statement child : entry.getStatements()) {
                    tails = emit(child, tails);
                    if (tails.isEmpty()) {
                        break;
                    }
                }
                entryTails.addAll(tails);
            }
            controls.pop();

            String merge = node("merge", "", lineOf(switchStmt));
            connectAll(entryTails, merge);
            connectAll(emptyEntries, merge);
            for (String breakNode : context.breaks) {
                edge(breakNode, merge);
            }
            if (!hasDefault) {
                edge(decision, merge, "no match");
            }
            return List.of(new Tail(merge, null));
        }

        private List<Tail> loopExit(String decision, ControlCtx context, int line) {
            if (context.breaks.isEmpty()) {
                return List.of(new Tail(decision, "No"));
            }
            String merge = node("merge", "", line);
            edge(decision, merge, "No");
            for (String breakNode : context.breaks) {
                edge(breakNode, merge);
            }
            return List.of(new Tail(merge, null));
        }

        private boolean isPrint(MethodCallExpr call) {
            if (!call.getNameAsString().startsWith("print")) {
                return false;
            }
            String scope = call.getScope().map(Object::toString).orElse("");
            return scope.equals("System.out") || scope.equals("System.err")
                    || scope.endsWith(".out") || scope.endsWith(".err");
        }

        private String joinExpressions(List<Expression> expressions) {
            return expressions.stream().map(Object::toString).collect(Collectors.joining(", "));
        }

        private String text(Node node) {
            String text = node.toString().replaceAll("\\s+", " ").trim();
            return truncate(text);
        }

        private String truncate(String text) {
            return text.length() > MAX_LABEL ? text.substring(0, MAX_LABEL - 3) + "..." : text;
        }
    }
}
