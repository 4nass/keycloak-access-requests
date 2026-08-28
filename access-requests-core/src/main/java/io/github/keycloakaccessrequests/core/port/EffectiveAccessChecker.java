package io.github.keycloakaccessrequests.core.port;

import io.github.keycloakaccessrequests.core.domain.Entitlement;

public interface EffectiveAccessChecker {

    boolean hasAccess(String realmId, String userId, Entitlement entitlement);
}
