package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NavBackgroundTestRequest")
public record NavBackgroundTestRequest(String url, String jsonPath) {
}
