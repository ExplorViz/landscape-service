package net.explorviz.persistence.util;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs Cypher queries and their execution time to a file. Only active in development mode. */
@IfBuildProfile("dev")
@ApplicationScoped
public class CypherQueryLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger(CypherQueryLogger.class);
  private static final String LOG_FILE = "cypher_queries.log";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  public void logQuery(final String cypher, final long timeMillis) {
    final Path path = Paths.get(LOG_FILE);
    try (BufferedWriter writer =
        Files.newBufferedWriter(
            path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      final String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
      final String logEntry =
          String.format(
              "[%s] [%dms] %s%n", timestamp, timeMillis, cypher.trim().replaceAll("\\s+", " "));
      writer.write(logEntry);
    } catch (final IOException e) {
      // Fallback to standard logging if file writing fails
      LOGGER.error("Failed to log Cypher query to file: {}", e.getMessage());
    }
  }
}
