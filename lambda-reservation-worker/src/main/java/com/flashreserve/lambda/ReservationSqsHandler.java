package com.flashreserve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReservationSqsHandler
        implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    public ReservationSqsHandler() {
        this(
                new ReservationService(
                        software.amazon.awssdk.services.dynamodb.DynamoDbClient
                                .create(),
                        requireEnvironmentVariable("RESERVATIONS_TABLE")),
                new ObjectMapper());
    }

    public ReservationSqsHandler(
            ReservationService reservationService,
            ObjectMapper objectMapper) {

        this.reservationService =
                Objects.requireNonNull(reservationService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public SQSBatchResponse handleRequest(
            SQSEvent event,
            Context context) {

        List<SQSBatchResponse.BatchItemFailure> failures =
                new ArrayList<>();

        if (event == null || event.getRecords() == null) {
            return new SQSBatchResponse(failures);
        }

        List<SQSEvent.SQSMessage> records = event.getRecords();

        for (int i = 0; i < records.size(); i++) {
            SQSEvent.SQSMessage record = records.get(i);

            try {
                ReservationMessage message =
                        objectMapper.readValue(
                                record.getBody(),
                                ReservationMessage.class);

                ReservationResult result =
                        reservationService.reserve(message);

                if (result == null
                        || (!"CONFIRMED".equals(result.status())
                        && !"REJECTED".equals(result.status()))) {
                    throw new IllegalStateException(
                            "Unexpected reservation result");
                }
            } catch (IdempotencyConflictException ex) {
                if (context != null) {
                    context.getLogger().log(
                            "Permanent idempotency conflict for SQS message "
                                    + record.getMessageId()
                                    + ": " + ex.getMessage());
                }
                // Permanent conflict: acknowledge this message; do not retry it.
            } catch (Exception ex) {
                if (context != null) {
                    context.getLogger().log(
                            "Failed to process SQS message "
                                    + record.getMessageId()
                                    + ": " + ex.getMessage());
                }

                // FIFO: report this message and all later,
                // unprocessed messages as failures.
                for (int j = i; j < records.size(); j++) {
                    failures.add(
                            new SQSBatchResponse.BatchItemFailure(
                                    records.get(j).getMessageId()));
                }

                break;
            }
        }

        return new SQSBatchResponse(failures);
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + name);
        }

        return value;
    }
}
