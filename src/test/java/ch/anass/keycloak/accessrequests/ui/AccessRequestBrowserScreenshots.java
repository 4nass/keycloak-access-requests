package ch.anass.keycloak.accessrequests.ui;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AccessRequestBrowserScreenshots {

    private AccessRequestBrowserScreenshots() {
    }

    static boolean enabled() {
        String directory = System.getProperty("access.requests.screenshot.dir");
        return directory != null && !directory.isBlank();
    }

    static void capture(WebDriver driver, String name) throws IOException {
        String directory = System.getProperty("access.requests.screenshot.dir");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path targetDirectory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(targetDirectory);
        Files.write(targetDirectory.resolve(name + ".png"),
                ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }
}
