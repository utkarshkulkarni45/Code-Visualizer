package com.codevisualizer.dto;

import java.util.List;

public record AnalyzeResponse(
        boolean valid,
        List<ErrorInfo> errors,
        List<MethodFlow> methods,
        List<TraceStep> trace,
        boolean traceTruncated,
        List<String> warnings) {

    public static AnalyzeResponse invalid(List<ErrorInfo> errors, List<String> warnings) {
        return new AnalyzeResponse(false, errors, List.of(), List.of(), false, warnings);
    }
}
