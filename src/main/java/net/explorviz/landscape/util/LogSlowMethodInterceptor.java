package net.explorviz.landscape.util;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * When a {@link LogSlowMethod} annotation is added to a class, a warning log is produced for every
 * method invocation within that class which exceeds the specified amount of time. Useful to
 * identify slow repository query functions. Can also be added directly to specific methods, in
 * which case the time value for the method takes precedence over any class annotation.
 *
 * <p>Make sure to remove the annotation once finished debugging as it will impact performance.
 */
@LogSlowMethod
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogSlowMethodInterceptor {

  @Inject ScheduledExecutorService scheduler;

  @AroundInvoke
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  public Object logSlowMethod(final InvocationContext context) throws Exception {

    LogSlowMethod annotation = context.getMethod().getAnnotation(LogSlowMethod.class);

    Class<?> targetClass = context.getTarget().getClass();

    // If method is not directly annotated, go up superclass hierarchy until annotation is found.
    while (annotation == null && targetClass != null) {
      annotation = targetClass.getAnnotation(LogSlowMethod.class);
      targetClass = targetClass.getSuperclass();
    }

    if (annotation == null) {
      return context.proceed();
    }

    final long thresholdMillis = annotation.thresholdMillis();

    final String methodName = context.getMethod().getName();

    final ScheduledFuture<?> future =
        scheduler.schedule(
            () ->
                Log.warnf(
                    "Method %s is taking a long time to finish (threshold: %dms)",
                    methodName, thresholdMillis),
            thresholdMillis,
            TimeUnit.MILLISECONDS);

    try {
      return context.proceed();
    } finally {
      future.cancel(false);
    }
  }
}
