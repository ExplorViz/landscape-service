package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

public record K8sPodDto(String name, List<ApplicationDto> applications) {}
