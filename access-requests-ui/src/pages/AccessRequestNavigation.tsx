type AccessRequestNavigationProps = {
    canApprove: boolean;
};

export function AccessRequestNavigation({ canApprove }: AccessRequestNavigationProps) {
    return (
        <nav aria-label="Access">
            <ul>
                <li>
                    <a href="request-access">Request access</a>
                </li>
                <li>
                    <a href="my-requests">My Requests</a>
                </li>
                {canApprove && (
                    <li>
                        <a href="approvals">Approvals</a>
                    </li>
                )}
            </ul>
        </nav>
    );
}
