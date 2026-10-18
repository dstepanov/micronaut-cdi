package io.micronaut.cdi.test.graalvm;

/**
 * A public interface an event is observed by, which a class of another package implements through a supertype
 * only that package can name.
 *
 * @param <S> What is signalled
 */
public interface Signal<S> {
}
