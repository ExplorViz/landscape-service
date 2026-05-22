package net.explorviz.persistence.repository;

import java.util.Map;
import net.explorviz.persistence.api.v3.model.landscape.FlatLandscapeDto;

/** Merges two flat landscapes from different commits into one comparison landscape. */
final class FlatLandscapeMerger {

  FlatLandscapeDto merge(
      final String token, final FlatLandscapeDto first, final FlatLandscapeDto second) {

    final Map<String, String> idMap = LandscapeIdMapBuilder.build(first, second);

    return LandscapeCommitDiffMerger.merge(token, first, second, idMap);
  }
}
