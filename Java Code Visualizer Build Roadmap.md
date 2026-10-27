# Java Code Visualizer: Build Roadmap

*A phased plan to turn the JavaScript prototype into a Java version with a Spring Boot backend, with the prompts and files you need at each step.*

## 1. The big picture

The prototype runs JavaScript inside the browser, which is why it needs no server. Java cannot run in the browser, so the Java version needs a backend that does three jobs:

1. **Validate:** parse the code and report syntax errors with line and column.
2. **Explain:** turn the parsed code (the AST) into a flowchart.
3. **Trace:** run the program and record every step: line, local variables, call stack, output.

Build them in this order. Jobs 1 and 2 never execute user code, so they are safe and easy to deploy. Job 3 runs untrusted code, so it comes last and needs strict sandboxing (section 6). Every phase below ends with something you can demo.

## 2. Tech stack

- **Language and framework:** Java 21 or 25 (LTS) with the current stable Spring Boot, built with Maven.
- **Parsing:** JavaParser (`com.github.javaparser:javaparser-core`, latest 3.x). It gives you syntax errors and the AST.
- **Tracing:** the Java Debug Interface (JDI), which ships with the JDK. It lets one JVM step through another and read its variables.
- **Testing:** JUnit 5 and Spring Boot Test.
- **Frontend:** plain HTML, CSS and JavaScript in `src/main/resources/static`, reusing the prototype’s look. Move to React later only if you want to.
- **Deployment:** Docker, hosted on Render, Railway or Fly.io.

## 3. Project structure

```
java-code-visualizer/
├── .ai/                      # PROJECT.md, ARCHITECTURE.md, RULES.md for AI tools
├── pom.xml
├── Dockerfile
├── README.md
├── src/main/java/com/codevisualizer/
│   ├── CodeVisualizerApplication.java
│   ├── controller/AnalyzeController.java
│   ├── dto/  AnalyzeRequest, AnalyzeResponse, ErrorInfo,
│   │         FlowNode, FlowEdge, TraceStep
│   ├── service/ParserService.java       # syntax check
│   ├── service/FlowchartService.java    # AST to nodes and edges
│   ├── service/TraceService.java        # JDI step recorder
│   ├── sandbox/SandboxRunner.java       # limits, timeouts
│   └── config/ CorsConfig, RateLimitFilter
├── src/main/resources/
│   ├── static/ index.html, app.js, style.css
│   └── samples/ Factorial.java, EvenOdd.java, Recursion.java, BubbleSort.java
└── src/test/java/com/codevisualizer/   # one test class per service
```

## 4. API contract

Decide this first, because the backend and frontend both depend on it. One endpoint is enough: `POST /api/analyze`.

```json
{
  "valid": true,
  "errors": [ { "line": 3, "column": 14, "message": "';' expected" } ],
  "flowchart": {
    "nodes": [ { "id": "n1", "type": "decision", "label": "i <= n", "line": 3 } ],
    "edges": [ { "from": "n1", "to": "n2", "label": "Yes" } ]
  },
  "trace": [
    { "line": 4, "locals": { "i": "1", "arr": ["3","9"] },
      "stack": ["main()", "sum(4, 6)"], "stdout": "" }
  ]
}
```

When `valid` is false, return only `errors` and skip the other two fields.

## 5. Phases

Use one prompt at a time with your AI coding tool. After each one, read the code, run the tests, and commit before moving on.

### Phase 0: Setup (1 day)

**Done when:** the app starts and `GET /api/health` returns OK.

> Act as a senior Java engineer. Create a Spring Boot Maven project named java-code-visualizer (Java 21, package com.codevisualizer) with spring-boot-starter-web, spring-boot-starter-validation, spring-boot-starter-test and com.github.javaparser:javaparser-core. Create the packages controller, dto, service, sandbox and config, a GET /api/health endpoint, a CORS config, and a README skeleton. Explain each file in one line. Do not add any other features.

Also ask your AI tool to create the `.ai/` governance files (project goal, architecture, coding rules), so every later prompt stays consistent.

### Phase 1: Syntax check API (2 days)

**Done when:** broken code returns exact line and column errors; valid code returns `valid: true`.

> Add ParserService using JavaParser. Input: a Java source String. If the user pasted only statements, wrap them in a class with a main method before parsing, and adjust reported line numbers back. Return a list of ErrorInfo(line, column, message) from the parse problems. Add POST /api/analyze that returns { valid, errors }. Reject code longer than 10,000 characters. Write JUnit tests for valid code, a missing semicolon, an unclosed brace, and empty input.

### Phase 2: Flowchart from the AST (3 to 4 days)

**Done when:** four sample programs produce the right nodes and edges in tests.

> Add FlowchartService that walks the JavaParser AST of main() and any other methods and produces FlowNode(id, type, label, line) and FlowEdge(from, to, label). Node types: start, end, process, decision, io, call. Handle variable declarations, expression statements, System.out.println as io, if/else, for, while, do-while, enhanced for, return, and method calls. Add the flowchart to the /api/analyze response. Write tests with four small programs and assert node and edge counts.

### Phase 3: Frontend (3 days)

**Done when:** you can paste Java, see errors or a flowchart, and pick from samples.

> Create a static frontend in src/main/resources/static (index.html, app.js, style.css): a textarea, file upload, sample dropdown and a Visualize button. Call POST /api/analyze, show errors with line numbers, and draw the flowchart from nodes and edges as SVG with a top-down layout, decision branches labelled Yes and No. Support dark mode and mobile widths. No frameworks.

### Phase 4: Step tracing with JDI (5 to 7 days)

**Done when:** the response includes a trace, and a 500-step limit and a 5-second timeout both work.

> Add TraceService using the Java Debug Interface. Compile the user code with javax.tools.JavaCompiler into a temp directory, launch it in a child JVM with -Xmx64m using a LaunchingConnector, and set a StepRequest (STEP\_LINE, STEP\_INTO) on the main thread. At each step record the line, the local variables (name and value; arrays as lists; objects as class name plus fields) and the call stack frame names. Capture stdout. Exclude JDK classes with a class filter. Stop after 500 steps or 5 seconds. Return TraceStep\[\] in the response.

### Phase 5: Better visualization (4 days)

**Done when:** stepping through a program highlights the flowchart, code, stack and variables together.

> Upgrade the frontend to use the trace. Add Prev, Next, Play and a speed control. On each step highlight the current line, the matching flowchart node, and the top call-stack frame. Show arrays as indexed cells, flash the values that changed, and show objects as boxes with fields and arrows for references. Add a one-line plain-English caption such as "Checking the condition: i <= n". Add arrow-key navigation.

### Phase 6: Hardening and deploy (3 days)

**Done when:** the app is live at a public URL and survives abusive input.

> Write a multi-stage Dockerfile that runs as a non-root user on a JRE image. Add SandboxRunner so the child JVM runs with a hard timeout, heap limit and output cap. Add a RateLimitFilter allowing 10 requests per minute per IP, and a 10,000 character request limit. Add integration tests for an infinite loop, huge output, System.exit and deep recursion, and make sure the server survives each.

### Phase 7: Stretch goals

- Python support through a separate parser service.
- Shareable links that store a snippet in a database.
- A recursion-tree view that draws every call as a node.
- A "find the bug" mode that explains common errors in beginner-friendly language.

## 6. Security rules for running code

Never run user code on your server without isolation. A single `Runtime.exec` or infinite loop can take the site down or worse.

- Run tracing in a separate process, never inside the Spring Boot JVM.
- Prefer running that process in a container with no network, a read-only filesystem, a memory limit, a CPU limit and a process limit.
- Set a timeout of about 5 seconds, a heap of 64 MB, at most 500 steps and an output cap.
- Do not rely on the Java SecurityManager. It is deprecated and removed, so container isolation is your real protection.
- Keep Phases 1 to 3 (parse-only) deployable on their own, so the live demo works even if tracing is not ready.

## 7. Better visualization: what changed in the new page

The upgraded JavaScript page published alongside this roadmap is your design reference for Phase 5. It adds:

- **Linked views:** the flowchart box, code line, call stack and variables all highlight together on every step.
- **Decision steps:** every `if` and every loop iteration shows a condition check, so learners see why a branch was taken.
- **Call stack:** functions and recursion show frames being pushed and popped.
- **Array cells:** arrays appear as indexed boxes, with changed values highlighted.
- **Plain-English caption:** one sentence per step.
- **Playback controls:** Prev, Next, Play, a speed setting and arrow keys.

For Java, add object boxes with reference arrows, a heap area next to the stack, and visible `static` fields.

## 8. Testing and deployment checklist

- Unit tests for the parser, flowchart builder and trace service.
- Sample programs for each concept: loops, if/else, methods, recursion, arrays, sorting, objects.
- Abuse tests: infinite loop, huge output, `System.exit(0)`, deep recursion, giant input.
- Test on a phone-width screen and in dark mode.
- Environment variables for limits, with no secrets in the repo.
- A live URL and a health check.

## 9. Portfolio polish

- A README with a demo GIF, an architecture diagram, setup steps and the API contract.
- A live link and a short section on how you sandboxed code execution, since interviewers value that.
- Resume bullet example: *Built a Spring Boot code visualizer that parses Java with JavaParser, renders flowcharts and traces execution step by step through JDI in a sandboxed container, used by students to learn how code runs.*
- Tag releases as you finish each phase so your commit history shows steady progress.
