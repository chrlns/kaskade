package pub.source.kaskade;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixersValidatorsTest {

    @Test
    void trimsWhitespace() {
        assertEquals("x", Fixers.trim().fix("  x \n"));
    }

    @Test
    void stripCodeFenceRemovesFenceAndLanguageTag() {
        assertEquals("{}\n", Fixers.stripCodeFence().fix("```json\n{}\n```"));
        assertEquals("code\n", Fixers.stripCodeFence().fix("```\ncode\n```"));
        assertEquals("plain", Fixers.stripCodeFence().fix("plain"));
        assertEquals("```json\n{", Fixers.stripCodeFence().fix("```json\n{"));
    }

    @Test
    void extractJsonDropsSurroundingProse() {
        assertEquals("{\"a\": 1}", Fixers.extractJson().fix("Sure! {\"a\": 1} hope that helps"));
        assertEquals("no json", Fixers.extractJson().fix("no json"));
    }

    @Test
    void regexReplaceRewritesMatches() {
        assertEquals("a-b", Fixers.regexReplace(Pattern.compile("x+"), "-").fix("axxb"));
    }

    @Test
    void nonEmptyAcceptsOnlyMeaningfulResponses() {
        assertTrue(Validators.nonEmpty().test(" x "));
        assertFalse(Validators.nonEmpty().test("   "));
        assertFalse(Validators.nonEmpty().test(null));
    }

    @Test
    void containsMatchesAndMaxLength() {
        assertTrue(Validators.contains("ERROR").test("an ERROR occurred"));
        assertTrue(Validators.matches(Pattern.compile("\\d+")).test("42"));
        assertFalse(Validators.matches(Pattern.compile("\\d+")).test("42a"));
        assertTrue(Validators.maxLength(3).test("abc"));
        assertFalse(Validators.maxLength(3).test("abcd"));
        assertThrows(IllegalArgumentException.class, () -> Validators.maxLength(-1));
    }

    @Test
    void isJsonAcceptsValidDocuments() {
        Validator isJson = Validators.isJson();
        assertTrue(isJson.test("{}"));
        assertTrue(isJson.test("[]"));
        assertTrue(isJson.test("  {\"a\": 1}  "));
        assertTrue(isJson.test("{\"a\": [1, 2.5e3, \"s\\n\", true, null]}"));
        assertTrue(isJson.test("\"\\u00e9\""));
        assertTrue(isJson.test("-0.5"));
        assertTrue(isJson.test("0.5"));
        assertTrue(isJson.test("0"));
        assertTrue(isJson.test("[[[[[\"deep\"]]]]]"));
    }

    @Test
    void isJsonRejectsInvalidDocuments() {
        Validator isJson = Validators.isJson();
        assertFalse(isJson.test(""));
        assertFalse(isJson.test("   "));
        assertFalse(isJson.test("{"));
        assertFalse(isJson.test("{\"a\"}"));
        assertFalse(isJson.test("{\"a\":1,}"));
        assertFalse(isJson.test("{'a':1}"));
        assertFalse(isJson.test("{\"a\":1} trailing"));
        assertFalse(isJson.test("tru"));
        assertFalse(isJson.test("01"));
        assertFalse(isJson.test("1."));
        assertFalse(isJson.test("1e"));
        assertFalse(isJson.test("\"tab\there\""));
        assertFalse(isJson.test("\"\\q\""));
        assertFalse(isJson.test("\"\\u12g4\""));
        assertFalse(isJson.test("[1, 2,]"));
        // hostile deep nesting must be a clean rejection, not a crash
        assertFalse(isJson.test("[".repeat(10000) + "]".repeat(10000)));
    }
}
