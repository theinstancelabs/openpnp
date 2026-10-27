package org.openpnp.instancelabs.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP error with a plain-language reason. {@code code} is a short machine-readable slug
 * ("refused", "bad-request", "not-found", ...); {@code details} is merged into the JSON body (for
 * example the interlock violations that caused a refusal).
 */
public class ApiException extends Exception {
    private static final long serialVersionUID = 1L;

    public final int status;
    public final String code;
    public final Map<String, Object> details = new LinkedHashMap<>();

    public ApiException(int status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
    }

    public ApiException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public static ApiException badRequest(String reason) {
        return new ApiException(400, "bad-request", reason);
    }

    public static ApiException notFound(String reason) {
        return new ApiException(404, "not-found", reason);
    }

    /** The request is valid but the machine state or an interlock does not allow it now. */
    public static ApiException refused(String reason) {
        return new ApiException(409, "refused", reason);
    }

    public static ApiException unavailable(String reason) {
        return new ApiException(503, "unavailable", reason);
    }
}
