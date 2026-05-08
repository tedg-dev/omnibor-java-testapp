package com.example.omnibor.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Basic smoke tests for the test application.
 */
public class AppTest {

    @Test
    public void analyzeHtmlReturnsNonNull() {
        String result = App.analyzeHtml(
            "<html><body>Hello</body></html>"
        );
        assertNotNull(result);
    }

    @Test
    public void analyzeHtmlIsDeterministic() {
        String html = "<html><body>Deterministic</body></html>";
        String first = App.analyzeHtml(html);
        String second = App.analyzeHtml(html);
        assertEquals(first, second);
    }

    @Test
    public void analyzeHtmlProduces64HexChars() {
        String result = App.analyzeHtml(
            "<html><body>SHA-256</body></html>"
        );
        assertEquals(64, result.length());
    }
}
