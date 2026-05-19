package net.explorviz.persistence.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.neo4j.ogm.session.Session;

/** A proxy for the Neo4j OGM {@link Session} that logs Cypher queries and their execution time. */
public final class LoggingSessionProxy implements InvocationHandler {

  private final Session delegate;
  private final CypherQueryLogger logger;

  private LoggingSessionProxy(final Session delegate, final CypherQueryLogger logger) {
    this.delegate = delegate;
    this.logger = logger;
  }

  /**
   * Wraps a {@link Session} with a logging proxy.
   *
   * @param delegate the original session
   * @param logger the logger to use
   * @return a proxied session
   */
  public static Session wrap(final Session delegate, final CypherQueryLogger logger) {
    return (Session)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {Session.class},
            new LoggingSessionProxy(delegate, logger));
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    final String methodName = method.getName();

    if (isQueryMethod(methodName) && args != null && args.length > 0) {
      return invokeWithLogging(method, args);
    }

    return method.invoke(delegate, args);
  }

  private boolean isQueryMethod(final String methodName) {
    return "query".equals(methodName)
        || "queryForObject".equals(methodName)
        || "execute".equals(methodName);
  }

  private Object invokeWithLogging(final Method method, final Object[] args) throws Throwable {
    final QueryInfo queryInfo = extractQueryInfo(args);

    if (queryInfo.cypher() != null) {
      final long start = System.currentTimeMillis();
      try {
        return method.invoke(delegate, args);
      } finally {
        final long duration = System.currentTimeMillis() - start;
        final String resolvedCypher = replaceParameters(queryInfo.cypher(), queryInfo.parameters());
        logger.logQuery(resolvedCypher, duration);
      }
    }

    return method.invoke(delegate, args);
  }

  private record QueryInfo(String cypher, Map<String, Object> parameters) {}

  @SuppressWarnings("unchecked")
  private QueryInfo extractQueryInfo(final Object[] args) {
    String cypher = null;
    Map<String, Object> parameters = null;

    if (args[0] instanceof String s) {
      cypher = s;
      if (args.length > 1 && args[1] instanceof Map m) {
        parameters = (Map<String, Object>) m;
      }
    } else if (args.length > 1 && args[1] instanceof String s) {
      cypher = s;
      if (args.length > 2 && args[2] instanceof Map m) {
        parameters = (Map<String, Object>) m;
      }
    }
    return new QueryInfo(cypher, parameters);
  }

  @SuppressWarnings("unchecked")
  private String replaceParameters(final String cypher, final Map<String, Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return cypher;
    }
    String result = cypher;

    // Sort keys by length descending to avoid replacing $token in $tokenId
    final List<String> keys = new ArrayList<>(parameters.keySet());
    keys.sort((a, b) -> Integer.compare(b.length(), a.length()));

    for (final String key : keys) {
      final Object value = parameters.get(key);
      result = result.replace("$" + key, formatValue(value));
    }
    return result;
  }

  private String formatValue(final Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String) {
      return "\"" + value + "\"";
    }
    if (value instanceof List<?> list) {
      return "[" + list.stream().map(this::formatValue).collect(Collectors.joining(", ")) + "]";
    }
    if (value instanceof Object[] arr) {
      return formatValue(Arrays.asList(arr));
    }
    return String.valueOf(value);
  }
}
