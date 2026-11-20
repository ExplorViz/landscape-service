package net.explorviz.landscape.ogm;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.neo4j.ogm.annotation.Properties;

/**
 * Registers classes for reflection in native images that Neo4j-OGM instantiates reflectively but
 * that the neo4j-ogm-quarkus extension does not register.
 *
 * <p>{@link Properties.NoopTransformation} is the default key transformation of {@link Properties}
 * fields and is instantiated via its no-arg constructor when the OGM metadata is built.
 *
 * <p>For {@code Session#query} results, OGM converts every list value into an array of the
 * elements' common runtime type via {@code java.lang.reflect.Array#newInstance} (see {@code
 * RestModelMapper.ResultRowBuilder#convertToTargetContainer}). Empty lists become {@code Void[]},
 * nested lists become nested arrays. The array classes of all value types the Bolt driver returns
 * for the data stored by this service are therefore registered here, including one nesting level.
 */
@RegisterForReflection(
    targets = {
      Properties.NoopTransformation.class,
      Void[].class,
      String[].class,
      Long[].class,
      Double[].class,
      Boolean[].class,
      Void[][].class,
      String[][].class,
      Long[][].class,
      Double[][].class,
      Boolean[][].class
    },
    classNames = {
      // Map values returned by the Bolt driver (private JDK class, hence referenced by name)
      "[Ljava.util.Collections$UnmodifiableMap;",
      "[[Ljava.util.Collections$UnmodifiableMap;"
    })
public final class NativeReflectionConfig {

  private NativeReflectionConfig() {}
}
