import type { Entitlement } from "../api/EntitlementsAdminApi";

export type DurationUnit = "SECONDS" | "HOURS" | "DAYS";

const DURATION_UNIT_SECONDS: Record<DurationUnit, number> = { SECONDS: 1, HOURS: 3_600, DAYS: 86_400 };

export const DURATION_PRESETS: Record<Entitlement["riskLevel"], {
    defaultDurationAmount: string;
    defaultDurationUnit: DurationUnit;
    maxDurationAmount: string;
    maxDurationUnit: DurationUnit;
}> = {
    LOW: { defaultDurationAmount: "30", defaultDurationUnit: "DAYS", maxDurationAmount: "90", maxDurationUnit: "DAYS" },
    MEDIUM: { defaultDurationAmount: "7", defaultDurationUnit: "DAYS", maxDurationAmount: "30", maxDurationUnit: "DAYS" },
    HIGH: { defaultDurationAmount: "8", defaultDurationUnit: "HOURS", maxDurationAmount: "24", maxDurationUnit: "HOURS" },
    CRITICAL: { defaultDurationAmount: "1", defaultDurationUnit: "HOURS", maxDurationAmount: "4", maxDurationUnit: "HOURS" }
};

export function durationInput(seconds: number): { amount: string; unit: DurationUnit } {
    if (seconds % DURATION_UNIT_SECONDS.DAYS === 0) {
        return { amount: String(seconds / DURATION_UNIT_SECONDS.DAYS), unit: "DAYS" };
    }
    if (seconds % DURATION_UNIT_SECONDS.HOURS === 0) {
        return { amount: String(seconds / DURATION_UNIT_SECONDS.HOURS), unit: "HOURS" };
    }
    return { amount: String(seconds), unit: "SECONDS" };
}

export function durationSeconds(amount: string, unit: DurationUnit): number | undefined {
    const parsed = Number(amount);
    const seconds = parsed * DURATION_UNIT_SECONDS[unit];
    return Number.isSafeInteger(parsed) && parsed > 0 && Number.isSafeInteger(seconds)
        ? seconds : undefined;
}

export function durationText(seconds: number, t: (key: string) => string): string {
    const input = durationInput(seconds);
    const unitKey = {
        SECONDS: input.amount === "1" ? "accessRequestsAdminDurationUnitSecond" : "accessRequestsAdminDurationUnitSeconds",
        HOURS: input.amount === "1" ? "accessRequestsAdminDurationUnitHour" : "accessRequestsAdminDurationUnitHours",
        DAYS: input.amount === "1" ? "accessRequestsAdminDurationUnitDay" : "accessRequestsAdminDurationUnitDays"
    }[input.unit];
    return `${input.amount} ${t(unitKey)}`;
}
