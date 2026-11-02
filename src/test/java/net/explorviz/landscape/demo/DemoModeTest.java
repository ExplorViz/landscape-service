package net.explorviz.landscape.demo;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import net.explorviz.landscape.api.ExampleDataResource;
import net.explorviz.landscape.api.v3.StructureResource;
import net.explorviz.landscape.grpc.CommitServiceImpl;
import net.explorviz.landscape.grpc.FileDataServiceImpl;
import net.explorviz.landscape.grpc.StateDataServiceImpl;
import net.explorviz.landscape.grpc.TrackableResourceServiceImpl;
import net.explorviz.landscape.messaging.TokenEventConsumer;
import net.explorviz.landscape.messaging.telemetry.TelemetryConsumer;
import org.junit.jupiter.api.Test;

class DemoModeTest {
  private final DemoMode mode = new DemoMode();

  @Test
  void defaultModeAllowsWrites() {
    assertFalse(mode.isEnabled());
    assertNull(filter("PATCH", StructureResource.class, "renameCity"));
  }

  @Test
  void demoBlocksMutationsButAllowsReadsAndBatchQueries() {
    mode.enabled = true;
    for (String method : new String[] {"PATCH", "PUT", "DELETE", "POST"}) {
      Response response = filter(method, StructureResource.class, "renameCity");
      assertEquals(403, response.getStatus());
      assertTrue(response.getEntity().toString().contains("DEMO_MODE"));
    }
    for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
      assertNull(filter(method, StructureResource.class, "getRuntimeStructureData"));
    }
    assertNull(filter("POST", StructureResource.class, "postEvolutionStructureBatch"));
    assertEquals(
        403, filter("GET", ExampleDataResource.class, "createTestingMonorepo").getStatus());
  }

  @Test
  void demoRejectsEveryGrpcIngestionBeforeAccessingDatabase() throws Exception {
    mode.enabled = true;
    for (Object service :
        new Object[] {
          new CommitServiceImpl(),
          new FileDataServiceImpl(),
          new StateDataServiceImpl(),
          new TrackableResourceServiceImpl()
        }) {
      injectMode(service);
      for (Method method : service.getClass().getDeclaredMethods()) {
        if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
            && method.getReturnType().equals(io.smallrye.mutiny.Uni.class)) {
          var result = (io.smallrye.mutiny.Uni<?>) method.invoke(service, new Object[] {null});
          StatusRuntimeException error =
              assertThrows(StatusRuntimeException.class, () -> result.await().indefinitely());
          assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        }
      }
    }
  }

  @Test
  void demoIgnoresKafkaMessagesBeforeAccessingDatabase() throws Exception {
    mode.enabled = true;
    var tokens = new TokenEventConsumer();
    injectMode(tokens);
    tokens.consume(null);
    var telemetry = new TelemetryConsumer();
    injectMode(telemetry);
    telemetry.consume(null);
  }

  private void injectMode(Object target) throws Exception {
    Field field = target.getClass().getDeclaredField("demoMode");
    field.setAccessible(true);
    field.set(target, mode);
  }

  private Response filter(String verb, Class<?> resource, String name) {
    DemoModeFilter filter = new DemoModeFilter();
    filter.demoMode = mode;
    filter.resourceInfo =
        new ResourceInfo() {
          public Class<?> getResourceClass() {
            return resource;
          }

          public Method getResourceMethod() {
            return java.util.Arrays.stream(resource.getMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow();
          }
        };
    AtomicReference<Response> response = new AtomicReference<>();
    ContainerRequestContext request =
        (ContainerRequestContext)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ContainerRequestContext.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getMethod")) return verb;
                  if (method.getName().equals("abortWith")) response.set((Response) args[0]);
                  return null;
                });
    filter.filter(request);
    return response.get();
  }
}
