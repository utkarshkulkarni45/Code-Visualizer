package com.codevisualizer.dto;

import java.util.List;

public record MethodFlow(
        String name,
        String signature,
        String className,
        int line,
        List<FlowNode> nodes,
        List<FlowEdge> edges) {
}
