package net.explorviz.landscape.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.messaging.service.LandscapeDeletionService;
import net.explorviz.proto.EventType;
import net.explorviz.proto.TokenEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class TokenEventConsumer {

  @Inject LandscapeDeletionService landscapeDeletionService;

  @Blocking
  @Incoming("token-events")
  public void consume(final ConsumerRecord<String, byte[]> record) {
    final TokenEvent event = parseEvent(record.value());
    final String tokenId = resolveTokenId(record, event);

    if (tokenId == null || tokenId.isBlank()) {
      Log.warn("Received token event without token id, skipping");
      return;
    }

    if (event == null || event.getType() == EventType.EVENT_TYPE_DELETED) {
      landscapeDeletionService.deleteLandscapeData(tokenId);
    }
  }

  private static TokenEvent parseEvent(final byte[] payload) {
    if (payload == null || payload.length == 0) {
      return null;
    }

    try {
      return TokenEvent.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      Log.error("Invalid token event payload", e);
      return null;
    }
  }

  private static String resolveTokenId(
      final ConsumerRecord<String, byte[]> record, final TokenEvent event) {
    if (event != null && event.hasToken() && !event.getToken().getId().isBlank()) {
      return event.getToken().getId();
    }

    return record.key();
  }
}
