package com.flashreserve.lambda;

public record ReservationMessage(
        String reservationId,
        String eventId,
        String customerId,
        int quantity,
        String idempotencyKey
) {
}
