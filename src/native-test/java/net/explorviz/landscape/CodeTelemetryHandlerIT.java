package net.explorviz.landscape;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import net.explorviz.landscape.messaging.telemetry.CodeTelemetryHandlerIntegrationTest;

@QuarkusIntegrationTest
class CodeTelemetryHandlerIT extends CodeTelemetryHandlerIntegrationTest {
  // Execute the same tests but in packaged mode.
}
