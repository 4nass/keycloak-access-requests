package io.github.keycloakaccessrequests.core.domain;

public final class UnauthorizedRequestActionException extends RuntimeException {

    public UnauthorizedRequestActionException(String message) {
        super(message);
    }
}
