package dev.fulfillmenthub.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class HttpProblem {
    private HttpProblem() {}
    static void write(HttpServletResponse response, int status, String fixedTitle) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"title\":\"" + fixedTitle + "\"}");
    }
}
