/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches archive class names against class white/black list rules.
 */
final class ClassNameFilter {
    private static final String CLASS_SUFFIX = ".class";
    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final String WEB_CLASSES = "WEB-INF/classes/";

    private ClassNameFilter() {
    }

    static boolean shouldInclude(String archiveClassName,
                                 String whiteText,
                                 String blackText) {
        String className = normalizeClassName(archiveClassName);
        List<String> whiteRules = parseRules(whiteText);
        if (!whiteRules.isEmpty() && !matchesAny(className, whiteRules)) {
            return false;
        }

        List<String> blackRules = parseRules(blackText);
        return blackRules.isEmpty() || !matchesAny(className, blackRules);
    }

    static boolean matches(String archiveClassName, String rawRule) {
        String className = normalizeClassName(archiveClassName);
        String rule = normalizeRule(rawRule);
        if (className.isEmpty() || rule.isEmpty()) {
            return false;
        }

        if (rule.indexOf('*') < 0) {
            if (rule.endsWith(".")) {
                return className.startsWith(rule);
            }
            return className.equals(rule);
        }

        // An unqualified rule without a leading wildcard describes the start
        // of the class name. A leading wildcard explicitly allows matching
        // anywhere in the fully qualified name.
        if (rule.indexOf('.') < 0 && !rule.startsWith("*")) {
            int separator = className.lastIndexOf('.');
            String simpleName = separator < 0
                    ? className
                    : className.substring(separator + 1);
            return globMatches(simpleName, rule);
        }
        return globMatches(className, rule);
    }

    private static boolean matchesAny(String className, List<String> rules) {
        for (String rule : rules) {
            if (matches(className, rule)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parseRules(String text) {
        List<String> rules = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return rules;
        }

        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String[] items = line.split(";");
            for (String item : items) {
                String rule = item.trim();
                if (rule.isEmpty() || isComment(rule)) {
                    continue;
                }
                rules.add(rule);
            }
        }
        return rules;
    }

    private static boolean isComment(String rule) {
        return rule.startsWith("#")
                || rule.startsWith("//")
                || rule.startsWith("/*");
    }

    private static String normalizeRule(String rule) {
        if (rule == null) {
            return "";
        }
        return rule.trim()
                .replace('\\', '.')
                .replace('/', '.');
    }

    private static String normalizeClassName(String archiveClassName) {
        if (archiveClassName == null) {
            return "";
        }

        String className = archiveClassName.trim().replace('\\', '/');
        int prefixIndex = className.indexOf(BOOT_CLASSES);
        if (prefixIndex >= 0) {
            className = className.substring(prefixIndex + BOOT_CLASSES.length());
        } else {
            prefixIndex = className.indexOf(WEB_CLASSES);
            if (prefixIndex >= 0) {
                className = className.substring(prefixIndex + WEB_CLASSES.length());
            }
        }

        while (className.startsWith("/")) {
            className = className.substring(1);
        }
        if (className.endsWith(CLASS_SUFFIX)) {
            className = className.substring(
                    0, className.length() - CLASS_SUFFIX.length());
        }
        return className.replace('/', '.');
    }

    /**
     * Linear-time glob matcher where '*' represents zero or more characters.
     */
    private static boolean globMatches(String value, String pattern) {
        int valueIndex = 0;
        int patternIndex = 0;
        int starIndex = -1;
        int retryValueIndex = -1;

        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                valueIndex++;
                patternIndex++;
            } else if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                retryValueIndex = valueIndex;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++retryValueIndex;
            } else {
                return false;
            }
        }

        while (patternIndex < pattern.length()
                && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
