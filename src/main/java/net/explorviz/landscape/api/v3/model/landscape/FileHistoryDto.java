package net.explorviz.landscape.api.v3.model.landscape;

public record FileHistoryDto(String commitHash, long date, String action) {}
