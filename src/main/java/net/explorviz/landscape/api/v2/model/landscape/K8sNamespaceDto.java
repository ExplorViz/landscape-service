package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

public record K8sNamespaceDto(String name, List<K8sDeploymentDto> k8sDeployments) {}
