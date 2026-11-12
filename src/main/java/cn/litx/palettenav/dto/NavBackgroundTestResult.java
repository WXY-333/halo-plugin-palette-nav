package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NavBackgroundTestResult", description = "Random background API test result")
public record NavBackgroundTestResult(boolean success, String type, String url, String message,
    java.util.List<NavBackgroundCandidate> candidates, String jsonPreview, String jsonPath) {
    public NavBackgroundTestResult(boolean success, String type, String url, String message) {
        this(success, type, url, message, java.util.List.of(), "", "");
    }

    public NavBackgroundTestResult withImage(boolean valid, String image, String message) {
        return new NavBackgroundTestResult(valid, type, image, message, candidates, jsonPreview, jsonPath);
    }
}
