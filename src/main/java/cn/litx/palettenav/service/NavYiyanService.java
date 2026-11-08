package cn.litx.palettenav.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Random quote provider backed by the bundled quotes file.
 */
@Slf4j
@Service
public class NavYiyanService {

    private static final String DEFAULT_QUOTE = "生活不止眼前的苟且，还有诗和远方的田野。";

    private final List<String> quotes;

    public NavYiyanService() {
        this.quotes = loadQuotes();
    }

    public String randomQuote() {
        if (quotes.isEmpty()) {
            return DEFAULT_QUOTE;
        }
        return quotes.get(ThreadLocalRandom.current().nextInt(quotes.size()));
    }

    private List<String> loadQuotes() {
        var result = new ArrayList<String>();
        try (var stream = getClass().getClassLoader().getResourceAsStream("data/quotes.txt")) {
            if (stream == null) {
                log.warn("Palette Nav quotes file is missing, using default quote");
                return List.of(DEFAULT_QUOTE);
            }
            try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var quote = line.trim();
                    if (!quote.isEmpty()) {
                        result.add(quote);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load Palette Nav quotes: {}", e.toString());
        }
        return result.isEmpty() ? List.of(DEFAULT_QUOTE) : List.copyOf(result);
    }
}
