package net.explorviz.persistence;

import static net.explorviz.persistence.util.TestUtils.assertNodeCounts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import net.explorviz.persistence.ogm.Clazz;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.repository.ClazzRepository;
import net.explorviz.persistence.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
public class ClazzRepositoryTest {
  @Inject ClazzRepository clazzRepository;

  @Inject SessionFactory sessionFactory;

  private Session session;

  @BeforeEach
  void cleanup() {
    session = sessionFactory.openSession();
    session.purgeDatabase();
  }

  @Test
  void testFindLongestClassPathMatchOnDBWithoutClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java", "hash");
    file1.setHasFileData(true);

    session.save(file1);

    Map<String, Object> resultMap =
        clazzRepository
            .findLongestMatchingClassPathByFileRevisionsId(session, classPath, file1.getId())
            .orElse(null);

    assert resultMap != null;

    Clazz clazz = resultMap.get("existingClass") instanceof Clazz cl ? cl : null;
    String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];

    assertNull(clazz);
    assertEquals(classPath.length, remainingPath.length);
    for (int i = 0; i < remainingPath.length; i++) {
      assertEquals(classPath[i], remainingPath[i]);
    }
  }

  @Test
  void testFindLongestClassPathMatchOnDBWithAllClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    Clazz upperClazz = new Clazz(classPath[0]);
    file1.addClass(upperClazz);
    Clazz lastClazz = upperClazz;
    for (int i = 1; i < classPath.length; i++) {
      Clazz newClazz = new Clazz(classPath[i]);
      lastClazz.addInnerClass(newClazz);
      lastClazz = newClazz;
    }

    session.save(file1);

    Map<String, Object> resultMap =
        clazzRepository
            .findLongestMatchingClassPathByFileRevisionsId(session, classPath, file1.getId())
            .orElse(null);

    assert resultMap != null;

    Clazz clazz = resultMap.get("existingClass") instanceof Clazz cl ? cl : null;
    String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];

    assertNotNull(clazz);
    assertEquals(classPath[classPath.length - 1], clazz.getName());
    assertEquals(0, remainingPath.length);
  }

  @Test
  void testFindLongestClassPathMatchOnDBWithSomeClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    file1.addClass(new Clazz(classPath[0]));

    session.save(file1);

    Map<String, Object> resultMap =
        clazzRepository
            .findLongestMatchingClassPathByFileRevisionsId(session, classPath, file1.getId())
            .orElse(null);

    assert resultMap != null;

    Clazz clazz = resultMap.get("existingClass") instanceof Clazz cl ? cl : null;
    String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];

    assertNotNull(clazz);
    assertEquals(classPath[0], clazz.getName());
    assertEquals(classPath.length - 1, remainingPath.length);
    for (int i = 0; i < remainingPath.length; i++) {
      assertEquals(classPath[i + 1], remainingPath[i]);
    }
  }

  @Test
  void testFindLongestClassPathMatchOnDBWithLongerClassPath() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    Clazz upperClazz = new Clazz(classPath[0]);
    file1.addClass(upperClazz);
    Clazz lastClazz = upperClazz;
    for (int i = 1; i < classPath.length; i++) {
      Clazz newClazz = new Clazz(classPath[i]);
      lastClazz.addInnerClass(newClazz);
      lastClazz = newClazz;
    }
    lastClazz.addInnerClass(new Clazz("D"));

    session.save(file1);

    Map<String, Object> resultMap =
        clazzRepository
            .findLongestMatchingClassPathByFileRevisionsId(session, classPath, file1.getId())
            .orElse(null);

    assert resultMap != null;

    Clazz clazz = resultMap.get("existingClass") instanceof Clazz cl ? cl : null;
    String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];

    assertNotNull(clazz);
    assertEquals(classPath[classPath.length - 1], clazz.getName());
    assertEquals(0, remainingPath.length);
  }

  @Test
  void testCreateClazzPathForDBWithoutClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java", "hash");
    file1.setHasFileData(true);

    session.save(file1);

    Clazz clazz =
        clazzRepository.createClazzPathAndReturnLastClazz(session, classPath, file1.getId());

    Map<String, Object> params = new HashMap<>();
    params.put("fileId", file1.getId());
    params.put("classNameOne", classPath[0]);
    params.put("classNameTwo", classPath[1]);
    params.put("classNameThree", classPath[2]);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (file:FileRevision)
            WHERE id(file)=$fileId

            MATCH (file)
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(:Clazz {name: $classNameThree})
            } as exists;
            """,
            params);

    assertNotNull(clazz);
    assertEquals(classPath[classPath.length - 1], clazz.getName());
    assertTrue(databaseIsCorrect);
    assertNodeCounts(session, ExpectedCounts.builder().files(1).classes(3).build());
  }

  @Test
  void testCreateClazzPathForDBWithAllClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    Clazz upperClazz = new Clazz(classPath[0]);
    file1.addClass(upperClazz);
    Clazz lastClazz = upperClazz;
    for (int i = 1; i < classPath.length; i++) {
      Clazz newClazz = new Clazz(classPath[i]);
      lastClazz.addInnerClass(newClazz);
      lastClazz = newClazz;
    }

    session.save(file1);

    Clazz clazz =
        clazzRepository.createClazzPathAndReturnLastClazz(session, classPath, file1.getId());

    Map<String, Object> params = new HashMap<>();
    params.put("fileId", file1.getId());
    params.put("classNameOne", classPath[0]);
    params.put("classNameTwo", classPath[1]);
    params.put("classNameThree", classPath[2]);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (file:FileRevision)
            WHERE id(file)=$fileId

            MATCH (file)
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(:Clazz {name: $classNameThree})
            } as exists;
            """,
            params);

    assertNotNull(clazz);
    assertEquals(classPath[classPath.length - 1], clazz.getName());
    assertTrue(databaseIsCorrect);
    assertNodeCounts(session, ExpectedCounts.builder().files(1).classes(3).build());
  }

  @Test
  void testCreateClazzPathForDBWithSomeClazzNodes() {
    String[] classPath = {"A", "B", "C"};

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    file1.addClass(new Clazz(classPath[0]));

    session.save(file1);

    Clazz clazz =
        clazzRepository.createClazzPathAndReturnLastClazz(session, classPath, file1.getId());

    Map<String, Object> params = new HashMap<>();
    params.put("fileId", file1.getId());
    params.put("classNameOne", classPath[0]);
    params.put("classNameTwo", classPath[1]);
    params.put("classNameThree", classPath[2]);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (file:FileRevision)
            WHERE id(file)=$fileId

            MATCH (file)
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(:Clazz {name: $classNameThree})
            } as exists;
            """,
            params);

    assertNotNull(clazz);
    assertEquals(classPath[classPath.length - 1], clazz.getName());
    assertTrue(databaseIsCorrect);
    assertNodeCounts(session, ExpectedCounts.builder().files(1).classes(3).build());
  }
}
