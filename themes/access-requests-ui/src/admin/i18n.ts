import i18n, { type LanguageDetectorModule } from "i18next";
import FetchBackend from "i18next-fetch-backend";
import { initReactI18next } from "react-i18next";

import { environment } from "./environment";

type KeycloakMessage = {
    key: string;
    value: string;
};

const languageDetector: LanguageDetectorModule = {
    type: "languageDetector",
    detect: () => document.documentElement.lang || "en"
};

export const adminI18n = i18n;

adminI18n.use(FetchBackend).use(languageDetector).use(initReactI18next);

export const adminI18nReady = adminI18n.init({
    fallbackLng: "en",
    interpolation: {
        escapeValue: false
    },
    ns: [environment.realm],
    defaultNS: environment.realm,
    backend: {
        loadPath: `${environment.adminBaseUrl.replace(/\/$/, "")}/resources/{{ns}}/admin/{{lng}}`,
        parse: (data: string) => {
            const messages = JSON.parse(data) as KeycloakMessage[];
            return Object.fromEntries(messages.map(({ key, value }) => [key, value]));
        }
    }
});
