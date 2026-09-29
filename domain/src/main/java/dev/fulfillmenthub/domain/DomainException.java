package dev.fulfillmenthub.domain;

public final class DomainException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DomainException(String message) {
        super(message);
    }
}
