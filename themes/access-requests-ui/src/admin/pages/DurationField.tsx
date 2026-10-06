import {
    FormGroup, FormSelect, FormSelectOption, InputGroup, InputGroupItem, TextInput
} from "@patternfly/react-core";
import { useTranslation } from "react-i18next";

import type { DurationUnit } from "./catalogDuration";

export function DurationField({ id, label, amount, unit, disabled, onAmount, onUnit }: {
    id: string;
    label: string;
    amount: string;
    unit: DurationUnit;
    disabled: boolean;
    onAmount: (value: string) => void;
    onUnit: (value: DurationUnit) => void;
}) {
    const { t } = useTranslation();
    return <FormGroup fieldId={id} isRequired label={label}>
        <InputGroup style={{ maxWidth: "24rem" }}>
            <InputGroupItem isFill>
                <TextInput id={id} isDisabled={disabled} isRequired min={1} step={1} type="number"
                    onChange={(_event, value) => onAmount(value)} value={amount} />
            </InputGroupItem>
            <InputGroupItem>
                <FormSelect id={`${id}-unit`} aria-label={`${label} ${t("accessRequestsAdminDurationUnit")}`}
                    isDisabled={disabled} style={{ width: "10rem" }} value={unit}
                    onChange={(_event, value) => onUnit(value as DurationUnit)}>
                    <FormSelectOption label={t("accessRequestsAdminDurationUnitSeconds")} value="SECONDS" />
                    <FormSelectOption label={t("accessRequestsAdminDurationUnitHours")} value="HOURS" />
                    <FormSelectOption label={t("accessRequestsAdminDurationUnitDays")} value="DAYS" />
                </FormSelect>
            </InputGroupItem>
        </InputGroup>
    </FormGroup>;
}
