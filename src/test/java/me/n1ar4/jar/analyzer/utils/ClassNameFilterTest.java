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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassNameFilterTest {
    @Test
    void trailingWildcardMatchesClassNamePrefix() {
        assertTrue(ClassNameFilter.matches(
                "com.abc.markerClient", "marker*"));
        assertFalse(ClassNameFilter.matches(
                "com.abc.Clientmarker", "marker*"));
    }

    @Test
    void surroundingWildcardsMatchAnywhere() {
        assertTrue(ClassNameFilter.matches(
                "com.abc.markerClient", "*marker*"));
        assertTrue(ClassNameFilter.matches(
                "com.abc.Clientmarker", "*marker*"));
        assertTrue(ClassNameFilter.matches(
                "com.marker.api.Client", "*marker*"));
    }

    @Test
    void leadingWildcardMatchesFullyQualifiedNameSuffix() {
        assertFalse(ClassNameFilter.matches(
                "com.abc.markerClient", "*marker"));
        assertTrue(ClassNameFilter.matches(
                "com.abc.Clientmarker", "*marker"));
    }

    @Test
    void supportsWildcardsInsideQualifiedPatterns() {
        assertTrue(ClassNameFilter.matches(
                "com.example.service.OrderService", "com.*.service.*"));
        assertFalse(ClassNameFilter.matches(
                "org.example.service.OrderService", "com.*.service.*"));
    }

    @Test
    void preservesExactClassAndPackagePrefixRules() {
        assertTrue(ClassNameFilter.matches(
                "com/example/Exact.class", "com.example.Exact"));
        assertFalse(ClassNameFilter.matches(
                "com/example/Other.class", "com.example.Exact"));
        assertTrue(ClassNameFilter.matches(
                "com/example/sub/Other.class", "com.example."));
        assertTrue(ClassNameFilter.matches(
                "com/example/sub/Other.class", "com.example.*"));
    }

    @Test
    void normalizesApplicationArchiveClassPaths() {
        assertTrue(ClassNameFilter.matches(
                "BOOT-INF/classes/com/example/ApiHandler.class", "*Handler"));
        assertTrue(ClassNameFilter.matches(
                "WEB-INF\\classes\\com\\example\\ApiHandler.class",
                "com.example.*"));
    }

    @Test
    void emptyOrCommentOnlyWhitelistAllowsAllClasses() {
        assertTrue(ClassNameFilter.shouldInclude(
                "com/example/ApiHandler.class", "# no active rules", ""));
    }

    @Test
    void parsesNewlinesSemicolonsAndComments() {
        String whiteRules = "# selected classes\ncom.example.Other;*Handler";
        assertTrue(ClassNameFilter.shouldInclude(
                "com/example/ApiHandler.class", whiteRules, ""));
        assertFalse(ClassNameFilter.shouldInclude(
                "com/example/ApiService.class", whiteRules, ""));
    }

    @Test
    void blacklistTakesPrecedenceOverWhitelist() {
        assertFalse(ClassNameFilter.shouldInclude(
                "com/example/InternalHandler.class", "*Handler", "*InternalHandler"));
        assertTrue(ClassNameFilter.shouldInclude(
                "com/example/PublicHandler.class", "*Handler", "*InternalHandler"));
    }
}
