package com.codevisualizer.controller;

import com.codevisualizer.dto.AnalyzeRequest;
import com.codevisualizer.dto.AnalyzeResponse;
import com.codevisualizer.dto.SampleProgram;
import com.codevisualizer.service.AnalyzeService;
import com.codevisualizer.service.SampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final AnalyzeService analyzeService;
    private final SampleService sampleService;

    public AnalyzeController(AnalyzeService analyzeService, SampleService sampleService) {
        this.analyzeService = analyzeService;
        this.sampleService = sampleService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/samples")
    public List<SampleProgram> samples() {
        return sampleService.samples();
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody(required = false) AnalyzeRequest request) {
        return analyzeService.analyze(request == null ? null : request.code());
    }
}
