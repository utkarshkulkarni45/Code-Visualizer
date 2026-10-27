package com.codevisualizer.service;

import com.codevisualizer.dto.ErrorInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns raw user text into a parsed compilation unit. Pasted snippets that are
 * only statements are wrapped in a synthetic {@code class Main}, and every
 * line number that leaves this service is translated back to the line the user
 * actually typed.
 */
@Service
public class ParserService {

    public static final int MAX_CODE_LENGTH = 10_000;

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:@[\\w.]+\\s*)*(?:public\\s+|protected\\s+|private\\s+|static\\s+|final\\s+|"
                    + "abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*(?:class|interface|enum|record)\\s+\\w+");
    private static final Pattern IMPORT_OR_PACKAGE = Pattern.compile("(?m)^\\s*(?:package|import)\\b");

    public record Parsed(String source, CompilationUnit unit, int lineOffset, List<String> warnings) {

        public int toUserLine(int parserLine) {
            return Math.max(1, parserLine - lineOffset);
        }
    }

    public record Outcome(Parsed parsed, List<ErrorInfo> errors, List<String> warnings) {

        public boolean ok() {
            return parsed != null;
        }

        public static Outcome failure(List<ErrorInfo> errors) {
            return new Outcome(null, errors, List.of());
        }
    }

    public Outcome parse(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Outcome.failure(List.of(new ErrorInfo(ErrorInfo.SYNTAX, 1, 1, "Paste or upload some Java code first.")));
        }
        if (rawCode.length() > MAX_CODE_LENGTH) {
            return Outcome.failure(List.of(new ErrorInfo(ErrorInfo.SYNTAX, 1, 1,
                    "Code is limited to " + MAX_CODE_LENGTH + " characters (yours is " + rawCode.length() + ").")));
        }

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        ParseResult<CompilationUnit> direct = parser.parse(rawCode);
        if (direct.isSuccessful() && direct.getResult().isPresent()) {
            return new Outcome(new Parsed(rawCode, direct.getResult().get(), 0, List.of()), List.of(), List.of());
        }

        boolean looksLikeWholeFile = TYPE_DECLARATION.matcher(rawCode).find()
                || IMPORT_OR_PACKAGE.matcher(rawCode).find();
        if (looksLikeWholeFile) {
            return new Outcome(null, problems(direct, 0), List.of());
        }

        // A beginner pasted only statements, e.g. "int x = 5; System.out.println(x);".
        String wrapped = "class Main {\n    public static void main(String[] args) {\n" + rawCode + "\n    }\n}\n";
        ParseResult<CompilationUnit> wrappedResult = parser.parse(wrapped);
        if (wrappedResult.isSuccessful() && wrappedResult.getResult().isPresent()) {
            List<String> warnings = List.of(
                    "Standalone statements were wrapped in class Main with a main(String[]) method.");
            return new Outcome(new Parsed(wrapped, wrappedResult.getResult().get(), 2, warnings), List.of(), warnings);
        }
        return new Outcome(null, problems(wrappedResult, 2), List.of());
    }

    private List<ErrorInfo> problems(ParseResult<CompilationUnit> result, int lineOffset) {
        List<ErrorInfo> errors = new ArrayList<>();
        try {
            result.getProblems().forEach(problem -> {
                Optional<Range> range = problem.getLocation()
                        .flatMap(tokenRange -> tokenRange.getBegin().getRange());
                int line = range.map((Range r) -> Math.max(1, r.begin.line - lineOffset)).orElse(1);
                int column = range.map(r -> Math.max(1, r.begin.column)).orElse(1);
                errors.add(new ErrorInfo(ErrorInfo.SYNTAX, line, column, cleanMessage(problem.getMessage())));
            });
        } catch (ParseProblemException e) {
            errors.add(new ErrorInfo(ErrorInfo.SYNTAX, 1, 1, cleanMessage(e.getMessage())));
        }
        if (errors.isEmpty()) {
            errors.add(new ErrorInfo(ErrorInfo.SYNTAX, 1, 1, "The code could not be parsed."));
        }
        return errors;
    }

    private String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Syntax error";
        }
        String firstLine = message.strip().lines().findFirst().orElse(message.strip());
        return firstLine.length() > 280 ? firstLine.substring(0, 277) + "..." : firstLine;
    }

    /** Exposed for tests that only need a position. */
    static int lineOf(Position position) {
        return position == null ? 0 : position.line;
    }
}
