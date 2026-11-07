package com.flashreserve.lambda;

/** Public view of an event's inventory. */
public record EventView(
        String eventId,
        int availableQuantity,
        String status
) {
}
