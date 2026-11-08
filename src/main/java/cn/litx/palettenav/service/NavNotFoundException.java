package cn.litx.palettenav.service;

/**
 * Thrown when a requested Palette Nav entity (or one of its dependants) does not exist.
 *
 * <p>Console endpoints map this to HTTP 404, while other {@link IllegalArgumentException}
 * values are treated as bad requests.</p>
 */
public class NavNotFoundException extends RuntimeException {

    public NavNotFoundException(String message) {
        super(message);
    }
}