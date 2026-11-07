package com.flashreserve.lambda;

//import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationSqsHandlerTest {

    private ReservationService reservationService;
    private ReservationSqsHandler handler;

    @BeforeEach
    void setUp() {
        reservationService = mock(ReservationService.class);

        handler = new ReservationSqsHandler(
                reservationService,
                new ObjectMapper());
    }

    @Test
    void processesValidMessageWithoutFailures() {
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenReturn(new ReservationResult(
                        "res-1", "CONFIRMED", "Reservation confirmed"));

        SQSEvent event = eventWithMessages(
                sqsMessage("message-1",
                        """
                        {
                          "reservationId": "res-1",
                          "eventId": "event-1",
                          "customerId": "customer-1",
                          "quantity": 2,
                          "idempotencyKey": "key-1"
                        }
                        """));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());

        verify(reservationService).reserve(argThat(message ->
                message.reservationId().equals("res-1")
                        && message.eventId().equals("event-1")
                        && message.quantity() == 2));
    }

    // Previously "marksMalformedJsonAsFailedMessage": malformed JSON was
    // retried. It can never succeed, so it is now acknowledged instead.
    @Test
    void acknowledgesMalformedJsonWithoutRetry() {
        SQSEvent event = eventWithMessages(
                sqsMessage("bad-message", "{ invalid json"));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(reservationService);
    }

    @Test
    void acknowledgesWrongFieldTypeWithoutRetry() {
        SQSEvent event = eventWithMessages(
                sqsMessage("wrong-type",
                        """
                        {
                          "reservationId": "res-1",
                          "eventId": "event-1",
                          "customerId": "customer-1",
                          "quantity": "abc",
                          "idempotencyKey": "key-1"
                        }
                        """));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(reservationService);
    }

    @Test
    void acknowledgesEmptyBodyWithoutRetry() {
        SQSEvent event = eventWithMessages(
                sqsMessage("empty-body", "   "),
                sqsMessage("null-body", null));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verifyNoInteractions(reservationService);
    }

    @Test
    void acknowledgesValidationFailureWithoutRetry() {
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenThrow(new InvalidReservationMessageException(
                        "Quantity must be greater than zero"));

        SQSEvent event = eventWithMessages(
                sqsMessage("zero-quantity", validBody("res-1", 0)));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(reservationService, times(1))
                .reserve(any(ReservationMessage.class));
    }

    @Test
    void invalidMessageDoesNotBlockLaterMessagesInSameBatch() {
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenReturn(new ReservationResult(
                        "res-2", "CONFIRMED", "Reservation confirmed"));

        SQSEvent event = eventWithMessages(
                sqsMessage("bad-message", "{ invalid json"),
                sqsMessage("good-message", validBody("res-2", 1)));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(reservationService, times(1)).reserve(argThat(message ->
                "res-2".equals(message.reservationId())));
    }

    @Test
    void unexpectedIllegalArgumentExceptionIsStillRetried() {
        // Only InvalidReservationMessageException is treated as permanent.
        // A plain IllegalArgumentException may be a bug, so it must retry.
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenThrow(new IllegalArgumentException("unexpected bug"));

        SQSEvent event = eventWithMessages(
                sqsMessage("message-1", validBody("res-1", 1)),
                sqsMessage("message-2", validBody("res-2", 1)));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertEquals(
                List.of("message-1", "message-2"),
                response.getBatchItemFailures().stream()
                        .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                        .toList());
        verify(reservationService, times(1))
                .reserve(any(ReservationMessage.class));
    }


@Test
void stopsProcessingAfterFailureAndReportsRemainingMessages() {
    when(reservationService.reserve(any(ReservationMessage.class)))
            .thenReturn(new ReservationResult(
                    "res-1", "CONFIRMED", "Reservation confirmed"))
            .thenThrow(new RuntimeException("Temporary database failure"));

    SQSEvent event = eventWithMessages(
            sqsMessage("message-1",
                    """
                    {
                      "reservationId": "res-1",
                      "eventId": "event-1",
                      "customerId": "customer-1",
                      "quantity": 1,
                      "idempotencyKey": "key-1"
                    }
                    """),
            sqsMessage("message-2",
                    """
                    {
                      "reservationId": "res-2",
                      "eventId": "event-1",
                      "customerId": "customer-2",
                      "quantity": 1,
                      "idempotencyKey": "key-2"
                    }
                    """),
            sqsMessage("message-3",
                    """
                    {
                      "reservationId": "res-3",
                      "eventId": "event-1",
                      "customerId": "customer-3",
                      "quantity": 1,
                      "idempotencyKey": "key-3"
                    }
                    """));

    SQSBatchResponse response =
            handler.handleRequest(event, null);

    assertEquals(
            List.of("message-2", "message-3"),
            response.getBatchItemFailures().stream()
                    .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                    .toList());

    // Message 3 must not be processed after message 2 fails.
    verify(reservationService, times(2))
            .reserve(any(ReservationMessage.class));
}


    @Test
    void acceptsDurableRejectionAsSuccessfullyProcessed() {
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenReturn(new ReservationResult(
                        "res-1", "REJECTED",
                        "Requested quantity is unavailable"));

        SQSEvent event = eventWithMessages(
                sqsMessage("message-1",
                        """
                        {
                          "reservationId": "res-1",
                          "eventId": "event-1",
                          "customerId": "customer-1",
                          "quantity": 2,
                          "idempotencyKey": "key-1"
                        }
                        """));

        SQSBatchResponse response =
                handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
    }

    @Test
    void acknowledgesPermanentIdempotencyConflictWithoutRetry() {
        when(reservationService.reserve(any(ReservationMessage.class)))
                .thenThrow(new IdempotencyConflictException(
                        "Idempotency key was reused for a different request"));

        SQSEvent event = eventWithMessages(
                sqsMessage("conflict-message",
                        """
                        {
                          "reservationId": "res-conflict",
                          "eventId": "event-1",
                          "customerId": "customer-1",
                          "quantity": 1,
                          "idempotencyKey": "existing-key"
                        }
                        """));

        SQSBatchResponse response = handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(reservationService, times(1))
                .reserve(any(ReservationMessage.class));
    }
    private static String validBody(String reservationId, int quantity) {
        return """
                {
                  "reservationId": "%s",
                  "eventId": "event-1",
                  "customerId": "customer-1",
                  "quantity": %d,
                  "idempotencyKey": "key-%s"
                }
                """.formatted(reservationId, quantity, reservationId);
    }

    private static SQSEvent eventWithMessages(
            SQSEvent.SQSMessage... messages) {

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }

    private static SQSEvent.SQSMessage sqsMessage(
            String messageId, String body) {

        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        return message;
    }
}
