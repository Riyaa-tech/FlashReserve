package com.flashreserve.lambda;

/**
 * Public view of a reservation. customerId and idempotencyKey are
 * deliberately not included.
 */
public record ReservationView(
        String reservationId,
        String eventId,
        int quantity,
        String status
) {
}
