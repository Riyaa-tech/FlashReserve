package com.flashreserve.lambda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationServiceTest {

    private DynamoDbClient dynamoDb;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        service = new ReservationService(dynamoDb, "FlashReserve");
    }

    @Test
    void rejectsNullMessage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(null));

        assertEquals("Message is required", exception.getMessage());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void validationFailuresUseInvalidReservationMessageException() {
        // The SQS handler relies on this exact type to acknowledge
        // permanent failures instead of retrying them.
        assertThrows(
                InvalidReservationMessageException.class,
                () -> service.reserve(new ReservationMessage(
                        "res-1", "event-1", "customer-1", 0, "key-1")));
        assertThrows(
                InvalidReservationMessageException.class,
                () -> service.reserve(new ReservationMessage(
                        "res-1", " ", "customer-1", 1, "key-1")));
        assertThrows(
                InvalidReservationMessageException.class,
                () -> service.reserve(null));
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void rejectsZeroQuantity() {
        ReservationMessage message = new ReservationMessage(
                "res-1", "event-1", "customer-1", 0, "key-1");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(message));

        assertEquals(
                "Quantity must be greater than zero",
                exception.getMessage());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void rejectsNegativeQuantity() {
        ReservationMessage message = new ReservationMessage(
                "res-1", "event-1", "customer-1", -1, "key-1");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(message));

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void rejectsBlankEventId() {
        ReservationMessage message = new ReservationMessage(
                "res-1", " ", "customer-1", 1, "key-1");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.reserve(message));

        assertTrue(exception.getMessage().contains("eventId"));
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void submitsOneTransactionWithThreeOperations() {
        when(dynamoDb.transactWriteItems(
                any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        ReservationMessage message = new ReservationMessage(
                "res-1", "event-1", "customer-1", 2, "key-1");

        ReservationResult result = service.reserve(message);

        assertEquals("CONFIRMED", result.status());
        assertEquals("res-1", result.reservationId());

        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);

        verify(dynamoDb).transactWriteItems(captor.capture());

        TransactWriteItemsRequest request = captor.getValue();

        assertEquals(3, request.transactItems().size());

        assertTrue(request.transactItems().get(0).update() != null);
        assertTrue(request.transactItems().get(1).put() != null);
        assertTrue(request.transactItems().get(2).put() != null);

        assertEquals(
                "FlashReserve",
                request.transactItems().get(0).update().tableName());

        assertTrue(
                request.transactItems().get(0).update()
                        .conditionExpression()
                        .contains("#available >= :quantity"));

        assertEquals(
                "EVENT#event-1",
                request.transactItems().get(0).update()
                        .key().get("PK").s());

        assertEquals(
                "RESERVATION#res-1",
                request.transactItems().get(1).put()
                        .item().get("PK").s());

        assertEquals(
                "IDEMPOTENCY#key-1",
                request.transactItems().get(2).put()
                        .item().get("PK").s());
    }

@Test
void recordsDurableRejectionWhenInventoryIsSoldOut() {
    TransactionCanceledException cancelled =
            TransactionCanceledException.builder()
                    .message("Inventory condition failed")
                    .build();

    when(dynamoDb.transactWriteItems(
            any(TransactWriteItemsRequest.class)))
            .thenThrow(cancelled)
            .thenReturn(TransactWriteItemsResponse.builder().build());

    // First read: no previous idempotency outcome.
    when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(
                    GetItemResponse.builder().item(Map.of()).build(),
                    GetItemResponse.builder().item(Map.of(
                            "PK", AttributeValue.builder()
                                    .s("EVENT#event-1").build(),
                            "SK", AttributeValue.builder()
                                    .s("INVENTORY#GENERAL").build(),
                            "status", AttributeValue.builder()
                                    .s("ACTIVE").build(),
                            "availableQuantity", AttributeValue.builder()
                                    .n("0").build()
                    )).build());

    ReservationMessage message = new ReservationMessage(
            "res-1", "event-1", "customer-1", 2, "key-1");

    ReservationResult result = service.reserve(message);

    assertEquals("REJECTED", result.status());
    assertEquals("res-1", result.reservationId());

    ArgumentCaptor<TransactWriteItemsRequest> captor =
            ArgumentCaptor.forClass(TransactWriteItemsRequest.class);

    verify(dynamoDb, times(2))
            .transactWriteItems(captor.capture());

    TransactWriteItemsRequest rejectionRequest =
            captor.getAllValues().get(1);

    assertEquals(3, rejectionRequest.transactItems().size());
    assertNotNull(rejectionRequest.transactItems().get(0).conditionCheck());

    assertEquals(
            "REJECTED",
            rejectionRequest.transactItems().get(1)
                    .put().item().get("status").s());

    assertEquals(
            "REJECTED",
            rejectionRequest.transactItems().get(2)
                    .put().item().get("status").s());
}

@Test
void returnsPreviouslyRecordedRejectedOutcomeOnRetry() {
    ReservationMessage message = new ReservationMessage(
            "res-1", "event-1", "customer-1", 2, "key-1");

    Map<String, AttributeValue> previousOutcome = Map.of(
            "PK", AttributeValue.builder()
                    .s("IDEMPOTENCY#key-1").build(),
            "SK", AttributeValue.builder()
                    .s("REQUEST").build(),
            "reservationId", AttributeValue.builder()
                    .s("res-1").build(),
            "eventId", AttributeValue.builder()
                    .s("event-1").build(),
            "customerId", AttributeValue.builder()
                    .s("customer-1").build(),
            "quantity", AttributeValue.builder()
                    .n("2").build(),
            "status", AttributeValue.builder()
                    .s("REJECTED").build()
    );

    when(dynamoDb.transactWriteItems(
            any(TransactWriteItemsRequest.class)))
            .thenThrow(TransactionCanceledException.builder()
                    .message("Simulated duplicate delivery")
                    .build());

    when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                    .item(previousOutcome)
                    .build());

    ReservationResult result = service.reserve(message);

    assertEquals("REJECTED", result.status());
    assertEquals("res-1", result.reservationId());
    assertEquals("Returned previously recorded outcome",
            result.message());

    // The stored outcome is reused; no second transaction is attempted.
    verify(dynamoDb, times(1))
            .transactWriteItems(any(TransactWriteItemsRequest.class));
}

}
