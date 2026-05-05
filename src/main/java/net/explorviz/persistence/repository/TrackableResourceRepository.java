package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.explorviz.persistence.ogm.AnnotationType;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.ogm.ResourceAnnotation;
import net.explorviz.persistence.ogm.ResourceVersion;
import net.explorviz.persistence.ogm.TrackableResource;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@SuppressWarnings("PMD.ExcessiveParameterList")
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

  public <T extends TrackableResource> ResourceAnnotation addAnnotationAndVersion(
      final Session session,
      final Class<T> resourceType,
      final Integer number,
      final String repoName,
      final String tokenId,
      final String externalId,
      final Instant timestamp,
      final AnnotationType annotationType,
      final Contributor contributor,
      final ResourceVersion newVersion) {
    final Optional<T> resourceOpt = findByNumber(session, resourceType, number, repoName, tokenId);
    if (resourceOpt.isEmpty()) {
      throw new IllegalArgumentException("Resource not found for number: " + number);
    }

    final T resource = resourceOpt.get();

    // Get the current (previous) version if it exists
    final ResourceVersion previousVersion = resource.getCurrentVersion();

    // Create the annotation activity
    final ResourceAnnotation annotation = new ResourceAnnotation();
    annotation.setExternalId(externalId);
    annotation.setTimestamp(timestamp);
    annotation.setAnnotationType(annotationType);
    newVersion.setCreatedBy(contributor);
    annotation.setAssociatedContributor(contributor);

    // Link the annotation to the versions
    annotation.setUsedResource(
        previousVersion); // Used the previous version (can be null for "created"))
    annotation.setGeneratedResourceVersion(newVersion); // Generated the new version

    // Link the new version back to the annotation and resource
    newVersion.setGeneratedBy(annotation);
    newVersion.setResource(resource);
    newVersion.setCreatedBy(contributor);
    newVersion.setCreationDate(timestamp);
    if (previousVersion != null) {
      newVersion.setDerivedFrom(previousVersion);
    }

    // Add the version to the resource
    resource.addVersion(newVersion);

    // Save all entities in transaction
    session.save(annotation, 2); // depth 2 to cascade to resourceVersions and contributor
    session.save(resource, 1); // depth 1 sufficient since annotation is already saved

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
