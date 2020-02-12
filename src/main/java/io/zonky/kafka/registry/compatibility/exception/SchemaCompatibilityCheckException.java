package io.zonky.kafka.registry.compatibility.exception;

public class SchemaCompatibilityCheckException extends RuntimeException {
    public SchemaCompatibilityCheckException(final Throwable t) {
        super(t);
    }

    public SchemaCompatibilityCheckException(final String message, final Throwable t) {
        super(message, t);
    }
}