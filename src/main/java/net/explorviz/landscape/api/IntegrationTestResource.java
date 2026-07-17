package net.explorviz.landscape.api;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/**
 * Contains endpoints to be used when integration testing, where it is often necessary to verify
 * that a setup step was successfully executed before running further steps in the test.
 */
@UnlessBuildProfile("prod")
@Path("/test")
public class IntegrationTestResource {

  @Inject SessionFactory sessionFactory;

  @GET
  @Path("/landscape/{landscapeTokenId}")
  public Boolean checkIfLandscapeExists(@RestPath final String landscapeTokenId) {
    final Session session = sessionFactory.openSession();
    return session.queryForObject(
        Boolean.class,
        """
        RETURN EXISTS { MATCH (:Landscape {tokenId: $tokenId})};
        """,
        Map.of("tokenId", landscapeTokenId));
  }
}
