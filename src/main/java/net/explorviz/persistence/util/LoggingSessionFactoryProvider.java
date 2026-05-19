package net.explorviz.persistence.util;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.ogm.config.Configuration;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Provides a {@link SessionFactory} that returns logging-enabled sessions in development mode. */
@IfBuildProfile("dev")
@ApplicationScoped
public class LoggingSessionFactoryProvider {

  @ConfigProperty(name = "quarkus.neo4j.uri", defaultValue = "bolt://localhost:7687")
  String neo4jUri;

  @Inject CypherQueryLogger queryLogger;

  @Produces
  @Alternative
  @Priority(1)
  @ApplicationScoped
  public SessionFactory sessionFactory() {
    final Configuration configuration = new Configuration.Builder().uri(neo4jUri).build();

    // We recreate the SessionFactory with the same configuration but override openSession
    // The package "net.explorviz.persistence.ogm" is where all entities are located.
    return new SessionFactory(configuration, "net.explorviz.persistence.ogm") {
      @Override
      public Session openSession() {
        return LoggingSessionProxy.wrap(super.openSession(), queryLogger);
      }
    };
  }
}
