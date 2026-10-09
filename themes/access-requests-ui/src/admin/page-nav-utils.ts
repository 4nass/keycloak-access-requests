/** Matches Keycloak 26.8.0's PageNav route normalization. */
export const normalizeNavRoutePath = (routePath: string): string =>
    routePath.replace(/\/:.+?(\?|(?:(?!\/).)*|$)/g, "").replace(/\/\*$/, "");
