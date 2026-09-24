import { AuthorizedAuditPage } from "./AuthorizedAuditPage";
import { AuditRequestDetailsPage } from "./AuditRequestDetailsPage";

export function AuditRequestDetailsRoute() {
    return <AuthorizedAuditPage><AuditRequestDetailsPage /></AuthorizedAuditPage>;
}
