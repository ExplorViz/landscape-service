package net.explorviz.landscape.api.v3.model.landscape;

import java.util.List;
import java.util.Objects;

public record AnimationWindowDto(int totalCount, int windowStart, List<AnimationFrameDto> frames) {

  public AnimationWindowDto {
    Objects.requireNonNull(frames);
  }
}
