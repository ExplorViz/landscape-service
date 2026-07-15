package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class N95NormalizerTest {

  @Test
  void testEmptySet() {
    double[] multiset = new double[0];
    N95Normalizer normalizer = new N95Normalizer(multiset);
    double value = normalizer.normalize(1);
    assertEquals(0.0d, value);
  }

  @Test
  void testAllZeroMultiset() { // p95=0, max=0 → always 0
    N95Normalizer n = new N95Normalizer(new double[] {0.0, 0.0, 0.0});
    assertEquals(0.0, n.normalize(0.0));
    assertEquals(0.0, n.normalize(7.0)); // even a positive raw → 0
  }

  @Test
  void testSingleElement() { // n=1 → index ⌈0.95⌉-1 = 0
    N95Normalizer n = new N95Normalizer(new double[] {4.0});
    assertEquals(1.0, n.normalize(4.0)); // p95=max=4
    assertEquals(0.25, n.normalize(1.0), 1e-9);
  }

  @Test
  void testTwoElementMultiset() { // the hand-computed {1,3} case
    N95Normalizer n = new N95Normalizer(new double[] {1.0, 3.0});
    // n=2, ⌈1.9⌉-1 = 1 → p95 = 3
    assertEquals(1.0, n.normalize(3.0));
    assertEquals(1.0 / 3, n.normalize(1.0), 1e-9);
  }

  @Test
  void testTwentyElementsIndexSemantics() { // §10: ⌈0.95·20⌉ = 19 (1-based) → sorted[18]
    double[] v = new double[20];
    for (int i = 0; i < 20; i++) v[i] = i + 1; // 1..20
    N95Normalizer n = new N95Normalizer(v);
    // p95 = sorted[18] = 19, max = 20
    assertEquals(1.0, n.normalize(19.0));
    assertEquals(1.0, n.normalize(20.0)); // 20/19 > 1 → capped
    assertEquals(0.5, n.normalize(9.5), 1e-9);
  }

  @Test
  void testFallbackToMaxWhenP95IsZero() { // sparse positives above the 95th pct
    double[] v = new double[20]; // 19 zeros + one 20.0
    v[19] = 20.0;
    N95Normalizer n = new N95Normalizer(v);
    // p95 = sorted[18] = 0, max = 20 → second branch: raw/max
    assertEquals(1.0, n.normalize(20.0));
    assertEquals(0.5, n.normalize(10.0), 1e-9);
    assertEquals(0.0, n.normalize(0.0));
  }

  @Test
  void testZerosAndDuplicatesAreLoadBearing() { // why it must be a multiset, not a Set
    double[] v = new double[20]; // 18 zeros + {5, 10}
    v[18] = 5.0;
    v[19] = 10.0;
    N95Normalizer n = new N95Normalizer(v);
    // multiset: p95 = sorted[18] = 5, max = 10
    // (a Set {0,5,10} would give n=3 → p95 = 10 — the bug this guards against)
    assertEquals(1.0, n.normalize(5.0));
    assertEquals(1.0, n.normalize(10.0)); // 10/5 → capped
    assertEquals(0.5, n.normalize(2.5), 1e-9);
  }

  @Test
  void testUnsortedInputSortedAndNotMutated() {
    double[] input = {3.0, 1.0, 2.0};
    N95Normalizer n = new N95Normalizer(input);
    // n=3, ⌈2.85⌉-1 = 2 → p95 = 3
    assertEquals(1.0, n.normalize(3.0));
    assertEquals(0.5, n.normalize(1.5), 1e-9);
    assertArrayEquals(new double[] {3.0, 1.0, 2.0}, input); // clone() protects caller
  }
}
