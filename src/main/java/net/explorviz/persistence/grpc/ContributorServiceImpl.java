package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.proto.ContributorData;
import net.explorviz.persistence.proto.ContributorService;
import net.explorviz.persistence.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class ContributorServiceImpl implements ContributorService {

  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> persistContributor(final ContributorData request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      // saveContributorData(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GGrpc
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  public void saveContributorData(final Session session, final ContributorData contributorData) {
    final Contributor contributor =
        new Contributor(
            contributorData.getGitUsername(),
            contributorData.getEmail(),
            contributorData.getGithubLogin(),
            contributorData.getAvatarUrl());
    session.save(contributor);
  }

  public void createCommitAuthorAssociation(final Session session, final Contributor contributor) {
    // This method would contain logic to associate the contributor with a commit
    // The actual implementation would depend on how commits are represented in the database and how
    // they relate to contributors.
  }
}
