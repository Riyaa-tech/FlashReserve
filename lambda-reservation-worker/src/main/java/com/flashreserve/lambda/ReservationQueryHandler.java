package com.flashreserve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.LambdaRuntime;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read API behind API Gateway (Lambda proxy integration):
 *
 *   GET /reservations/{reservationId}  -> CONFIRMED / REJECTED, or 404
 *   GET /events/{eventId}              -> available quantity, or 404
 *
 * Clients get generic error messages; details go to the logs only.
 */
public class ReservationQueryHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final String RESERVATION_ROUTE = "/reservations/{reservationId}";
    static final String EVENT_ROUTE = "/events/{eventId}";

    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final ReservationQueryService queryService;
    private final ObjectMapper objectMapper;
    private final String corsAllowedOrigin;

    public ReservationQueryHandler() {
        this(
                new ReservationQueryService(
                        DynamoDbClient.create(),
                        requireEnvironmentVariable("RESERVATIONS_TABLE")),
                new ObjectMapper(),
                System.getenv("CORS_ALLOWED_ORIGIN"));
    }

    public ReservationQueryHandler(
            ReservationQueryService queryService,
            ObjectMapper objectMapper,
            String corsAllowedOrigin) {

        this.queryService = Objects.requireNonNull(queryService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.corsAllowedOrigin = corsAllowedOrigin;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request, Context context) {

        LambdaLogger logger = logger(context);
        String route = request == null ? null : request.getResource();
        String id = null;
        APIGatewayProxyResponseEvent response;

        try {
            if (request == null || !"GET".equals(request.getHttpMethod())) {
                response = error(404, "NOT_FOUND", "Route not found");
            } else if (RESERVATION_ROUTE.equals(route)) {
                id = pathParameter(request, "reservationId");
                response = isValidId(id)
                        ? found(queryService.findReservation(id),
                                "Reservation not found. "
                                        + "It may still be processing.")
                        : error(400, "BAD_REQUEST", "Invalid reservationId");
            } else if (EVENT_ROUTE.equals(route)) {
                id = pathParameter(request, "eventId");
                response = isValidId(id)
                        ? found(queryService.findEvent(id),
                                "Event not found")
                        : error(400, "BAD_REQUEST", "Invalid eventId");
            } else {
                response = error(404, "NOT_FOUND", "Route not found");
            }
        } catch (Exception ex) {
            logger.log("QUERY_ERROR route=" + route
                    + " id=" + safeId(id)
                    + " error=" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage()
                    + System.lineSeparator());
            response = error(500, "INTERNAL_ERROR", "Please retry");
        }

        logger.log("QUERY route=" + route
                + " id=" + safeId(id)
                + " status=" + response.getStatusCode()
                + System.lineSeparator());

        return response;
    }

    private APIGatewayProxyResponseEvent found(
            Optional<?> result, String notFoundMessage) {

        return result
                .map(body -> json(200, body))
                .orElseGet(() -> error(404, "NOT_FOUND", notFoundMessage));
    }

    private APIGatewayProxyResponseEvent error(
            int statusCode, String error, String message) {

        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return json(statusCode, body);
    }

    private APIGatewayProxyResponseEvent json(int statusCode, Object body) {
        String serialized;

        try {
            serialized = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            statusCode = 500;
            serialized =
                    "{\"error\":\"INTERNAL_ERROR\",\"message\":\"Please retry\"}";
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        // Stop browsers/proxies caching a "not found" while the UI polls.
        headers.put("Cache-Control", "no-store");

        if (corsAllowedOrigin != null && !corsAllowedOrigin.isBlank()) {
            headers.put("Access-Control-Allow-Origin", corsAllowedOrigin);
        }

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(serialized);
    }

    private static String pathParameter(
            APIGatewayProxyRequestEvent request, String name) {

        Map<String, String> parameters = request.getPathParameters();
        return parameters == null ? null : parameters.get(name);
    }

    private static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /** Only log IDs that passed validation, so log lines stay clean. */
    private static String safeId(String id) {
        return isValidId(id) ? id : "<invalid>";
    }

    private static LambdaLogger logger(Context context) {
        return context != null && context.getLogger() != null
                ? context.getLogger()
                : LambdaRuntime.getLogger();
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + name);
        }

        return value;
    }
}
