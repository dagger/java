package example;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class AppIT {
    @Test
    public void runsAfterPackaging() {
        assertTrue(Files.isRegularFile(Path.of("target/app-1.0-SNAPSHOT.jar")));
    }
}
