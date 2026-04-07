package enkan.example.faas.shared;

import net.unit8.raoh.Issues;
import net.unit8.raoh.encode.Encoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raoh {@link Encoder}s that produce RFC 9457-style Problem JSON as {@code Map<String, Object>}.
 *
 * <p>{@code SerDesMiddleware} + {@code JsonBodyWriter} serializes the map without reflection.
 *
 * <p>Usage:
 * <pre>{@code
 * return new ApiResponse(404, ProblemEncoder.notFound());
 * return new ApiResponse(400, ProblemEncoder.fromIssues(issues));
 * }</pre>
 */
public final class ProblemEncoder {

    private static final Map<Integer, String> TITLES = Map.of(
            400, "Bad Request",
            404, "Not Found",
            500, "Internal Server Error"
    );

    private ProblemEncoder() {}

    /** Returns a 404 Not Found problem map. */
    public static Map<String, Object> notFound() {
        return problem(404, null, null);
    }

    /**
     * Returns a 400 Bad Request problem map with validation violations
     * derived from Raoh {@link Issues}.
     *
     * <p>The violations are encoded using {@link Issues#flatten()},
     * which maps each JSON Pointer path to its list of error messages.
     */
    public static Map<String, Object> fromIssues(Issues issues) {
        Map<String, Object> map = problem(400, null, null);
        map.put("violations", issues.flatten());
        return map;
    }

    private static Map<String, Object> problem(int status, String detail, String instance) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "about:blank");
        map.put("title", TITLES.getOrDefault(status, "Problem occurs"));
        map.put("status", status);
        if (detail != null) map.put("detail", detail);
        if (instance != null) map.put("instance", instance);
        return map;
    }
}
