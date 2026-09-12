package net.explorviz.landscape.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.api.v3.model.landscape.ModelType;
import net.explorviz.landscape.ogm.otel.GenericTelemetryEntity;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.GenericServiceDescriptor;
import net.explorviz.landscape.proto.HttpDescriptor;
import net.explorviz.landscape.proto.RpcDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;

/**
 * Allows constructing flat landscape objects directly from over-the-wire representations of models.
 * This can be used for integration testing to ensure returned landscapes have the expected shape.
 */
public class FlatLandscapeBuilder {

  private String landscapeToken;
  private final List<CityDto> cities = new ArrayList<>();
  private final List<DistrictDto> districts = new ArrayList<>();
  private final List<BuildingDto> buildings = new ArrayList<>();

  public FlatLandscapeDto build() {
    return new FlatLandscapeDto(
        this.landscapeToken,
        this.cities.stream().collect(Collectors.toMap(c -> c.flatBaseModel().id(), c -> c)),
        this.districts.stream().collect(Collectors.toMap(d -> d.flatBaseModel().id(), d -> d)),
        this.buildings.stream().collect(Collectors.toMap(b -> b.flatBaseModel().id(), b -> b)));
  }

  public FlatLandscapeBuilder setLandscapeToken(String landscapeToken) {
    this.landscapeToken = landscapeToken;
    return this;
  }

  /**
   * Adds any models required to represent the passed entity to the landscape. Any model which
   * already exists is not created again. Random IDs are assigned to all models. Where appropriate,
   * the {@link ModelType} is set match the entity type, and the {@link TypeOfAnalysis} is set to
   * {@link TypeOfAnalysis#RUNTIME}. The {@link FlatBaseModel#telemetryKey()} is set to that of the
   * entity descriptor for the appropriate model.
   *
   * @param entity Telemetry entity for which to create landscape models
   */
  public FlatLandscapeBuilder addModelsFromTelemetryEntity(TelemetryEntity entity) {
    switch (entity.getEntityDescriptorCase()) {
      case CODE_DESCRIPTOR -> addCodeEntity(entity);
      case RPC_DESCRIPTOR -> addRpcEntity(entity);
      case HTTP_DESCRIPTOR -> addHttpEntity(entity);
      case GENERIC_SERVICE_DESCRIPTOR -> addGenericEntity(entity);
      default -> throw new UnsupportedOperationException("No handler implemented for entity type");
    }

    return this;
  }

  private void addCodeEntity(TelemetryEntity entity) {
    CodeDescriptor descriptor = entity.getCodeDescriptor();
    String[] filePath = descriptor.getFilePath().split("/");

    LandscapeModelCreator<DistrictDto> createDistrict =
        (name, fqn, cityId, parentDistrictId) ->
            new DistrictDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    null,
                    ModelType.CODE,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                new ArrayList<>(),
                new ArrayList<>());

    LandscapeModelCreator<BuildingDto> createBuilding =
        (name, fqn, cityId, parentDistrictId) ->
            new BuildingDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    descriptor.getFileTelemetryKey(),
                    ModelType.CODE,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                descriptor.getLanguage(),
                Map.of());

    ensureBuildingPath(
        descriptor.getApplicationName(),
        entity.getInstrumentationScope(),
        filePath,
        createDistrict,
        createBuilding);
  }

  private void addRpcEntity(TelemetryEntity entity) {
    RpcDescriptor descriptor = entity.getRpcDescriptor();
    List<String> servicePath = new ArrayList<>();
    servicePath.add(descriptor.getSystemName());
    servicePath.addAll(List.of(descriptor.getServiceName().split("\\.")));

    LandscapeModelCreator<DistrictDto> createDistrict =
        (name, fqn, cityId, parentDistrictId) ->
            new DistrictDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    null,
                    ModelType.RPC,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                new ArrayList<>(),
                new ArrayList<>());

    LandscapeModelCreator<BuildingDto> createBuilding =
        (name, fqn, cityId, parentDistrictId) ->
            new BuildingDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    descriptor.getServiceTelemetryKey(),
                    ModelType.RPC,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                null,
                Map.of());

    ensureBuildingPath(
        descriptor.getApplicationName(),
        entity.getInstrumentationScope(),
        servicePath.toArray(new String[0]),
        createDistrict,
        createBuilding);
  }

  private void addHttpEntity(TelemetryEntity entity) {
    HttpDescriptor descriptor = entity.getHttpDescriptor();

    LandscapeModelCreator<DistrictDto> createDistrict =
        (name, fqn, cityId, parentDistrictId) -> {
          throw new IllegalStateException("HTTP entity should not create districts");
        };

    LandscapeModelCreator<BuildingDto> createBuilding =
        (name, fqn, cityId, parentDistrictId) ->
            new BuildingDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    descriptor.getTelemetryKey(),
                    ModelType.HTTP,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                null,
                Map.of());

    ensureBuildingPath(
        descriptor.getApplicationName(),
        entity.getInstrumentationScope(),
        new String[] {descriptor.getRoute()},
        createDistrict,
        createBuilding);
  }

  private void addGenericEntity(TelemetryEntity entity) {
    GenericServiceDescriptor descriptor = entity.getGenericServiceDescriptor();

    LandscapeModelCreator<DistrictDto> createDistrict =
        (name, fqn, cityId, parentDistrictId) -> {
          throw new IllegalStateException("Generic entity should not create districts");
        };

    LandscapeModelCreator<BuildingDto> createBuilding =
        (name, fqn, cityId, parentDistrictId) ->
            new BuildingDto(
                new FlatBaseModel(
                    UUID.randomUUID().toString(),
                    name,
                    fqn,
                    descriptor.getServiceTelemetryKey(),
                    ModelType.UNKNOWN,
                    TypeOfAnalysis.RUNTIME,
                    null),
                cityId,
                parentDistrictId,
                null,
                Map.of());

    ensureBuildingPath(
        descriptor.getServiceName(),
        entity.getInstrumentationScope(),
        new String[] {GenericTelemetryEntity.DISPLAY_NAME},
        createDistrict,
        createBuilding);
  }

  /**
   * Ensures a path from a city to a building according to the provided building fqn path. Any model
   * that is missing along the path is created by calling one of the supplied creation functions.
   * When a model with the required name already exists for a segment, no new node is created.
   *
   * @param cityName Name of the city to be found or created
   * @param scopeName Name of the instrumentation scope within the city
   * @param buildingPath List of names to follow within the city, where the last element is the
   *     building name and all prior names are district names.
   * @param districtCreator Function that supplies a new district whenever one needs to be created
   * @param buildingCreator Function that supplies a new building whenever one needs to be created
   */
  private void ensureBuildingPath(
      String cityName,
      String scopeName,
      String[] buildingPath,
      LandscapeModelCreator<DistrictDto> districtCreator,
      LandscapeModelCreator<BuildingDto> buildingCreator) {

    Optional<CityDto> existingCityOpt = findCityByName(cityName);

    CityDto city;
    if (existingCityOpt.isPresent()) {
      city = existingCityOpt.get();
    } else {
      city =
          new CityDto(
              new FlatBaseModel(
                  UUID.randomUUID().toString(),
                  cityName,
                  null,
                  null,
                  ModelType.SERVICE,
                  TypeOfAnalysis.RUNTIME,
                  null),
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>());
      this.cities.add(city);
    }

    DistrictDto scopeDistrict =
        findOrCreateDistrict(
            city,
            null,
            scopeName,
            scopeName,
            (name, fqn, cityId, parentDistrictId) ->
                new DistrictDto(
                    new FlatBaseModel(
                        UUID.randomUUID().toString(),
                        name,
                        fqn,
                        null,
                        ModelType.INSTRUMENTATION_SCOPE,
                        TypeOfAnalysis.RUNTIME,
                        null),
                    cityId,
                    parentDistrictId,
                    new ArrayList<>(),
                    new ArrayList<>()));

    List<String> fqn = new ArrayList<>();
    fqn.add(scopeDistrict.flatBaseModel().name());
    DistrictDto parentDistrict = scopeDistrict;

    for (int i = 0; i < buildingPath.length; i++) {
      String pathName = buildingPath[i];
      fqn.add(pathName);

      // Last path segment corresponds to building name
      if (i == buildingPath.length - 1) {
        findOrCreateBuilding(
            city, parentDistrict, pathName, String.join("/", fqn), buildingCreator);
        return;
      }

      parentDistrict =
          findOrCreateDistrict(
              city, parentDistrict, pathName, String.join("/", fqn), districtCreator);
    }
  }

  private DistrictDto findOrCreateDistrict(
      CityDto city,
      DistrictDto parentDistrict,
      String name,
      String fqn,
      LandscapeModelCreator<DistrictDto> creator) {

    List<String> districtsInParent =
        parentDistrict != null ? parentDistrict.districtIds() : city.districtIds();

    Optional<DistrictDto> existingDistrict = findDistrictByName(districtsInParent, name);
    if (existingDistrict.isPresent()) {
      return existingDistrict.get();
    }

    DistrictDto newDistrict =
        creator.create(
            name,
            fqn,
            city.flatBaseModel().id(),
            parentDistrict != null ? parentDistrict.flatBaseModel().id() : null);

    this.districts.add(newDistrict);
    city.allContainedDistrictIds().add(newDistrict.flatBaseModel().id());
    districtsInParent.add(newDistrict.flatBaseModel().id());

    return newDistrict;
  }

  private void findOrCreateBuilding(
      CityDto city,
      DistrictDto parentDistrict,
      String name,
      String fqn,
      LandscapeModelCreator<BuildingDto> creator) {

    List<String> buildingsInParent =
        parentDistrict != null ? parentDistrict.buildingIds() : city.buildingIds();

    Optional<BuildingDto> existingBuilding = findBuildingByName(buildingsInParent, name);

    if (existingBuilding.isPresent()) {
      return;
    }

    BuildingDto newBuilding =
        creator.create(
            name,
            fqn,
            city.flatBaseModel().id(),
            parentDistrict != null ? parentDistrict.flatBaseModel().id() : null);

    this.buildings.add(newBuilding);
    city.allContainedBuildingIds().add(newBuilding.flatBaseModel().id());
    buildingsInParent.add(newBuilding.flatBaseModel().id());
  }

  private Optional<CityDto> findCityByName(String name) {
    return this.cities.stream().filter(c -> c.flatBaseModel().name().equals(name)).findAny();
  }

  private Optional<DistrictDto> findDistrictByName(List<String> existingDistrictIds, String name) {
    return existingDistrictIds.stream()
        .flatMap(
            id ->
                this.districts.stream()
                    .filter(d -> d.flatBaseModel().id().equals(id))
                    .filter(d -> d.flatBaseModel().name().equals(name)))
        .findAny();
  }

  private Optional<BuildingDto> findBuildingByName(List<String> existingBuildingIds, String name) {
    return existingBuildingIds.stream()
        .flatMap(
            id ->
                this.buildings.stream()
                    .filter(b -> b.flatBaseModel().id().equals(id))
                    .filter(b -> b.flatBaseModel().name().equals(name)))
        .findAny();
  }

  @FunctionalInterface
  private interface LandscapeModelCreator<T> {
    T create(String name, String fqn, String cityId, String parentDistrictId);
  }
}
