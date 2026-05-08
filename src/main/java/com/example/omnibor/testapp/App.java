package com.example.omnibor.testapp;

import java.security.MessageDigest;
import java.security.Security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Simple application that exercises jsoup, Log4j2, and
 * Bouncy Castle to produce a realistic Maven dependency
 * graph for OmniBOR sidecar SPDX validation.
 */
public final class App {

    private static final Logger LOG =
        LogManager.getLogger(App.class);

    private App() {
        // utility class
    }

    /**
     * Parse an HTML snippet with jsoup, compute its SHA-256
     * digest using Bouncy Castle, and log the result.
     */
    public static String analyzeHtml(String html) {
        LOG.info("Parsing HTML ({} chars)", html.length());

        Document doc = Jsoup.parse(html);
        String text = doc.body().text();
        LOG.debug("Extracted text: {}", text);

        Security.addProvider(new BouncyCastleProvider());
        try {
            MessageDigest digest =
                MessageDigest.getInstance("SHA-256", "BC");
            byte[] hash = digest.digest(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            String hex = Hex.toHexString(hash);
            LOG.info("SHA-256 (BC): {}", hex);
            return hex;
        } catch (Exception e) {
            LOG.error("Digest failed", e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        String html = args.length > 0
            ? args[0]
            : "<html><body><h1>OmniBOR Test</h1>"
              + "<p>Sidecar SPDX validation.</p>"
              + "</body></html>";

        String hash = analyzeHtml(html);
        System.out.println("Content hash: " + hash);
    }
}
