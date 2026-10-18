package com.flashreserve.lambda;

public record ReservationResult(
        String reservationId,
        String status,
        String message
) {
}