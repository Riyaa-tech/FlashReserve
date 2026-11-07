package com.flashreserve.lambda;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/**
 * Key format of the FlashReserveReservations table, as written by the
 * reservation worker.
 *
 * Used by the read path. ReservationQueryServiceTest checks that these keys
 * match exactly what ReservationService writes, so the two cannot drift.
 */
final class TableKeys {

    static final String PK = "PK";
    static final String SK = "SK";

    private TableKeys() {
    }

    static Map<String, AttributeValue> reservation(String reservationId) {
        return key("RESERVATION#" + reservationId, "DETAILS");
    }

    static Map<String, AttributeValue> eventInventory(String eventId) {
        return key("EVENT#" + eventId, "INVENTORY#GENERAL");
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(
                PK, AttributeValue.builder().s(pk).build(),
                SK, AttributeValue.builder().s(sk).build());
    }
}
