package net.explorviz.landscape.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Configuration for batched {@link net.explorviz.landscape.proto.FileData} persistence. */
@ApplicationScoped
public class FileDataInsertProperties {

  @ConfigProperty(name = "explorviz.landscape.file-data-insert-chunk-size", defaultValue = "50")
  int chunkSize;

  public int getChunkSize() {
    return chunkSize;
  }
}
