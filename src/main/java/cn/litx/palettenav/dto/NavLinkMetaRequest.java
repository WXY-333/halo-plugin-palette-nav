package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for reading a link target's metadata.
 */
@Schema(name = "NavLinkMetaRequest", description = "Request body for reading a link target's metadata.")
public record NavLinkMetaRequest(
    @Schema(description = "The http(s) URL to read", example = "https://www.example.com",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String url
) {
}