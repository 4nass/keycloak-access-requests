import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { createElement, Fragment, useState } from "react";
import { afterEach, vi } from "vitest";

vi.mock("@keycloak/keycloak-ui-shared", () => ({
    ContinueCancelModal: ({
        buttonTitle,
        cancelLabel,
        children,
        continueLabel,
        isDisabled,
        modalTitle,
        onContinue
    }: {
        buttonTitle: string;
        cancelLabel: string;
        children: unknown;
        continueLabel: string;
        isDisabled?: boolean;
        modalTitle: string;
        onContinue: () => void;
    }) => {
        const [isOpen, setIsOpen] = useState(false);

        return createElement(
            Fragment,
            undefined,
            createElement("button", { disabled: isDisabled, onClick: () => setIsOpen(true), type: "button" }, buttonTitle),
            isOpen && createElement(
                "div",
                { "aria-label": modalTitle, role: "dialog" },
                children,
                createElement("button", {
                    onClick: () => {
                        setIsOpen(false);
                        onContinue();
                    },
                    type: "button"
                }, continueLabel),
                createElement("button", { onClick: () => setIsOpen(false), type: "button" }, cancelLabel)
            )
        );
    }
}));

afterEach(cleanup);
