package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

/**
 * In the v2-API, the container for all structure data is the node. It represents a particular
 * machine or container on which applications are running. Since this feature was not used and only
 * kept for compatibility purposes, we send back default values for its attributes.
 *
 * @param ipAddress IP address of the machine / container on which the applications are running
 * @param hostName Hostname of the machine / container on which the applications are running
 * @param applications List of applications running on this node
 */
public record NodeDto(String ipAddress, String hostName, List<ApplicationDto> applications) {}
