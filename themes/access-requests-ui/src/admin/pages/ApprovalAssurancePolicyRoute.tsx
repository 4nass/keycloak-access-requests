import {
    ActionGroup, Alert, Button, Card, CardBody, CardTitle, Form, FormGroup, Grid, GridItem,
    PageSection, Spinner, Text, TextContent, TextInput, Title
} from "@patternfly/react-core";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";

import { presentEntitlementsAdminError, type ApprovalAssurancePolicy } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";

export function ApprovalAssurancePolicyRoute() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [policy, setPolicy] = useState<ApprovalAssurancePolicy>();
    const [error, setError] = useState<unknown>();
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);

    useEffect(() => {
        let active = true;
        void api.approvalAssurancePolicy().then((loaded) => {
            if (active) setPolicy(loaded);
        }).catch((failure: unknown) => {
            if (active) setError(failure);
        });
        return () => { active = false; };
    }, [api]);

    const update = (risk: "high" | "critical", field: "acr" | "loa" | "maxAgeSeconds", value: string) => {
        if (!policy) return;
        setSaved(false);
        setPolicy({
            ...policy,
            [risk]: { ...policy[risk], [field]: field === "acr" ? value : Number(value) }
        });
    };

    const save = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!policy || saving) return;
        setSaving(true);
        setError(undefined);
        setSaved(false);
        try {
            setPolicy(await api.updateApprovalAssurancePolicy(policy));
            setSaved(true);
        } catch (failure) {
            setError(failure);
        } finally {
            setSaving(false);
        }
    };

    return <>
        <PageSection variant="light">
            <Title headingLevel="h1">{t("accessRequestsAdminAssurancePolicy")}</Title>
            <TextContent><Text component="p">{t("accessRequestsAdminAssuranceDescription")}</Text></TextContent>
        </PageSection>
        <PageSection>
            <AccessRequestsAdminTabs active="assurance" />
            <Alert isInline variant="warning" className="pf-v5-u-mb-lg"
                title={t("accessRequestsAdminAssuranceMfaWarning")}>
                <p>{t("accessRequestsAdminAssuranceMaxAgeWarning")}</p>
            </Alert>
            {error !== undefined && <Alert isInline variant="danger" className="pf-v5-u-mb-lg"
                title={t(presentEntitlementsAdminError(error).messageKey)} />}
            {saved && <Alert isInline variant="success" className="pf-v5-u-mb-lg"
                title={t("accessRequestsAdminAssuranceSaved")} />}
            {!policy && !error && <Spinner />}
            {policy && <Form onSubmit={(event) => { void save(event); }}>
                <Grid hasGutter>
                    {(["high", "critical"] as const).map((risk) => {
                        const headingId = `${risk}-assurance-heading`;
                        return <GridItem key={risk} md={6}>
                            <section aria-labelledby={headingId}>
                                <Card isFlat>
                                    <CardTitle>
                                        <Title headingLevel="h2" id={headingId} size="lg">{t(risk === "high"
                                            ? "accessRequestsAdminRiskLevelHigh" : "accessRequestsAdminRiskLevelCritical")}</Title>
                                    </CardTitle>
                                    <CardBody>
                                        <Grid hasGutter>
                                            <GridItem span={12}>
                                                <FormGroup fieldId={`${risk}-acr`} label={t("accessRequestsAdminAssuranceAcr")} isRequired>
                                                    <TextInput id={`${risk}-acr`} value={policy[risk].acr} isRequired
                                                        onChange={(_, value) => update(risk, "acr", value)} />
                                                </FormGroup>
                                            </GridItem>
                                            <GridItem span={12}>
                                                <FormGroup fieldId={`${risk}-loa`} label={t("accessRequestsAdminAssuranceLoa")} isRequired>
                                                    <TextInput id={`${risk}-loa`} type="number" min={2} max={10}
                                                        value={policy[risk].loa} isRequired
                                                        onChange={(_, value) => update(risk, "loa", value)} />
                                                </FormGroup>
                                            </GridItem>
                                            <GridItem span={12}>
                                                <FormGroup fieldId={`${risk}-age`} label={t("accessRequestsAdminAssuranceAgeSeconds")} isRequired>
                                                    <TextInput id={`${risk}-age`} type="number" min={1} max={risk === "high" ? 3600 : 300}
                                                        value={policy[risk].maxAgeSeconds} isRequired
                                                        onChange={(_, value) => update(risk, "maxAgeSeconds", value)} />
                                                </FormGroup>
                                            </GridItem>
                                        </Grid>
                                    </CardBody>
                                </Card>
                            </section>
                        </GridItem>;
                    })}
                </Grid>
                <ActionGroup><Button type="submit" isDisabled={saving}>{t("save")}</Button></ActionGroup>
            </Form>}
        </PageSection>
    </>;
}

export default ApprovalAssurancePolicyRoute;
