package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

/**
 * The landscape is the base container representing a particular state of structure data to be
 * visualized. Within a landscape, different nodes containing applications are visualized.
 *
 * @param landscapeToken String identifier of the software landscape
 * @param nodes Represent machines or containers on which applications are run
 * @param k8sNodes Special type of node particularly for Kubernetes deployments, which the v2-API
 *     supported. The landscape-service currently stores no data regarding Kubernetes deployments,
 *     therefore these objects are currently always left empty.
 */
public record LandscapeDto(String landscapeToken, List<NodeDto> nodes, List<K8sNodeDto> k8sNodes) {}
