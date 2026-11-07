package com.flashreserve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationQueryHandlerTest {

    private ReservationQueryService queryService;
    private ReservationQueryHandler handler;

    @BeforeEach
    void setUp() {
        queryService = mock(ReservationQueryService.class);
        handler = new ReservationQueryHandler(
                queryService, new ObjectMapper(), null);
    }

    @Test
    void returnsReservationAsJsonWithoutPrivateFields() {
        when(queryService.findReservation("res-1"))
                .thenReturn(Optional.of(new ReservationView(
                        "res-1", "event-1", 2, "CONFIRMED")));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                reservationRequest("res-1"), null);

        assertEquals(200, response.getStatusCode());
        assertEquals(
                "{\"reservationId\":\"res-1\",\"eventId\":\"event-1\","
                        + "\"quantity\":2,\"status\":\"CONFIRMED\"}",
                response.getBody());
        assertFalse(response.getBody().contains("customer"));
        assertFalse(response.getBody().contains("idempotency"));
        assertEquals("application/json",
                response.getHeaders().get("Content-Type"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
    }

    @Test
    void returnsRejectedReservation() {
        when(queryService.findReservation("res-1"))
                .thenReturn(Optional.of(new ReservationView(
                        "res-1", "event-1", 2, "REJECTED")));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                reservationRequest("res-1"), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"status\":\"REJECTED\""));
    }

    @Test
    void returns404WhenReservationIsNotFoundYet() {
        when(queryService.findReservation("res-1"))
                .thenReturn(Optional.empty());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                reservationRequest("res-1"), null);

        assertEquals(404, response.getStatusCode());
        assertEquals(
                "{\"error\":\"NOT_FOUND\",\"message\":\"Reservation not "
                        + "found. It may still be processing.\"}",
                response.getBody());
    }

    @Test
    void rejectsInvalidIdsWithoutCallingDynamoDb() {
        for (String badId : new String[] {
                "", "has space", "../etc", "res#1", "x".repeat(129)}) {

            APIGatewayProxyResponseEvent response = handler.handleRequest(
                    reservationRequest(badId), null);

            assertEquals(400, response.getStatusCode(), badId);
        }

        APIGatewayProxyRequestEvent missing = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withResource(ReservationQueryHandler.RESERVATION_ROUTE);
        assertEquals(400, handler.handleRequest(missing, null).getStatusCode());

        verifyNoInteractions(queryService);
    }

    @Test
    void returnsEventInventory() {
        when(queryService.findEvent("event-1"))
                .thenReturn(Optional.of(new EventView("event-1", 7, "ACTIVE")));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(ReservationQueryHandler.EVENT_ROUTE,
                        "eventId", "event-1"),
                null);

        assertEquals(200, response.getStatusCode());
        assertEquals(
                "{\"eventId\":\"event-1\",\"availableQuantity\":7,"
                        + "\"status\":\"ACTIVE\"}",
                response.getBody());
    }

    @Test
    void returns404WhenEventDoesNotExist() {
        when(queryService.findEvent("event-1")).thenReturn(Optional.empty());

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(ReservationQueryHandler.EVENT_ROUTE,
                        "eventId", "event-1"),
                null);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void returnsGenericErrorWithoutLeakingDetails() {
        when(queryService.findReservation("res-1"))
                .thenThrow(new RuntimeException("DynamoDB secret detail"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                reservationRequest("res-1"), null);

        assertEquals(500, response.getStatusCode());
        assertEquals(
                "{\"error\":\"INTERNAL_ERROR\",\"message\":\"Please retry\"}",
                response.getBody());
    }

    @Test
    void returns404ForUnknownRouteOrMethod() {
        APIGatewayProxyRequestEvent unknown = request(
                "/admin/{id}", "id", "x");
        APIGatewayProxyRequestEvent post = reservationRequest("res-1")
                .withHttpMethod("POST");

        assertEquals(404, handler.handleRequest(unknown, null).getStatusCode());
        assertEquals(404, handler.handleRequest(post, null).getStatusCode());
        verifyNoInteractions(queryService);
    }

    @Test
    void addsCorsHeaderOnlyWhenOriginIsConfigured() {
        when(queryService.findReservation("res-1"))
                .thenReturn(Optional.empty());

        assertNull(handler.handleRequest(reservationRequest("res-1"), null)
                .getHeaders().get("Access-Control-Allow-Origin"));

        ReservationQueryHandler withCors = new ReservationQueryHandler(
                queryService, new ObjectMapper(), "https://example.com");

        assertEquals(
                "https://example.com",
                withCors.handleRequest(reservationRequest("res-1"), null)
                        .getHeaders().get("Access-Control-Allow-Origin"));
    }

    private static APIGatewayProxyRequestEvent reservationRequest(String id) {
        return request(
                ReservationQueryHandler.RESERVATION_ROUTE, "reservationId", id);
    }

    private static APIGatewayProxyRequestEvent request(
            String resource, String parameter, String value) {

        return new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withResource(resource)
                .withPathParameters(Map.of(parameter, value));
    }
}
