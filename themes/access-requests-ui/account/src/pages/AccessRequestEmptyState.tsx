import { EmptyState, EmptyStateBody, EmptyStateHeader } from "@patternfly/react-core";

type AccessRequestEmptyStateProps = {
    description: string;
    title: string;
};

export function AccessRequestEmptyState({ description, title }: AccessRequestEmptyStateProps) {
    return (
        <EmptyState variant="sm">
            <EmptyStateHeader headingLevel="h2" titleText={title} />
            <EmptyStateBody>{description}</EmptyStateBody>
        </EmptyState>
    );
}
