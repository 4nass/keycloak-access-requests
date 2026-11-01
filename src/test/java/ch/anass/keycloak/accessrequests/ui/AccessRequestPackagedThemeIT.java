package ch.anass.keycloak.accessrequests.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AccessRequestPackagedThemeIT {

    @Test
    void packagesOnlyTheCurrentAccountAndAdminBuildAssets() throws IOException {
        try (JarFile provider = new JarFile("target/keycloak-access-requests.jar")) {
            for (String console : Set.of("account", "admin")) {
                Path distribution = Path.of("themes/access-requests-ui/dist", console);
                Set<String> expected;
                try (var files = Files.walk(distribution)) {
                    expected = files.filter(Files::isRegularFile)
                            .map(distribution::relativize)
                            .map(path -> path.toString().replace('\\', '/'))
                            .collect(Collectors.toSet());
                }
                assertFalse(expected.isEmpty(), "The " + console + " build must contain assets.");
                String prefix = "theme/access-requests/" + console + "/resources/";
                Set<String> packaged = provider.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                        .map(entry -> entry.getName().substring(prefix.length()))
                        .collect(Collectors.toSet());
                assertEquals(expected, packaged,
                        "The JAR must contain exactly the current " + console + " assets, without stale chunks.");
            }
        }
    }
}
