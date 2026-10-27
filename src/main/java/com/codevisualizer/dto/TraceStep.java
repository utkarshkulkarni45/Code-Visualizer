package com.codevisualizer.dto;

import java.util.List;

/**
 * One recorded execution step. {@code output} is the complete stdout captured
 * so far, which lets the UI avoid guessing about incremental output.
 */
public record TraceStep(
        int index,
        int line,
        String caption,
        String method,
        List<VariableInfo> locals,
        List<String> stack,
        String output) {
}
