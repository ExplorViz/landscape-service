package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory map from {@link FileRevisionLookupKey} to Neo4j node id, populated when commit file
 * stubs are linked and consulted before {@link net.explorviz.landscape.grpc.FileDataBatchWriter}
 * hits the database.
 */
@ApplicationScoped
public class FileRevisionIdCache {

  private final Map<String, Long> ids = new ConcurrentHashMap<>();

  public Long get(final String lookupKey) {
    return ids.get(lookupKey);
  }

  public void put(final String lookupKey, final long fileRevId) {
    ids.put(lookupKey, fileRevId);
  }

  public void putAll(final Map<String, Long> entries) {
    ids.putAll(entries);
  }

  public void clear() {
    ids.clear();
  }
}
