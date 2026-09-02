import { createInstance, type LanguageDetectorModule } from "i18next";
import FetchBackend from "i18next-fetch-backend";
import { initReactI18next } from "react-i18next";

import { environment } from "./environment";

type KeyValue = {
    key: string;
    value: string;
};

const keycloakLanguageDetector: LanguageDetectorModule = {
    type: "languageDetector",
    detect: () => environment.locale
};

export const i18n = createInstance({
    fallbackLng: "en",
    interpolation: {
        escapeValue: false
    },
    backend: {
        loadPath: `${environment.serverBaseUrl}/resources/${environment.realm}/account/{{lng}}`,
        parse: (data: string) => {
            const messages: KeyValue[] = JSON.parse(data);
            return Object.fromEntries(messages.map(({ key, value }) => [key, value]));
        }
    }
});

i18n.use(FetchBackend).use(keycloakLanguageDetector).use(initReactI18next);
