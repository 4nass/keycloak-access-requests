export function adminActorLabel(
    actorId: string,
    actorName: string | null | undefined,
    translate: (key: string) => string
): string {
    if (actorId === "access-requests-expiration") {
        return translate("accessRequestsAdminSystemActor");
    }
    return actorName ?? translate("accessRequestsAdminUserUnavailable");
}
