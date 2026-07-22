package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import net.explorviz.landscape.messaging.SpanConsumer;
import net.explorviz.landscape.messaging.TokenEventConsumer;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.ParsedSpan;
import net.explorviz.proto.EventType;
import net.explorviz.proto.LandscapeToken;
import net.explorviz.proto.TokenEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class TokenEventConsumerTest {

  private static final String LANDSCAPE_TOKEN = "token-to-delete";

  @Inject TokenEventConsumer tokenEventConsumer;

  @Inject SpanConsumer spanConsumer;

  @Inject SessionFactory sessionFactory;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionFactory.openSession();
    resetDatabase(session);
    persistRuntimeSpan();
  }

  @Test
  void consumeDeletedEventRemovesLandscapeData() {
    assertTrue(landscapeExists());

    final TokenEvent deletedEvent =
        TokenEvent.newBuilder()
            .setType(EventType.EVENT_TYPE_DELETED)
            .setToken(
                LandscapeToken.newBuilder()
                    .setId(LANDSCAPE_TOKEN)
                    .setOwnerId("user")
                    .setSecret("secret")
                    .setCreated(0L)
                    .setAlias("alias")
                    .build())
            .build();

    tokenEventConsumer.consume(
        new ConsumerRecord<>("tokens.events", 0, 0L, LANDSCAPE_TOKEN, deletedEvent.toByteArray()));

    assertFalse(landscapeExists());
  }

  @Test
  void consumeTombstoneRecordRemovesLandscapeData() {
    assertTrue(landscapeExists());

    tokenEventConsumer.consume(new ConsumerRecord<>("tokens.events", 0, 0L, LANDSCAPE_TOKEN, null));

    assertFalse(landscapeExists());
  }

  @Test
  void consumeCreatedEventDoesNotDeleteLandscapeData() {
    final TokenEvent createdEvent =
        TokenEvent.newBuilder()
            .setType(EventType.EVENT_TYPE_CREATED)
            .setToken(
                LandscapeToken.newBuilder()
                    .setId(LANDSCAPE_TOKEN)
                    .setOwnerId("user")
                    .setSecret("secret")
                    .setCreated(0L)
                    .setAlias("alias")
                    .build())
            .build();

    tokenEventConsumer.consume(
        new ConsumerRecord<>("tokens.events", 0, 0L, LANDSCAPE_TOKEN, createdEvent.toByteArray()));

    assertTrue(landscapeExists());
  }

  private void persistRuntimeSpan() {
    final ParsedSpan span =
        ParsedSpan.newBuilder()
            .setSpanId("span1")
            .setTraceId("trace1")
            .setApplicationName("myApp")
            .setLandscapeTokenId(LANDSCAPE_TOKEN)
            .setCodeDescriptor(
                CodeDescriptor.newBuilder()
                    .setFilePath("net/explorviz/myApp/MyClass.java")
                    .setFunctionName("myMethod"))
            .setStartTime(1)
            .setEndTime(5)
            .build();

    spanConsumer.consume(span.toByteArray());
  }

  private boolean landscapeExists() {
    return Boolean.TRUE.equals(
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Landscape {tokenId: $tokenId})
            } AS exists
            """,
            Map.of("tokenId", LANDSCAPE_TOKEN)));
  }
}
