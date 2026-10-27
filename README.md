# Java Code Visualizer

Paste or upload Java code and see how it runs: syntax errors with exact positions, a flowchart built from the parsed program, and a real step-by-step trace with variables, call stack and console output.

Built for students learning how control flow, loops, methods and recursion actually execute.

## Features

- **Syntax check** — JavaParser reports parse errors with line and column. Statement-only snippets are wrapped in a class automatically.
- **Flowchart** — generated from the AST: start/end, assignments, `if`/`else`, `for`, enhanced `for`, `while`, `do-while`, `switch`, `break`/`continue`, `System.out` printing and method calls. Yes/No branches and loop back edges are labelled.
- **Real execution trace** — the program is compiled with `javac` and run in a separate JVM, stepped through over JDI. Every step records the line, locals (arrays shown as indexed cells), call-stack frames and stdout.
- **Linked playback** — code line, flowchart node, variables, stack and console highlight together. Prev / Next / Play, speed control, slider and arrow-key navigation.
- **Samples** — factorial, if/else, array sum, bubble sort, recursion, while loop and a helper-method program.
- **Dark, responsive UI** — works from phone width up.

## Stack

Java 21, Spring Boot 3.5, JavaParser 3.27, JDI (JDK), plain HTML/CSS/JS (no frontend framework), Maven, JUnit 5.

## Run locally

Prerequisites: JDK 21+ and Maven 3.9+.

```bash
mvn spring-boot:run
```

Open <http://localhost:8080>. The first sample loads and runs automatically.

Run the tests:

```bash
mvn test
```

Build a jar:

```bash
mvn clean package
java -jar target/java-code-visualizer-*.jar
```

## API

### `GET /api/health`

```json
{ "status": "ok" }
```

### `GET /api/samples`

Returns the built-in sample programs (`id`, `name`, `description`, `code`).

### `POST /api/analyze`

```json
{ "code": "public class Main { ... }" }
```

Response:

```json
{
  "valid": true,
  "errors": [],
  "methods": [
    {
      "name": "main",
      "signature": "Main.main(String[])",
      "className": "Main",
      "line": 2,
      "nodes": [
        { "id": "n1", "type": "start", "label": "Start", "line": 2 },
        { "id": "n3", "type": "decision", "label": "i <= n", "line": 4 }
      ],
      "edges": [
        { "from": "n1", "to": "n3" }
      ]
    }
  ],
  "trace": [
    {
      "index": 0,
      "line": 3,
      "caption": "executing int n = 5;",
      "method": "main",
      "locals": [ { "name": "n", "type": "int", "value": "5" } ],
      "stack": [ "main() · Main.java:3" ],
      "output": ""
    }
  ],
  "traceTruncated": false,
  "warnings": []
}
```

Invalid code returns `valid: false`, an `errors` array (each with `phase`, `line`, `column`, `message`) and no methods or trace. Flowchart node types are `start`, `end`, `process`, `decision`, `io`, `call` and `merge`.

## Limits and sandboxing

User code never runs in the Spring Boot JVM. For each request the tracer:

- compiles into a temporary directory with `javax.tools.JavaCompiler`;
- launches the program in a **separate JVM** (`-Xmx64m`) via JDI;
- stops after **500 steps** or **5 seconds**, whichever comes first;
- caps console output at **20,000 characters**;
- rejects code longer than **10,000 characters**;
- kills the child JVM, disposes the debug session and deletes the temp directory afterwards.

`System.exit`, stack overflows, infinite loops and output floods are all contained by these limits and return a warning instead of taking the server down.

**Deployment:** process isolation inside one JVM is not a security boundary. When exposing this publicly, run the app in a container with a CPU limit, memory limit, read-only filesystem, no network and a non-root user. The included `Dockerfile` does the non-root part; add the remaining isolation at the orchestrator (or a per-execution container sandbox) for untrusted public traffic.

## Project layout

```
src/main/java/com/codevisualizer/
  controller/AnalyzeController.java   REST endpoints
  service/ParserService.java          syntax check and statement wrapping
  service/FlowchartService.java       AST to flowchart nodes/edges
  service/TraceService.java           compile + JDI single-stepping
  service/AnalyzeService.java         orchestrates the response
  service/SampleService.java          built-in samples
  dto/                                request/response records
src/main/resources/static/            frontend (index.html, style.css, app.js)
src/test/java/                        parser, flowchart and trace tests
```

## Docker

```bash
docker build -t java-code-visualizer .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp --memory 512m --cpus 1 java-code-visualizer
```

The image runs as a non-root user and includes the full JDK because the tracer needs `javac` at runtime.
