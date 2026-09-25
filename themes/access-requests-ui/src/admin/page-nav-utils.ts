/** Matches Keycloak 26.7.4's PageNav route normalization. */
export const normalizeNavRoutePath = (routePath: string): string =>
    routePath.replace(/\/:.+?(\?|(?:(?!\/).)*|$)/g, "").replace(/\/\*$/, "");
