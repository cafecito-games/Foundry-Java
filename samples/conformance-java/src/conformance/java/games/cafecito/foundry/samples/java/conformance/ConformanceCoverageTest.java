package games.cafecito.foundry.samples.java.conformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import games.cafecito.foundry.samples.java.ConformanceCategory;
import games.cafecito.foundry.samples.java.Covers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Proves that the conformance matrix has no unmapped category.
 *
 * <p>Adding a {@link ConformanceCategory} constant without adding a named {@code @Covers} test, or
 * dropping a test that was the only claim on a category, fails here.
 */
public class ConformanceCoverageTest {
    private static final List<Class<?>> MATRIX =
            List.of(
                    ApiSurfaceConformanceTest.class,
                    LifecycleConformanceTest.class,
                    ConcurrencyConformanceTest.class);

    @Test
    public void everyConformanceCategoryIsClaimedByANamedTest() {
        Set<ConformanceCategory> claimed = EnumSet.noneOf(ConformanceCategory.class);
        List<String> unannotatedTests = new ArrayList<>();
        for (Class<?> testClass : MATRIX) {
            for (Method method : testClass.getDeclaredMethods()) {
                if (method.getAnnotation(org.junit.Test.class) == null) {
                    continue;
                }
                Covers covers = method.getAnnotation(Covers.class);
                if (covers == null || covers.value().length == 0) {
                    unannotatedTests.add(testClass.getSimpleName() + "." + method.getName());
                    continue;
                }
                claimed.addAll(List.of(covers.value()));
            }
        }

        Set<ConformanceCategory> unmapped = EnumSet.allOf(ConformanceCategory.class);
        unmapped.removeAll(claimed);
        assertEquals(
                "Conformance categories without a named test: " + names(unmapped),
                Set.of(),
                unmapped);
        assertEquals(
                "Conformance tests without a @Covers claim: " + unannotatedTests,
                List.of(),
                unannotatedTests);
        assertEquals(ConformanceCategory.values().length, claimed.size());
    }

    @Test
    public void theMatrixEnumeratesEveryConformanceTestClass() {
        assertFalse(MATRIX.isEmpty());
        for (Class<?> testClass : MATRIX) {
            assertTrue(
                    testClass.getName() + " must live in the conformance package",
                    testClass.getPackage().getName().endsWith(".conformance"));
            assertTrue(
                    testClass.getSimpleName() + " must declare at least one test",
                    List.of(testClass.getDeclaredMethods()).stream()
                            .anyMatch(method -> method.getAnnotation(org.junit.Test.class) != null));
        }
    }

    private static List<String> names(Set<ConformanceCategory> categories) {
        return new ArrayList<>(new TreeSet<>(categories.stream().map(Enum::name).toList()));
    }
}
