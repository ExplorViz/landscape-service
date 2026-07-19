package net.explorviz.landscape.messaging.telemetry;

import static io.restassured.RestAssured.given;
import static net.explorviz.landscape.util.FlatLandscapeComparator.assertLandscapeIdsValid;
import static net.explorviz.landscape.util.FlatLandscapeComparator.assertLandscapeStructureMatching;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.restassured.response.Response;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import java.time.Duration;
import java.util.List;
import net.explorviz.landscape.api.v3.StructureResource;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.FlatLandscapeComparator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
public class CodeTelemetryServiceIntegrationTest {

  @InjectKafkaCompanion KafkaCompanion companion;

  @ConfigProperty(name = "mp.messaging.incoming.telemetry-entities.topic")
  String entitiesTopic;

  private static final String DEFAULT_LANDSCAPE_ID = "mytokenvalue";
  private static final String DEFAULT_LANDSCAPE_SECRET = "mytokensecret";

  @TestHTTPEndpoint(StructureResource.class)
  @TestHTTPResource("/runtime")
  private String getStructureRuntimeUrl;

  @BeforeEach
  void setup() {
    given().get("/example/purge").then().statusCode(200);
  }

  @Test
  void testSaveEntity() {
    final String[] filePath = new String[] {"src", "main", "java", "HelloWorld.java"};

    CodeDescriptor codeDescriptor =
        CodeDescriptor.newBuilder()
            .setApplicationName("hello-world")
            .setFileId("796f20776164647570")
            .setFilePath(String.join("/", filePath))
            .setFunctionName("main")
            .setFunctionId("66722065207368206120766F636120646F")
            .setClassName("HelloWorld")
            .setLanguage("java")
            .build();

    TelemetryEntity entity =
        TelemetryEntity.newBuilder()
            .setLandscapeTokenId(DEFAULT_LANDSCAPE_ID)
            .setLandscapeTokenSecret(DEFAULT_LANDSCAPE_SECRET)
            .setCodeDescriptor(codeDescriptor)
            .build();

    companion
        .produce(String.class, byte[].class)
        .fromRecords(
            new ProducerRecord<>(entitiesTopic, DEFAULT_LANDSCAPE_ID, entity.toByteArray()))
        .awaitCompletion();

    FlatLandscapeDto expectedLandscape =
        FlatLandscapeComparator.landscapeFromCodeEntities(
            DEFAULT_LANDSCAPE_ID, List.of(entity.getCodeDescriptor()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Response response =
                  given()
                      .pathParam("landscapeToken", DEFAULT_LANDSCAPE_ID)
                      .get(getStructureRuntimeUrl);

              response.then().statusCode(200);

              FlatLandscapeDto receivedLandscape =
                  new ObjectMapper().readValue(response.asString(), FlatLandscapeDto.class);

              assertLandscapeIdsValid(receivedLandscape);
              assertLandscapeStructureMatching(expectedLandscape, receivedLandscape);
            });
  }
}
