package com.flashreserve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.LambdaRuntime;
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

        LambdaLogger logger = logger(context);

        for (int i = 0; i < records.size(); i++) {
            SQSEvent.SQSMessage record = records.get(i);
            ReservationMessage message = null;

            try {
                message = objectMapper.readValue(
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

                // CONFIRMED / REJECTED, or REPLAY_CONFIRMED /
                // REPLAY_REJECTED when an earlier outcome was returned.
                String outcome =
                        ReservationService.REPLAY_MESSAGE
                                .equals(result.message())
                                ? "REPLAY_" + result.status()
                                : result.status();

                log(logger, outcome, record, message, null);
            } catch (IdempotencyConflictException ex) {
                log(logger, "IDEMPOTENCY_CONFLICT", record, message, ex);
                // Permanent conflict: acknowledge this message; do not retry it.
            } catch (Exception ex) {
                log(logger, "RETRYABLE_FAILURE", record, message, ex);

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

    private static LambdaLogger logger(Context context) {
        return context != null && context.getLogger() != null
                ? context.getLogger()
                : LambdaRuntime.getLogger();
    }

    /*
     * One single-line, searchable log entry per message, e.g.
     * RESERVATION_OUTCOME outcome=CONFIRMED messageId=... reservationId=...
     * customerId and the raw message body are deliberately not logged.
     */
    private static void log(
            LambdaLogger logger,
            String outcome,
            SQSEvent.SQSMessage record,
            ReservationMessage message,
            Exception error) {

        StringBuilder line = new StringBuilder("RESERVATION_OUTCOME")
                .append(" outcome=").append(outcome)
                .append(" messageId=").append(record.getMessageId());

        if (message != null) {
            line.append(" reservationId=").append(message.reservationId())
                    .append(" eventId=").append(message.eventId())
                    .append(" quantity=").append(message.quantity());
        }

        if (error != null) {
            line.append(" error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }

        logger.log(line.append(System.lineSeparator()).toString());
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
