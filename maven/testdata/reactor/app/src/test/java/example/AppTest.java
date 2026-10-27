package example;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AppTest {
    @Test
    public void usesReactorDependency() {
        assertEquals("hello", Greeting.message());
        assertEquals("from-config", System.getProperty("fixture.message"));
    }
}
