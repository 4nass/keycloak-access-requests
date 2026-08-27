package io.github.keycloakaccessrequests.core.domain;

public final class InvalidRequestStateException extends RuntimeException {

    public InvalidRequestStateException(DecisionStatus currentStatus) {
        super("Request cannot transition from state " + currentStatus + ".");
    }
}
