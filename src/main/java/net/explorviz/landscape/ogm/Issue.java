package net.explorviz.landscape.ogm;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.neo4j.ogm.annotation.NodeEntity;

@NodeEntity
@RegisterForReflection
public class Issue extends TrackableResource {}
