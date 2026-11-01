/** Matches Keycloak 26.8.1's PageNav route normalization. */
export const normalizeNavRoutePath = (routePath: string): string =>
    routePath.replace(/\/:.+?(\?|(?:(?!\/).)*|$)/g, "").replace(/\/\*$/, "");
