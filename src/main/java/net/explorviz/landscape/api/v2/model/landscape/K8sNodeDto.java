package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

public record K8sNodeDto(String name, List<K8sNamespaceDto> k8sNamespaces) {}
