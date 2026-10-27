package com.codevisualizer.service;

import com.codevisualizer.dto.AnalyzeResponse;
import com.codevisualizer.dto.ErrorInfo;
import com.codevisualizer.dto.MethodFlow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AnalyzeService {

    private final ParserService parserService;
    private final FlowchartService flowchartService;
    private final TraceService traceService;

    public AnalyzeService(ParserService parserService, FlowchartService flowchartService,
                          TraceService traceService) {
        this.parserService = parserService;
        this.flowchartService = flowchartService;
        this.traceService = traceService;
    }

    public AnalyzeResponse analyze(String code) {
        try {
            ParserService.Outcome outcome = parserService.parse(code);
            if (!outcome.ok()) {
                return AnalyzeResponse.invalid(outcome.errors(), outcome.warnings());
            }

            List<String> warnings = new ArrayList<>(outcome.warnings());
            List<MethodFlow> methods = flowchartService.build(outcome.parsed());
            TraceService.TraceResult trace = traceService.trace(outcome.parsed());
            warnings.addAll(trace.warnings());

            if (!trace.errors().isEmpty()) {
                return AnalyzeResponse.invalid(trace.errors(), dedupe(warnings));
            }
            if (methods.isEmpty()) {
                return AnalyzeResponse.invalid(List.of(new ErrorInfo(ErrorInfo.SYNTAX, 1, 1,
                        "No class with a method body was found.")), dedupe(warnings));
            }
            return new AnalyzeResponse(true, List.of(), methods, trace.steps(),
                    trace.truncated(), dedupe(warnings));
        } catch (Exception exception) {
            return AnalyzeResponse.invalid(List.of(new ErrorInfo(ErrorInfo.RUNTIME, 1, 1,
                    "Unexpected error while analyzing the code: " + exception.getMessage())), List.of());
        }
    }

    private List<String> dedupe(List<String> warnings) {
        return List.copyOf(new LinkedHashSet<>(warnings));
    }
}
