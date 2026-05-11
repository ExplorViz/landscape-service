package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.ogm.ResourceAnnotation;
import net.explorviz.persistence.ogm.ResourceVersion;
import net.explorviz.persistence.ogm.TrackableResource;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class TrackableResourceRepository {

  @Inject SessionFactory sessionFactory;

  public <T extends TrackableResource> Optional<T> findByNumber(
      final Session session,
      final Class<T> type,
      final Integer number,
      final String repoName,
      final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            type,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(t:%s {number: $number})
            RETURN t;
            """
                .formatted(type.getSimpleName()),
            Map.of("tokenId", tokenId, "repoName", repoName, "number", number)));
  }

  public <T extends TrackableResource> Set<T> findAllByContributor(
      final Session session,
      final Class<T> type,
      final String repoName,
      final String tokenId,
      final Contributor contributor) {

    final String cypher =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(t:%s)-[:HAS_VERSION]->(:ResourceVersion)-[:CREATED_BY]->(c:Contributor {name: $contributorName})
        RETURN DISTINCT t;
        """
            .formatted(type.getSimpleName());

    final Map<String, Object> params =
        Map.of(
            "tokenId", tokenId,
            "repoName", repoName,
            "contributorName", contributor.getName());

    final Iterable<T> results = session.query(type, cypher, params);

    final Set<T> resultSet = new HashSet<>();
    results.forEach(resultSet::add);

    return resultSet;
  }

  public <T extends TrackableResource> ResourceAnnotation addAnnotationEvent(
      final Session session,
      final T trackableResource,
      final ResourceAnnotation annotation,
      final ResourceVersion resourceVersion) {

    // reassure trackableResource is saved
    if (trackableResource.getId() == null) {
      session.save(trackableResource, 0);
    }

    // Ensure creationDate is set on the version (fallback to annotation timestamp)
    if (resourceVersion.getCreationDate() == null) {
      resourceVersion.setCreationDate(annotation.getTimestamp());
    }

    final long timestamp = resourceVersion.getCreationDate().toEpochMilli();

    // find predecessor by date
    final ResourceVersion previousVersion =
        session.queryForObject(
            ResourceVersion.class,
            """
            MATCH (t)-[:HAS_VERSION]->(v:ResourceVersion)
            WHERE id(t) = $resourceId AND v.creationDate < $newDate
            RETURN v ORDER BY v.creationDate DESC LIMIT 1
            """,
            Map.of("resourceId", trackableResource.getId(), "newDate", timestamp));

    // Find successor by date
    final ResourceVersion successorVersion =
        session.queryForObject(
            ResourceVersion.class,
            """
            MATCH (t)-[:HAS_VERSION]->(v:ResourceVersion)
            WHERE id(t) = $resourceId AND v.creationDate > $newDate
            RETURN v ORDER BY v.creationDate ASC LIMIT 1
            """,
            Map.of("resourceId", trackableResource.getId(), "newDate", timestamp));

    // set relationships
    annotation.setUsedResource(previousVersion);
    annotation.setGeneratedResourceVersion(resourceVersion);
    resourceVersion.setGeneratedBy(annotation);
    resourceVersion.setResource(trackableResource);

    // set creator
    if (annotation.getAssociatedContributor() != null) {
      resourceVersion.setCreatedBy(annotation.getAssociatedContributor());
    }

    // set derivedFrom relation
    if (previousVersion != null) {
      resourceVersion.setDerivedFrom(previousVersion);
    }

    // add update successor relation in case of out of order arrival
    if (successorVersion != null) {
      successorVersion.setDerivedFrom(resourceVersion);
      // Update the successor's annotation to use the new version
      final ResourceAnnotation successorAnnotation =
          session.queryForObject(
              ResourceAnnotation.class,
              "MATCH (a:ResourceAnnotation)-[:GENERATES]->(v:ResourceVersion) WHERE id(v) = $succId"
                  + " RETURN a",
              Map.of("succId", successorVersion.getId()));

      if (successorAnnotation != null) {
        successorAnnotation.setUsedResource(resourceVersion);
        session.save(successorAnnotation, 1);
      }
      session.save(successorVersion, 1);
    } else {

      // if no successor, update trackableResource with new data
      if (resourceVersion.getState() != null) {
        trackableResource.setState(resourceVersion.getState().name());
      }
      trackableResource.setTitle(resourceVersion.getTitle());
      if (resourceVersion.getLabels() != null) {
        trackableResource.setLabels(new HashSet<>(resourceVersion.getLabels()));
      }
      session.save(trackableResource, 0);
    }

    // save and update relationships
    session.save(annotation, 2);
    trackableResource.addVersion(resourceVersion);
    resourceVersion.setResource(trackableResource);
    session.save(trackableResource, 1);

    return annotation;
  }

  public <T extends TrackableResource> T getOrCreate(
      final Session session,
      final Class<T> type,
      final Integer number,
      final String repoName,
      final String tokenId) {
    return findByNumber(session, type, number, repoName, tokenId)
        .orElseGet(
            () -> {
              try {
                final T resource = type.getDeclaredConstructor().newInstance();
                resource.setNumber(number);
                return resource;
              } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(
                    "Could not instantiate TrackableResource of type: " + type.getSimpleName(), e);
              }
            });
  }
}
