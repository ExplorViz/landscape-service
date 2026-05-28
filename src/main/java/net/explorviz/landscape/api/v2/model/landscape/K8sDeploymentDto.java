package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

public record K8sDeploymentDto(String name, List<K8sPodDto> k8sPods) {}
