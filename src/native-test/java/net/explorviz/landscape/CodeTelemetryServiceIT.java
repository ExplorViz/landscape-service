package net.explorviz.landscape;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import net.explorviz.landscape.messaging.telemetry.CodeTelemetryServiceIntegrationTest;

@QuarkusIntegrationTest
class CodeTelemetryServiceIT extends CodeTelemetryServiceIntegrationTest {
  // Execute the same tests but in packaged mode.
}
