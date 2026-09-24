import { AuditEventsPage } from "./AuditEventsPage";
import { AuthorizedAuditPage } from "./AuthorizedAuditPage";

export function AuditEventsRoute() {
    return <AuthorizedAuditPage><AuditEventsPage /></AuthorizedAuditPage>;
}
