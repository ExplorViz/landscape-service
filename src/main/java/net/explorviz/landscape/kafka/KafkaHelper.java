package net.explorviz.landscape.kafka;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.util.Map;
import java.util.Properties;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.streams.StreamsConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;


/**
 * Helper class that contains multiple Kafka configs.
 */
@ApplicationScoped
public class KafkaHelper {

  // Broker host
  private final String bootstrapServer;

  // Application ID
  private final String applicationId;

  // Topic to write/read records from/to
  private final String topicSpanStructure;



  private final SchemaRegistryClient registry;
  private final String schemaRegistryUrl;

  @Inject
  public KafkaHelper(
      @ConfigProperty(
          name = "quarkus.kafka-streams.bootstrap-servers") final String bootstrapServer,
      @ConfigProperty(name = "quarkus.kafka-streams.application-id") final String applicationId,
      @ConfigProperty(name = "quarkus.kafka-streams.topics") final String topicSpanStructure,
      @ConfigProperty(name = "explorviz.schema-registry.url") final String schemaRegistryUrl,
      final SchemaRegistryClient schemaRegistryClient) {
    this.bootstrapServer = bootstrapServer;
    this.applicationId = applicationId;
    this.topicSpanStructure = topicSpanStructure;
    this.schemaRegistryUrl = schemaRegistryUrl;
    this.registry = schemaRegistryClient;

  }

  public String getBootstrapServer() {
    return this.bootstrapServer;
  }

  public String getApplicationId() {
    return this.applicationId;
  }


  public String getTopicSpanStructure() {
    return this.topicSpanStructure;
  }

  /**
   * Returns a new instance of the default configuration for all streams to be used.
   */
  public Properties newDefaultStreamProperties() {
    final Properties streamsConfig = new Properties();
    streamsConfig.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, this.getBootstrapServer());
    streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, this.getApplicationId());
    return streamsConfig;
  }

  /**
   * Returns a SerDe for specific avro records to be used with a schema registry.
   *
   * @param <T> the type of the avro records
   * @return the SerDe
   */
  public <T extends SpecificRecord> SpecificAvroSerde<T> getAvroValueSerde() {
    final SpecificAvroSerde<T> valueSerde = new SpecificAvroSerde<>(this.registry);
    valueSerde.configure(
        Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, this.schemaRegistryUrl),
        false);
    return valueSerde;
  }

}
