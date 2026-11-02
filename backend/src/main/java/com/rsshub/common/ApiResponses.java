package com.rsshub.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One place that decides what an error looks like on the wire, so the endpoints Security writes by hand and the
 * {@code @RestControllerAdvice} write the same shape. Problem types are URNs, not URLs: nothing publishes a
 * problem-docs page for this service, and inventing one would be a link that 404s.
 */
@Component
public class ApiResponses {

    private final ObjectMapper objectMapper;

    public ApiResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:rsshub:" + code));
        return problem;
    }

    public void writeJson(HttpServletResponse response, HttpStatus status, Object body) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    public void writeProblem(HttpServletResponse response, HttpStatus status, String code, String detail)
        throws IOException {
        writeJson(response, status, problem(status, code, detail));
    }
}
