package com.flashreserve.lambda;

public class ReservationProcessingException extends RuntimeException {

    public ReservationProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}