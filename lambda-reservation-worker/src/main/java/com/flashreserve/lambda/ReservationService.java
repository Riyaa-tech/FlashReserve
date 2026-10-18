package com.flashreserve.lambda;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ReservationService {

    private static final String INVENTORY_SK = "INVENTORY#GENERAL";
    private static final String DETAILS_SK = "DETAILS";
    private static final String IDEMPOTENCY_SK = "REQUEST";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public ReservationService(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb);

        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Table name cannot be blank");
        }

        this.tableName = tableName;
    }

    public ReservationResult reserve(ReservationMessage message) {
        validate(message);

        String inventoryPk = "EVENT#" + message.eventId();
        String reservationPk = "RESERVATION#" + message.reservationId();
        String idempotencyPk = "IDEMPOTENCY#" + message.idempotencyKey();

        Map<String, AttributeValue> values = Map.of(
                ":quantity", number(message.quantity()),
                ":active", string("ACTIVE")
        );

        Update decrementInventory = Update.builder()
                .tableName(tableName)
                .key(key(inventoryPk, INVENTORY_SK))
                .updateExpression(
                        "SET #available = #available - :quantity")
                .conditionExpression(
                        "#available >= :quantity AND #status = :active")
                .expressionAttributeNames(Map.of(
                        "#available", "availableQuantity",
                        "#status", "status"))
                .expressionAttributeValues(values)
                .build();

        Put reservation = Put.builder()
                .tableName(tableName)
                .item(reservationItem(message, reservationPk, "CONFIRMED"))
                .conditionExpression(
                        "attribute_not_exists(PK) AND attribute_not_exists(SK)")
                .build();

        Put idempotency = Put.builder()
                .tableName(tableName)
                .item(idempotencyItem(message, idempotencyPk, "CONFIRMED"))
                .conditionExpression(
                        "attribute_not_exists(PK) AND attribute_not_exists(SK)")
                .build();

        TransactWriteItemsRequest request =
                TransactWriteItemsRequest.builder()
                        .transactItems(
                                TransactWriteItem.builder()
                                        .update(decrementInventory).build(),
                                TransactWriteItem.builder()
                                        .put(reservation).build(),
                                TransactWriteItem.builder()
                                        .put(idempotency).build()
                        )
                        .build();

        try {
    dynamoDb.transactWriteItems(request);

    return new ReservationResult(
            message.reservationId(),
            "CONFIRMED",
            "Reservation confirmed");

} catch (TransactionCanceledException ex) {
    ReservationResult previous = findPreviousOutcome(
            message, idempotencyPk);

    if (previous != null) {
        return previous;
    }

    /*
     * Record REJECTED only if inventory is still unavailable
     * or inactive. Otherwise, leave the request retryable.
     */
    ReservationResult rejected = recordRejectionIfUnavailable(
            message,
            inventoryPk,
            reservationPk,
            idempotencyPk);

    if (rejected != null) {
        return rejected;
    }

    throw new ReservationProcessingException(
            "Transaction cancelled without a recorded outcome",
            ex);
}
    }

private ReservationResult recordRejectionIfUnavailable(
        ReservationMessage message,
        String inventoryPk,
        String reservationPk,
        String idempotencyPk) {

    Map<String, AttributeValue> inventoryKey =
            key(inventoryPk, INVENTORY_SK);

    Map<String, AttributeValue> inventory = dynamoDb.getItem(
            GetItemRequest.builder()
                    .tableName(tableName)
                    .key(inventoryKey)
                    .consistentRead(true)
                    .build()
    ).item();

    boolean unavailable = inventory == null
            || inventory.isEmpty();

    if (!unavailable) {
        String status = readString(inventory, "status");
        AttributeValue availableValue =
                inventory.get("availableQuantity");

        if (status == null
                || availableValue == null
                || availableValue.n() == null) {
            throw new ReservationProcessingException(
                    "Inventory record is incomplete",
                    new IllegalStateException(
                            "Missing inventory status or quantity"));
        }

        int available = Integer.parseInt(availableValue.n());

        unavailable = !"ACTIVE".equals(status)
                || available < message.quantity();
    }

    if (!unavailable) {
        // Stock may have changed after the original transaction failed.
        return null;
    }

    ConditionCheck inventoryStillUnavailable =
            ConditionCheck.builder()
                    .tableName(tableName)
                    .key(inventoryKey)
                    .conditionExpression(
                            "attribute_not_exists(PK) "
                            + "OR #status <> :active "
                            + "OR #available < :quantity")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#available", "availableQuantity"))
                    .expressionAttributeValues(Map.of(
                            ":active", string("ACTIVE"),
                            ":quantity", number(message.quantity())))
                    .build();

    Put rejectedReservation = Put.builder()
            .tableName(tableName)
            .item(reservationItem(
                    message, reservationPk, "REJECTED"))
            .conditionExpression(
                    "attribute_not_exists(PK) "
                    + "AND attribute_not_exists(SK)")
            .build();

    Put rejectedIdempotency = Put.builder()
            .tableName(tableName)
            .item(idempotencyItem(
                    message, idempotencyPk, "REJECTED"))
            .conditionExpression(
                    "attribute_not_exists(PK) "
                    + "AND attribute_not_exists(SK)")
            .build();

    try {
        dynamoDb.transactWriteItems(
                TransactWriteItemsRequest.builder()
                        .transactItems(
                                TransactWriteItem.builder()
                                        .conditionCheck(
                                                inventoryStillUnavailable)
                                        .build(),
                                TransactWriteItem.builder()
                                        .put(rejectedReservation)
                                        .build(),
                                TransactWriteItem.builder()
                                        .put(rejectedIdempotency)
                                        .build())
                        .build());

        return new ReservationResult(
                message.reservationId(),
                "REJECTED",
                "Requested quantity is unavailable");

    } catch (TransactionCanceledException ex) {
        /*
         * Another concurrent delivery may have recorded the outcome.
         */
        ReservationResult previous = findPreviousOutcome(
                message, idempotencyPk);

        if (previous != null) {
            return previous;
        }

        // Do not guess why the rejection transaction failed.
        throw new ReservationProcessingException(
                "Could not record reservation rejection",
                ex);
    }
}
    private ReservationResult findPreviousOutcome(
            ReservationMessage message, String idempotencyPk) {

        Map<String, AttributeValue> item = dynamoDb.getItem(
                GetItemRequest.builder()
                        .tableName(tableName)
                        .key(key(idempotencyPk, IDEMPOTENCY_SK))
                        .consistentRead(true)
                        .build()
        ).item();

        if (item == null || item.isEmpty()) {
            return null;
        }

        boolean sameRequest =
                message.reservationId().equals(
                        readString(item, "reservationId"))
                && message.eventId().equals(
                        readString(item, "eventId"))
                && message.customerId().equals(
                        readString(item, "customerId"))
                && message.quantity() == readInt(item, "quantity");

        if (!sameRequest) {
            throw new IdempotencyConflictException("Idempotency key was reused for a different request");
        }

        String status = readString(item, "status");

        if (!"CONFIRMED".equals(status) && !"REJECTED".equals(status)) {
            throw new ReservationProcessingException(
                    "Existing idempotency record has an unknown status",
                    new IllegalStateException("Unexpected status: " + status));
        }

        return new ReservationResult(
                message.reservationId(),
                status,
                "Returned previously recorded outcome");
    }

    private static void validate(ReservationMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Message is required");
        }

        requireText(message.reservationId(), "reservationId");
        requireText(message.eventId(), "eventId");
        requireText(message.customerId(), "customerId");
        requireText(message.idempotencyKey(), "idempotencyKey");

        if (message.quantity() <= 0) {
            throw new IllegalArgumentException(
                    "Quantity must be greater than zero");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("PK", string(pk), "SK", string(sk));
    }

    private static Map<String, AttributeValue> reservationItem(
        ReservationMessage message,
        String pk,
        String status) {

    Map<String, AttributeValue> item =
            new HashMap<>(key(pk, DETAILS_SK));

    item.put("reservationId", string(message.reservationId()));
    item.put("eventId", string(message.eventId()));
    item.put("customerId", string(message.customerId()));
    item.put("quantity", number(message.quantity()));
    item.put("status", string(status));
    item.put("idempotencyKey", string(message.idempotencyKey()));

    return item;
}

private static Map<String, AttributeValue> idempotencyItem(
        ReservationMessage message,
        String pk,
        String status) {

    Map<String, AttributeValue> item =
            new HashMap<>(key(pk, IDEMPOTENCY_SK));

    item.put("reservationId", string(message.reservationId()));
    item.put("eventId", string(message.eventId()));
    item.put("customerId", string(message.customerId()));
    item.put("quantity", number(message.quantity()));
    item.put("status", string(status));

    return item;
}

    private static String readString(
            Map<String, AttributeValue> item, String attribute) {

        AttributeValue value = item.get(attribute);
        return value == null ? null : value.s();
    }

    private static int readInt(
            Map<String, AttributeValue> item, String attribute) {

        AttributeValue value = item.get(attribute);

        if (value == null || value.n() == null) {
            throw new ReservationProcessingException(
                    "Invalid idempotency record",
                    new IllegalStateException(
                            "Missing numeric attribute: " + attribute));
        }

        return Integer.parseInt(value.n());
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder()
                .n(Integer.toString(value))
                .build();
    }
}
