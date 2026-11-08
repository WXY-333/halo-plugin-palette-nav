package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Page metadata used to pre-fill the console link form.
 *
 * <p>{@code fetched} is false when the page could not be read (timeout, TLS error,
 * non-HTML response); {@code name} and {@code icon} then contain host-based
 * fallbacks so the form still gets something useful.</p>
 */
@Schema(name = "NavLinkMeta", description = "Fetched metadata of a nav link target.")
public record NavLinkMeta(
    @Schema(description = "The requested URL", example = "https://www.example.com")
    String url,
    @Schema(description = "Site name or page title", example = "Example")
    String name,
    @Schema(description = "Resolved absolute icon URL", example = "https://www.example.com/favicon.ico")
    String icon,
    @Schema(description = "Whether the page itself was read successfully")
    boolean fetched
) {
}
