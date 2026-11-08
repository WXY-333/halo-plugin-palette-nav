package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Simple success response for write operations without a business payload.
 */
@Schema(name = "NavOkResult", description = "Operation result.")
public record OkResult(boolean ok) {
}
