package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NavBackgroundCandidate", description = "Selectable JSON image URL field")
public record NavBackgroundCandidate(String path, String url) {
}
