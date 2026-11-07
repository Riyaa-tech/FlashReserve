package com.flashreserve.lambda;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only access to reservation and inventory records.
 * Uses GetItem only: no Scan, Query, or writes.
 */
public class ReservationQueryService {

    private static final Set<String> RESERVATION_STATUSES =
            Set.of("CONFIRMED", "REJECTED");

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public ReservationQueryService(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb);

        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be blank");
        }

        this.tableName = tableName;
    }

    /**
     * Strongly consistent: a poll that arrives right after the worker
     * writes the outcome must see it.
     */
    public Optional<ReservationView> findReservation(String reservationId) {
        Map<String, AttributeValue> item =
                getItem(TableKeys.reservation(reservationId), true);

        if (item == null) {
            return Optional.empty();
        }

        String status = requireString(item, "status");

        if (!RESERVATION_STATUSES.contains(status)) {
            throw new IllegalStateException(
                    "Unexpected reservation status: " + status);
        }

        return Optional.of(new ReservationView(
                requireString(item, "reservationId"),
                requireString(item, "eventId"),
                requireInt(item, "quantity"),
                status));
    }

    /**
     * Eventually consistent: the count is for display only. Overselling is
     * prevented by the worker's conditional write, not by this read.
     */
    public Optional<EventView> findEvent(String eventId) {
        Map<String, AttributeValue> item =
                getItem(TableKeys.eventInventory(eventId), false);

        if (item == null) {
            return Optional.empty();
        }

        return Optional.of(new EventView(
                eventId,
                requireInt(item, "availableQuantity"),
                requireString(item, "status")));
    }

    private Map<String, AttributeValue> getItem(
            Map<String, AttributeValue> key, boolean consistentRead) {

        Map<String, AttributeValue> item = dynamoDb.getItem(
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key)
                        .consistentRead(consistentRead)
                        .build()
        ).item();

        return item == null || item.isEmpty() ? null : item;
    }

    private static String requireString(
            Map<String, AttributeValue> item, String attribute) {

        AttributeValue value = item.get(attribute);

        if (value == null || value.s() == null) {
            throw new IllegalStateException(
                    "Record is missing attribute: " + attribute);
        }

        return value.s();
    }

    private static int requireInt(
            Map<String, AttributeValue> item, String attribute) {

        AttributeValue value = item.get(attribute);

        if (value == null || value.n() == null) {
            throw new IllegalStateException(
                    "Record is missing numeric attribute: " + attribute);
        }

        return Integer.parseInt(value.n());
    }
}
