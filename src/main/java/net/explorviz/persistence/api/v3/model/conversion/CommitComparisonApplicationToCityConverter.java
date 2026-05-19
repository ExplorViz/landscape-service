package net.explorviz.persistence.api.v3.model.conversion;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiFunction;
import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.TypeOfAnalysis;
import net.explorviz.persistence.api.v3.model.conversion.DefaultApplicationToCityConverter.DirectoryWrapper;
import net.explorviz.persistence.api.v3.model.conversion.DefaultApplicationToCityConverter.FileRevisionWrapper;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto.BuildingConvertible;
import net.explorviz.persistence.api.v3.model.landscape.CityDto.CityConvertible;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto.DistrictConvertible;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.FileRevision;

/**
 * Provides wrapper classes for turning two versions of the same OGM Application object into a
 * combined FlatLandscape city, where each version is hydrated for a different commit.
 */
public final class CommitComparisonApplicationToCityConverter {

  private CommitComparisonApplicationToCityConverter() {}

  /**
   * Creates a {@link CityConvertible} representing the union of two versions of the same
   * application. Uses the following mapping:
   *
   * <ul>
   *   <li>OGM Application -> City
   *   <li>OGM Directory -> District
   *   <li>OGM FileRevision -> Building
   * </ul>
   *
   * <p>The value for {@link CommitComparison} is set depending on whether a component is part of
   * just the first application, just the second, or both, where the value is specified relative to
   * the second application (e.g. "DELETED" means present in the first application, but not in the
   * second).
   *
   * <p>The origin of data is assumed to be from static analysis.
   *
   * @param firstApp Application against which to compare the second application. Should be hydrated
   *     to the desired depth
   * @param secondApp Application that is considered the "current" state, relative to which all
   *     {@link CommitComparison} values are given. Should be hydrated to the desired depth
   * @return A {@link CityConvertible} according to the above mapping, where the contents represent
   *     the union of the provided applications
   * @throws IllegalArgumentException if the provided applications have a different ID or name.
   */
  public static CityConvertible convert(final Application firstApp, final Application secondApp) {
    if (!Objects.equals(firstApp.getId(), secondApp.getId())
        || !firstApp.getName().equals(secondApp.getName())) {
      throw new IllegalArgumentException("Provided applications must have same ID and name.");
    }
    return new ComparisonApplicationWrapper(firstApp, secondApp);
  }

  record ComparisonApplicationWrapper(Application firstApp, Application secondApp)
      implements CityConvertible {

    @Override
    public String getId() {
      return firstApp.getId().toString();
    }

    @Override
    public String getName() {
      return firstApp.getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return TypeOfAnalysis.STATIC;
    }

    @Override
    public Collection<DistrictConvertible> getDistricts() {
      return compareSortedSets(
          firstApp.getRootDirectory().getSubdirectories(),
          secondApp.getRootDirectory().getSubdirectories(),
          ComparisonDirectoryWrapper::new,
          d1 -> new DirectoryWrapper(d1, TypeOfAnalysis.STATIC, CommitComparison.REMOVED),
          d2 -> new DirectoryWrapper(d2, TypeOfAnalysis.STATIC, CommitComparison.ADDED));
    }

    @Override
    public Collection<BuildingConvertible> getBuildings() {
      return compareSortedSets(
          firstApp.getRootDirectory().getFileRevisions(),
          secondApp.getRootDirectory().getFileRevisions(),
          ComparisonFileRevisionWrapper::new,
          f1 -> new FileRevisionWrapper(f1, TypeOfAnalysis.STATIC, CommitComparison.REMOVED),
          f2 -> new FileRevisionWrapper(f2, TypeOfAnalysis.STATIC, CommitComparison.ADDED));
    }
  }

  record ComparisonDirectoryWrapper(Directory firstDir, Directory secondDir)
      implements DistrictConvertible {

    @Override
    public String getId() {
      return firstDir.getId().toString();
    }

    @Override
    public String getName() {
      return firstDir.getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return TypeOfAnalysis.STATIC;
    }

    @Override
    public CommitComparison getCommitComparison() {
      return Objects.equals(firstDir.getId(), secondDir.getId())
          ? CommitComparison.UNCHANGED
          : CommitComparison.MODIFIED;
    }

    @Override
    public Collection<DistrictConvertible> getDistricts() {
      return compareSortedSets(
          firstDir.getSubdirectories(),
          secondDir.getSubdirectories(),
          ComparisonDirectoryWrapper::new,
          d1 -> new DirectoryWrapper(d1, TypeOfAnalysis.STATIC, CommitComparison.REMOVED),
          d2 -> new DirectoryWrapper(d2, TypeOfAnalysis.STATIC, CommitComparison.ADDED));
    }

    @Override
    public Collection<BuildingConvertible> getBuildings() {
      return compareSortedSets(
          firstDir.getFileRevisions(),
          secondDir.getFileRevisions(),
          ComparisonFileRevisionWrapper::new,
          f1 -> new FileRevisionWrapper(f1, TypeOfAnalysis.STATIC, CommitComparison.REMOVED),
          f2 -> new FileRevisionWrapper(f2, TypeOfAnalysis.STATIC, CommitComparison.ADDED));
    }
  }

  record ComparisonFileRevisionWrapper(FileRevision firstFile, FileRevision secondFile)
      implements BuildingConvertible {

    @Override
    public String getId() {
      return secondFile.getId().toString();
    }

    @Override
    public String getName() {
      return secondFile().getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return TypeOfAnalysis.STATIC;
    }

    @Override
    public CommitComparison getCommitComparison() {
      return Objects.equals(firstFile.getId(), secondFile.getId())
          ? CommitComparison.UNCHANGED
          : CommitComparison.MODIFIED;
    }

    @Override
    public String getLanguage() {
      return secondFile.getLanguage();
    }

    @Override
    public Map<String, MetricValue> getMetrics() {
      return MetricValue.fromMaps(secondFile.getMetrics(), firstFile.getMetrics());
    }
  }

  /**
   * Iterates two {@link SortedSet}s and executes the provided transformation callbacks on the
   * elements depending on whether the given element is present in both sets, only in the first set,
   * or only in the second set. The equality is checked based on the set's {@link Comparable}
   * implementation, therefore the elements need not necessarily be identical to be considered
   * "present" in both sets.
   *
   * @param <T> The type of elements in the sets, must be {@link Comparable}
   * @param <R> The output type of the transformation functions
   * @param firstSet First set to iterate
   * @param secondSet Second set to iterate
   * @param onPresentInBoth Transformation function to use if element is present in both sets
   * @param onOnlyInFirst Transformation function to use if element is only present in first set
   * @param onOnlyInSecond Transformation function to use if element is only present in second set
   * @return An unordered set containing the results of the transformation functions
   */
  private static <T extends Comparable<T>, R> Set<R> compareSortedSets(
      final SortedSet<T> firstSet,
      final SortedSet<T> secondSet,
      final BiFunction<T, T, R> onPresentInBoth,
      final java.util.function.Function<T, R> onOnlyInFirst,
      final java.util.function.Function<T, R> onOnlyInSecond) {

    final Set<R> results = new HashSet<>();

    final Iterator<T> firstIt = firstSet.iterator();
    final Iterator<T> secondIt = secondSet.iterator();
    Optional<T> first = next(firstIt);
    Optional<T> second = next(secondIt);

    while (first.isPresent() || second.isPresent()) {
      if (first.isPresent() && second.isPresent() && first.get().compareTo(second.get()) == 0) {
        results.add(onPresentInBoth.apply(first.get(), second.get()));
        first = next(firstIt);
        second = next(secondIt);
      } else if (second.isEmpty() || first.isPresent() && first.get().compareTo(second.get()) < 0) {
        results.add(onOnlyInFirst.apply(first.get()));
        first = next(firstIt);
      } else {
        results.add(onOnlyInSecond.apply(second.get()));
        second = next(secondIt);
      }
    }
    return results;
  }

  private static <T> Optional<T> next(final Iterator<T> it) {
    return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
  }
}
