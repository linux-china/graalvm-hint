package io.goodforgod.graalvm.hint.processor;

import io.goodforgod.graalvm.hint.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Processes {@link NativeImageHint} and {@link InitializationHint} annotations for
 * native-image.properties file
 *
 * @author Anton Kurako (GoodforGod)
 * @see NativeImageHint
 * @see InitializationHint
 * @since 30.09.2021
 */
@SupportedAnnotationTypes({
        "io.goodforgod.graalvm.hint.annotation.NativeImageHint",
        "io.goodforgod.graalvm.hint.annotation.InitializationHint",
        "io.goodforgod.graalvm.hint.annotation.InitializationHints"
})
@SupportedOptions({
        HintOptions.HINT_PROCESSING_GROUP,
        HintOptions.HINT_PROCESSING_ARTIFACT
})
public final class NativeImageHintProcessor extends AbstractHintProcessor {

    private static final String ENTRY_POINT_DEFAULT_VALUE = Void.class.getSimpleName();
    private static final String FILE_NAME = "native-image.properties";

    private static final String INIT_BUILD_TIME = "--initialize-at-build-time=";
    private static final String INIT_RUNTIME_TIME = "--initialize-at-run-time=";

    private static final String ARG_SEPARATOR = " \\\n       ";

    static class Entrypoint {

        private final String className;
        private final NativeImageHint hint;

        private Entrypoint(String className, NativeImageHint hint) {
            this.className = className;
            this.hint = hint;
        }
    }

    static class Initialization implements Comparable<Initialization> {

        private final InitializationHint.InitPhase phase;
        private final String className;

        Initialization(InitializationHint.InitPhase phase, String className) {
            this.phase = phase;
            this.className = className;
        }

        @Override
        public int compareTo(Initialization o) {
            return className.compareTo(o.className);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Initialization that = (Initialization) o;
            return Objects.equals(className, that.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className);
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        try {
            final Set<? extends Element> annotatedNative = roundEnv.getElementsAnnotatedWith(NativeImageHint.class);
            final Set<TypeElement> typesNative = ElementFilter.typesIn(annotatedNative);
            final List<String> nativeImageHintOptions = getNativeImageHintProperties(typesNative);

            final Set<TypeElement> typesInits = getAnnotatedElements(roundEnv, InitializationHint.class,
                    InitializationHints.class);
            final List<String> initializationHintOptions = getInitializationHintProperties(typesInits);

            final String nativeImageProperties = Stream.of(nativeImageHintOptions, initializationHintOptions)
                    .flatMap(Collection::stream)
                    .collect(Collectors.joining(ARG_SEPARATOR, "Args = ", ""));

            if (nativeImageProperties.isEmpty() && initializationHintOptions.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                        typesNative.size() + " annotations @NativeImageHint are present but not Options or Entrypoint found!");
                return false;
            } else {
                return writeConfigFile(FILE_NAME, nativeImageProperties, roundEnv);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private List<String> getNativeImageHintProperties(Set<TypeElement> elements) {
        final List<Entrypoint> entrypoints = elements.stream()
                .map(t -> getAnnotationFieldClassNameAny(t, NativeImageHint.class, "entrypoint")
                        .filter(name -> !ENTRY_POINT_DEFAULT_VALUE.equals(name))
                        .map(name -> new Entrypoint(name, t.getAnnotation(NativeImageHint.class)))
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (entrypoints.size() > 1) {
            throw new IllegalStateException("@NativeImageHint multiple entrypoint detected with values: " + entrypoints);
        }

        final Optional<Entrypoint> entryClassName = entrypoints.stream().findFirst();
        final List<String> options = elements.stream()
                .map(element -> element.getAnnotation(NativeImageHint.class))
                .flatMap(hint -> Stream
                        .concat(Arrays.stream(hint.options()).map(NativeImageOptions::option), Arrays.stream(hint.optionNames()))
                        .distinct())
                .collect(Collectors.toList());

        return entryClassName
                .map(entry -> {
                    final List<String> entryOptions = getEntrypointOptions(entry);
                    return Stream.of(entryOptions, options)
                            .flatMap(Collection::stream)
                            .distinct()
                            .collect(Collectors.toList());
                })
                .orElse(options);
    }

    private List<String> getEntrypointOptions(Entrypoint entrypoint) {
        return (entrypoint.hint.name().isBlank())
                ? List.of("-H:Class=" + entrypoint.className)
                : List.of("-H:Name=" + entrypoint.hint.name() + " -H:Class=" + entrypoint.className);
    }

    private List<String> getInitializationHintProperties(Set<TypeElement> elements) {
        final Map<InitializationHint.InitPhase, List<Initialization>> groupedInitializationOptions = elements.stream()
                .flatMap(e -> {
                    final InitializationHints hints = e.getAnnotation(InitializationHints.class);
                    if (hints == null) {
                        final InitializationHint hint = e.getAnnotation(InitializationHint.class);
                        return getInitialization(e, hint, false);
                    } else {
                        return Arrays.stream(hints.value())
                                .flatMap(h -> getInitialization(e, h, true));
                    }
                })
                .distinct()
                .collect(Collectors.groupingBy(e -> e.phase));

        return groupedInitializationOptions.entrySet().stream()
                .map(e -> e.getValue().stream()
                        .sorted()
                        .map(i -> i.className)
                        .collect(Collectors.joining(",", getInitializationArgumentName(e.getKey()), "")))
                .sorted()
                .collect(Collectors.toList());
    }

    private Stream<Initialization> getInitialization(TypeElement element, InitializationHint hint, boolean isParentAnnotation) {
        final List<String> types = (isParentAnnotation)
                ? getAnnotationFieldClassNames(element, InitializationHint.class, "types", InitializationHints.class)
                : getAnnotationFieldClassNames(element, InitializationHint.class, "types");

        final List<String> typeNames = Arrays.asList(hint.typeNames());
        if (types.isEmpty() && typeNames.isEmpty()) {
            return Stream.of(new Initialization(hint.value(), element.getQualifiedName().toString()));
        } else {
            return Stream.of(types, typeNames)
                    .flatMap(Collection::stream)
                    .map(type -> new Initialization(hint.value(), type));
        }
    }

    private String getInitializationArgumentName(InitializationHint.InitPhase phase) {
        return (InitializationHint.InitPhase.BUILD.equals(phase))
                ? INIT_BUILD_TIME
                : INIT_RUNTIME_TIME;
    }
}
