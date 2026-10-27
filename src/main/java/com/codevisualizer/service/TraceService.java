package com.codevisualizer.service;

import com.codevisualizer.dto.ErrorInfo;
import com.codevisualizer.dto.TraceStep;
import com.codevisualizer.dto.VariableInfo;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Modifier;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.StepRequest;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Compiles the submitted code into a temporary directory and runs it in a
 * separate, budget-limited JVM. A JDI line-step request records every executed
 * line together with local variables, the call stack and stdout.
 */
@Service
public class TraceService {

    public static final int MAX_STEPS = 500;
    public static final int TIMEOUT_MS = 5_000;
    public static final int MAX_OUTPUT_CHARS = 20_000;
    private static final int MAX_VALUE_CHARS = 220;
    private static final int MAX_STACK_FRAMES = 50;
    private static final int MAX_LOCALS = 30;
    private static final int MAX_ARRAY_ITEMS = 30;

    private static final List<String> EXCLUDED_CLASS_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.springframework.");

    public record TraceResult(
            List<TraceStep> steps,
            List<ErrorInfo> errors,
            boolean truncated,
            List<String> warnings) {
    }

    private record MainInfo(String className) {
    }

    private record CompileOutcome(List<ErrorInfo> errors) {
    }

    public TraceResult trace(ParserService.Parsed parsed) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("javaviz-");
            CompileOutcome compiled = compile(parsed, workDir);
            if (!compiled.errors().isEmpty()) {
                return new TraceResult(List.of(), compiled.errors(), false, List.of());
            }
            MainInfo main = findMain(parsed);
            if (main == null) {
                return new TraceResult(List.of(), List.of(), false, List.of(
                        "No main(String[]) method was found, so the program was not run. "
                                + "The flowchart is still available."));
            }
            return run(parsed, workDir, main);
        } catch (Exception exception) {
            return new TraceResult(List.of(), List.of(new ErrorInfo(ErrorInfo.RUNTIME, 1, 1,
                    "Could not run the code: " + shortMessage(exception))), false, List.of());
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ------------------------------------------------------------------ compile

    private CompileOutcome compile(ParserService.Parsed parsed, Path workDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileOutcome(List.of(new ErrorInfo(ErrorInfo.RUNTIME, 1, 1,
                    "Running code requires a full JDK (javac was not found).")));
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(workDir));
            String fileName = sourceFileName(parsed);
            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("string:///" + fileName + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return parsed.source();
                }
            };
            List<String> options = List.of("-g", "-proc:none", "-nowarn", "-encoding", "UTF-8");
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null,
                    List.of(source)).call();

            List<ErrorInfo> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                    continue;
                }
                long rawLine = diagnostic.getLineNumber();
                long rawColumn = diagnostic.getColumnNumber();
                int line = rawLine < 1 ? 1
                        : parsed.toUserLine((int) Math.min(rawLine, Integer.MAX_VALUE));
                int column = rawColumn < 1 ? 1 : (int) Math.min(rawColumn, Integer.MAX_VALUE);
                errors.add(new ErrorInfo(ErrorInfo.COMPILE, line, column,
                        clean(diagnostic.getMessage(null))));
            }
            if (!Boolean.TRUE.equals(success) && errors.isEmpty()) {
                errors.add(new ErrorInfo(ErrorInfo.COMPILE, 1, 1, "Compilation failed."));
            }
            return new CompileOutcome(errors);
        }
    }

    private String sourceFileName(ParserService.Parsed parsed) {
        for (TypeDeclaration<?> type : parsed.unit().getTypes()) {
            boolean isPublic = type.getModifiers().stream()
                    .anyMatch(modifier -> modifier.getKeyword() == Modifier.Keyword.PUBLIC);
            if (isPublic) {
                return type.getNameAsString();
            }
        }
        return parsed.unit().getTypes().isEmpty()
                ? "Main"
                : parsed.unit().getTypes().get(0).getNameAsString();
    }

    private MainInfo findMain(ParserService.Parsed parsed) {
        for (MethodDeclaration method : parsed.unit().findAll(MethodDeclaration.class)) {
            if (!method.isStatic() || !method.getType().isVoidType()
                    || !method.getNameAsString().equals("main")
                    || method.getParameters().size() != 1) {
                continue;
            }
            Parameter parameter = method.getParameter(0);
            boolean stringArray = parameter.getType().asString().equals("String[]")
                    || (parameter.isVarArgs() && parameter.getType().asString().equals("String"));
            if (!stringArray) {
                continue;
            }
            Optional<ClassOrInterfaceDeclaration> owner =
                    method.findAncestor(ClassOrInterfaceDeclaration.class);
            if (owner.isEmpty()) {
                continue;
            }
            String packageName = parsed.unit().getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            String className = owner.get().getNameAsString();
            return new MainInfo(packageName.isBlank() ? className : packageName + "." + className);
        }
        return null;
    }

    // ------------------------------------------------------------------- execute

    private TraceResult run(ParserService.Parsed parsed, Path workDir, MainInfo mainInfo) {
        List<TraceStep> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ErrorInfo> errors = new ArrayList<>();
        boolean truncated = false;
        boolean timedOut = false;
        boolean stepLimitHit = false;
        boolean sawDeath = false;

        Process process = null;
        VirtualMachine vm = null;
        OutputCollector output = null;
        StepRequest stepRequest = null;

        try {
            LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            if (arguments.containsKey("home")) {
                arguments.get("home").setValue(System.getProperty("java.home"));
            }
            arguments.get("main").setValue(mainInfo.className());
            arguments.get("options").setValue(
                    "-cp " + quote(workDir.toAbsolutePath().toString())
                            + " -Xmx64m -Dfile.encoding=UTF-8");
            arguments.get("suspend").setValue("true");

            vm = connector.launch(arguments);
            process = vm.process();
            output = new OutputCollector(process);
            output.start();

            EventRequestManager requestManager = vm.eventRequestManager();
            MethodEntryRequest entryRequest = requestManager.createMethodEntryRequest();
            entryRequest.addClassFilter(mainInfo.className());
            entryRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            entryRequest.enable();

            EventQueue queue = vm.eventQueue();
            long prepareDeadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
            ThreadReference mainThread = null;
            boolean prepared = false;

            vm.resume();
            while (System.nanoTime() < prepareDeadline) {
                EventSet set;
                try {
                    set = queue.remove(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (set == null) {
                    continue;
                }
                for (Event event : set) {
                    if (event instanceof MethodEntryEvent methodEntry
                            && methodEntry.method().name().equals("main")) {
                        mainThread = methodEntry.thread();
                        prepared = true;
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        sawDeath = true;
                    }
                }
                if (prepared || sawDeath) {
                    break;
                }
                try {
                    set.resume();
                } catch (VMDisconnectedException disconnected) {
                    break;
                }
            }
            entryRequest.disable();

            if (!prepared) {
                if (sawDeath) {
                    warnings.add("The program finished before tracing could start.");
                } else {
                    warnings.add("The program did not reach its main method within 5 seconds.");
                }
                addStderrWarning(output, warnings);
                return new TraceResult(List.of(), List.of(), false, warnings);
            }
            if (mainThread == null) {
                mainThread = vm.allThreads().stream()
                        .filter(thread -> "main".equals(thread.name()))
                        .findFirst().orElse(null);
            }
            if (mainThread == null) {
                warnings.add("The main thread could not be found, so no steps were recorded.");
                return new TraceResult(List.of(), List.of(), false, warnings);
            }

            TraceStep entryStep = record(parsed, mainThread, output, 0);
            if (entryStep != null) {
                steps.add(entryStep);
            }

            stepRequest = requestManager.createStepRequest(
                    mainThread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
            for (String prefix : EXCLUDED_CLASS_PREFIXES) {
                stepRequest.addClassExclusionFilter(prefix + "*");
            }
            stepRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            stepRequest.enable();
            vm.resume();

            long traceDeadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
            String lastKey = entryStep == null ? null : dedupeKey(entryStep);
            while (true) {
                if (steps.size() >= MAX_STEPS) {
                    stepLimitHit = true;
                    break;
                }
                if (System.nanoTime() >= traceDeadline) {
                    timedOut = true;
                    break;
                }
                EventSet set;
                try {
                    set = queue.remove(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (set == null) {
                    continue;
                }
                boolean death = false;
                for (Event event : set) {
                    if (event instanceof StepEvent stepEvent) {
                        TraceStep recorded = record(parsed, stepEvent.thread(), output, steps.size());
                        if (recorded != null) {
                            String key = dedupeKey(recorded);
                            if (!key.equals(lastKey)) {
                                steps.add(recorded);
                                lastKey = key;
                            }
                        }
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        death = true;
                    }
                }
                if (death) {
                    sawDeath = true;
                    break;
                }
                try {
                    set.resume();
                } catch (VMDisconnectedException disconnected) {
                    break;
                }
            }

            truncated = stepLimitHit || timedOut;
            if (sawDeath) {
                output.await();
            }
            String finalOutput = output.snapshot();
            if (!steps.isEmpty()) {
                TraceStep last = steps.get(steps.size() - 1);
                String caption = "closing the block".equals(last.caption())
                        ? "program finished" : last.caption();
                if (!caption.equals(last.caption()) || !finalOutput.equals(last.output())) {
                    steps.set(steps.size() - 1, new TraceStep(last.index(), last.line(), caption,
                            last.method(), last.locals(), last.stack(), finalOutput));
                }
            }
            if (stepLimitHit) {
                warnings.add("The trace stopped at the " + MAX_STEPS + "-step limit.");
            } else if (timedOut) {
                warnings.add("Execution stopped after " + (TIMEOUT_MS / 1000)
                        + " seconds. The program may contain an infinite loop.");
            }
            if (sawDeath) {
                addExitWarnings(process, output, warnings);
            }
            if (output.truncated()) {
                warnings.add("Console output was cut off at " + MAX_OUTPUT_CHARS + " characters.");
            }
            return new TraceResult(steps, errors, truncated, warnings);
        } catch (Exception exception) {
            errors.add(new ErrorInfo(ErrorInfo.RUNTIME, 1, 1,
                    "The program could not be started: " + shortMessage(exception)));
            addStderrWarning(output, warnings);
            return new TraceResult(steps, errors, truncated, warnings);
        } finally {
            if (stepRequest != null) {
                try {
                    stepRequest.disable();
                } catch (Exception ignored) {
                    // VM may already be gone.
                }
            }
            if (vm != null && !sawDeath) {
                try {
                    vm.exit(1);
                } catch (Exception ignored) {
                    // VM may already be gone.
                }
            }
            if (process != null) {
                try {
                    if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(400, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if (output != null) {
                output.await();
            }
            if (vm != null) {
                try {
                    vm.dispose();
                } catch (Exception ignored) {
                    // Nothing left to release.
                }
            }
        }
    }

    // ------------------------------------------------------------------ recording

    private TraceStep record(ParserService.Parsed parsed, ThreadReference thread,
                             OutputCollector output, int index) {
        if (thread == null) {
            return null;
        }
        Location location;
        try {
            location = thread.frame(0).location();
        } catch (IncompatibleThreadStateException | VMDisconnectedException exception) {
            return null;
        }
        int jvmLine = location.lineNumber();
        if (jvmLine < 1) {
            return null;
        }
        int userLine = parsed.toUserLine(jvmLine);
        String sourceLine = sourceLine(parsed.source(), jvmLine);
        String method = location.method().name();
        return new TraceStep(index, userLine, caption(userLine, sourceLine), method,
                readLocals(thread), readStack(thread, parsed), output.snapshot());
    }

    private List<VariableInfo> readLocals(ThreadReference thread) {
        List<VariableInfo> variables = new ArrayList<>();
        try {
            StackFrame frame = thread.frame(0);
            for (LocalVariable variable : frame.visibleVariables()) {
                if (variables.size() >= MAX_LOCALS) {
                    break;
                }
                try {
                    Value value = frame.getValue(variable);
                    variables.add(new VariableInfo(variable.name(), variable.typeName(),
                            formatValue(value, 0)));
                } catch (Exception ignored) {
                    // A variable can disappear between requests; skip it.
                }
            }
        } catch (AbsentInformationException | IncompatibleThreadStateException
                 | VMDisconnectedException ignored) {
            // No debug information or the frame moved on.
        }
        return variables;
    }

    private List<String> readStack(ThreadReference thread, ParserService.Parsed parsed) {
        List<String> frames = new ArrayList<>();
        try {
            for (StackFrame frame : thread.frames()) {
                Location location = frame.location();
                ReferenceType type = location.declaringType();
                if (isExcluded(type.name())) {
                    continue;
                }
                String source = "";
                try {
                    source = " · " + location.sourceName();
                } catch (AbsentInformationException ignored) {
                    // Source name is optional.
                }
                int line = location.lineNumber();
                String suffix = line > 0 ? ":" + parsed.toUserLine(line) : "";
                frames.add(location.method().name() + "()" + source + suffix);
                if (frames.size() >= MAX_STACK_FRAMES) {
                    break;
                }
            }
        } catch (IncompatibleThreadStateException | VMDisconnectedException ignored) {
            // The VM is already gone.
        }
        return frames;
    }

    private boolean isExcluded(String className) {
        return EXCLUDED_CLASS_PREFIXES.stream().anyMatch(className::startsWith);
    }

    private String formatValue(Value value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof PrimitiveValue primitive) {
            if (primitive instanceof CharValue character) {
                return "'" + escape(String.valueOf(character.value())) + "'";
            }
            if (primitive instanceof BooleanValue bool) {
                return String.valueOf(bool.value());
            }
            return truncate(primitive.toString(), MAX_VALUE_CHARS);
        }
        if (value instanceof StringReference string) {
            return "\"" + escape(string.value()) + "\"";
        }
        if (value instanceof ArrayReference array) {
            StringBuilder result = new StringBuilder("[");
            int length = array.length();
            int shown = Math.min(length, MAX_ARRAY_ITEMS);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    result.append(", ");
                }
                try {
                    result.append(formatValue(array.getValue(i), depth + 1));
                } catch (Exception exception) {
                    result.append("?");
                }
            }
            if (length > shown) {
                result.append(", ...(").append(length).append(" items)");
            }
            return truncate(result.append(']').toString(), MAX_VALUE_CHARS);
        }
        if (value instanceof ObjectReference object) {
            String typeName = simpleName(object.referenceType().name());
            String id = "@" + Long.toHexString(object.uniqueID());
            if (depth > 0 || object.referenceType().name().startsWith("java.")) {
                return typeName + id;
            }
            StringBuilder result = new StringBuilder(typeName).append('{');
            int count = 0;
            try {
                for (Field field : object.referenceType().allFields()) {
                    if (field.isStatic() || field.isSynthetic()) {
                        continue;
                    }
                    if (count >= 6) {
                        result.append(", ...");
                        break;
                    }
                    if (count > 0) {
                        result.append(", ");
                    }
                    result.append(field.name()).append('=');
                    try {
                        result.append(formatValue(object.getValue(field), depth + 1));
                    } catch (Exception exception) {
                        result.append('?');
                    }
                    count++;
                }
            } catch (Exception ignored) {
                // Fall back to the type name only.
            }
            return truncate(result.append('}').toString(), MAX_VALUE_CHARS);
        }
        return truncate(value.toString(), MAX_VALUE_CHARS);
    }

    private String simpleName(String className) {
        String withoutPackage = className.substring(className.lastIndexOf('.') + 1);
        return withoutPackage.replace('$', '.');
    }

    private String caption(int line, String code) {
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return "Line " + line;
        }
        String description;
        if (trimmed.startsWith("if") || trimmed.startsWith("} else if")) {
            description = "checking the condition " + trimmed;
        } else if (trimmed.startsWith("for") || trimmed.startsWith("while")) {
            description = "checking the loop condition " + trimmed;
        } else if (trimmed.contains("System.out.print") || trimmed.contains("System.err.print")) {
            description = "printing " + trimmed;
        } else if (trimmed.startsWith("return")) {
            description = "running " + trimmed;
        } else if (trimmed.equals("}") || trimmed.equals("{")) {
            description = "closing the block";
        } else {
            description = "executing " + trimmed;
        }
        return truncate(description, 150);
    }

    private String sourceLine(String source, int jvmLine) {
        String[] lines = source.split("\n", -1);
        return jvmLine - 1 < lines.length ? lines[jvmLine - 1] : "";
    }

    private String dedupeKey(TraceStep step) {
        StringBuilder key = new StringBuilder();
        key.append(step.line()).append('|').append(step.method()).append('|');
        for (VariableInfo variable : step.locals()) {
            key.append(variable.name()).append('=').append(variable.value()).append(';');
        }
        key.append('|').append(step.output().length());
        return key.toString();
    }

    private void addExitWarnings(Process process, OutputCollector output, List<String> warnings) {
        String stderr = output == null ? "" : output.errorSnapshot().strip();
        if (!stderr.isEmpty()) {
            String firstLine = stderr.lines().findFirst().orElse(stderr);
            warnings.add("Runtime error: " + truncate(firstLine, 180));
        } else if (process != null) {
            try {
                if (process.waitFor(200, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                    warnings.add("The program exited with code " + process.exitValue() + ".");
                }
            } catch (Exception ignored) {
                // The exit code is a nice-to-have only.
            }
        }
    }

    private void addStderrWarning(OutputCollector output, List<String> warnings) {
        if (output == null) {
            return;
        }
        String stderr = output.errorSnapshot().strip();
        if (!stderr.isEmpty()) {
            warnings.add("Runtime error: " + truncate(stderr.lines().findFirst().orElse(stderr), 180));
        }
    }

    private String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private String clean(String message) {
        if (message == null || message.isBlank()) {
            return "Compilation error";
        }
        String firstLine = message.strip().lines().findFirst().orElse(message.strip());
        return truncate(firstLine, 280);
    }

    private String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return truncate(message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message, 220);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private String escape(String value) {
        if (value == null) {
            return "null";
        }
        return truncate(value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\"", "\\\""), MAX_VALUE_CHARS);
    }

    private void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    /**
     * Drains stdout and stderr continuously. Without this the child JVM can
     * block once the OS pipe buffer fills up.
     */
    private static final class OutputCollector {
        private final Process process;
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder error = new StringBuilder();
        private boolean truncated;
        private final Thread outputThread;
        private final Thread errorThread;

        private OutputCollector(Process process) {
            this.process = process;
            this.outputThread = new Thread(() -> pump(process.getInputStream(), true), "trace-stdout");
            this.errorThread = new Thread(() -> pump(process.getErrorStream(), false), "trace-stderr");
            this.outputThread.setDaemon(true);
            this.errorThread.setDaemon(true);
        }

        private void start() {
            outputThread.start();
            errorThread.start();
        }

        private void pump(InputStream stream, boolean primary) {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (this) {
                        StringBuilder target = primary ? output : error;
                        int cap = primary ? MAX_OUTPUT_CHARS : 4_000;
                        int remaining = cap - target.length();
                        if (remaining > 0) {
                            target.append(buffer, 0, Math.min(read, remaining));
                        } else if (primary) {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closed when the process ends.
            }
        }

        private synchronized String snapshot() {
            return output.toString();
        }

        private synchronized String errorSnapshot() {
            return error.toString();
        }

        private synchronized boolean truncated() {
            return truncated;
        }

        private void await() {
            try {
                outputThread.join(500);
                errorThread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
