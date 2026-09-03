import { type KeyboardEvent, type ReactNode, type RefObject, useEffect, useRef } from "react";

type AccessibleDialogProps = {
    ariaLabel: string;
    children: ReactNode;
    initialFocusRef?: RefObject<HTMLElement | null>;
    onClose: () => void;
};

const focusableSelector = [
    "a[href]",
    "button:not([disabled])",
    "input:not([disabled])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    "[tabindex]:not([tabindex='-1'])"
].join(",");

function focusableElements(container: HTMLElement) {
    return Array.from(container.querySelectorAll<HTMLElement>(focusableSelector)).filter(
        (element) => !element.hasAttribute("hidden") && element.getAttribute("aria-hidden") !== "true"
    );
}

export function AccessibleDialog({ ariaLabel, children, initialFocusRef, onClose }: AccessibleDialogProps) {
    const dialogRef = useRef<HTMLDivElement>(null);
    const returnFocusRef = useRef<HTMLElement>();

    useEffect(() => {
        const activeElement = document.activeElement;
        returnFocusRef.current = activeElement instanceof HTMLElement ? activeElement : undefined;

        const dialog = dialogRef.current;
        if (!dialog) {
            return;
        }

        const initialFocus = initialFocusRef?.current ?? focusableElements(dialog)[0] ?? dialog;
        initialFocus.focus();

        return () => returnFocusRef.current?.focus();
    }, [initialFocusRef]);

    const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key === "Escape") {
            event.preventDefault();
            onClose();
            return;
        }

        if (event.key !== "Tab" || !dialogRef.current) {
            return;
        }

        const elements = focusableElements(dialogRef.current);
        if (elements.length === 0) {
            event.preventDefault();
            dialogRef.current.focus();
            return;
        }

        const first = elements[0];
        const last = elements.at(-1)!;
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    };

    return (
        <div aria-label={ariaLabel} aria-modal="true" onKeyDown={handleKeyDown} ref={dialogRef} role="dialog" tabIndex={-1}>
            {children}
        </div>
    );
}
