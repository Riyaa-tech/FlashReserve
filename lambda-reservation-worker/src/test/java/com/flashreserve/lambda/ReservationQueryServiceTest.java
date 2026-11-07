package com.flashreserve.lambda;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationQueryServiceTest {

    private DynamoDbClient dynamoDb;
    private ReservationQueryService service;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        service = new ReservationQueryService(dynamoDb, "FlashReserve");
    }

    @Test
    void returnsConfirmedReservationUsingStronglyConsistentRead() {
        returnItem(reservationItem("CONFIRMED"));

        Optional<ReservationView> result = service.findReservation("res-1");

        assertEquals(
                Optional.of(new ReservationView(
                        "res-1", "event-1", 2, "CONFIRMED")),
                result);

        GetItemRequest request = capturedGetItem();
        assertEquals("FlashReserve", request.tableName());
        assertEquals(TableKeys.reservation("res-1"), request.key());
        assertTrue(request.consistentRead());
        verifyNoMoreInteractions(dynamoDb); // GetItem only, no writes
    }

    @Test
    void returnsDurableRejectedReservation() {
        returnItem(reservationItem("REJECTED"));

        assertEquals(
                "REJECTED",
                service.findReservation("res-1").orElseThrow().status());
    }

    @Test
    void returnsEmptyWhenReservationDoesNotExist() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(service.findReservation("res-unknown").isEmpty());
    }

    @Test
    void rejectsUnexpectedStoredStatus() {
        returnItem(reservationItem("PENDING"));

        assertThrows(
                IllegalStateException.class,
                () -> service.findReservation("res-1"));
    }

    @Test
    void returnsEventInventoryUsingEventuallyConsistentRead() {
        returnItem(Map.of(
                "PK", s("EVENT#event-1"),
                "SK", s("INVENTORY#GENERAL"),
                "availableQuantity", n(7),
                "status", s("ACTIVE")));

        assertEquals(
                Optional.of(new EventView("event-1", 7, "ACTIVE")),
                service.findEvent("event-1"));

        GetItemRequest request = capturedGetItem();
        assertEquals(TableKeys.eventInventory("event-1"), request.key());
        assertFalse(request.consistentRead());
        verifyNoMoreInteractions(dynamoDb);
    }

    @Test
    void returnsEmptyWhenEventDoesNotExist() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());

        assertTrue(service.findEvent("event-unknown").isEmpty());
    }

    /*
     * Guard test: runs the real worker code and checks that the keys it
     * writes are exactly the keys the reader looks up.
     */
    @Test
    void readerKeysMatchKeysWrittenByWorker() {
        DynamoDbClient workerDb = mock(DynamoDbClient.class);
        when(workerDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        new ReservationService(workerDb, "FlashReserve").reserve(
                new ReservationMessage(
                        "res-1", "event-1", "customer-1", 2, "key-1"));

        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(workerDb).transactWriteItems(captor.capture());

        TransactWriteItem inventoryUpdate =
                captor.getValue().transactItems().get(0);
        TransactWriteItem reservationPut =
                captor.getValue().transactItems().get(1);

        assertEquals(
                TableKeys.eventInventory("event-1"),
                inventoryUpdate.update().key());

        Map<String, AttributeValue> written = reservationPut.put().item();
        assertEquals(
                TableKeys.reservation("res-1"),
                Map.of("PK", written.get("PK"), "SK", written.get("SK")));
    }

    private void returnItem(Map<String, AttributeValue> item) {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
    }

    private GetItemRequest capturedGetItem() {
        ArgumentCaptor<GetItemRequest> captor =
                ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        return captor.getValue();
    }

    private static Map<String, AttributeValue> reservationItem(String status) {
        return Map.of(
                "PK", s("RESERVATION#res-1"),
                "SK", s("DETAILS"),
                "reservationId", s("res-1"),
                "eventId", s("event-1"),
                "customerId", s("customer-1"),
                "quantity", n(2),
                "status", s(status),
                "idempotencyKey", s("key-1"));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }
}
