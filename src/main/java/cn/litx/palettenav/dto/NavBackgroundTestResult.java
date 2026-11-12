package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NavBackgroundTestResult", description = "Random background API test result")
public record NavBackgroundTestResult(boolean success, String type, String url, String message) {
}
