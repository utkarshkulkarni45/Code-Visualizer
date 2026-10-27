package com.codevisualizer.dto;

/**
 * A problem found while parsing, compiling or running the submitted code.
 * Line and column are in the coordinate system of the code the user pasted.
 */
public record ErrorInfo(String phase, int line, int column, String message) {

    public static final String SYNTAX = "syntax";
    public static final String COMPILE = "compile";
    public static final String RUNTIME = "runtime";
}
