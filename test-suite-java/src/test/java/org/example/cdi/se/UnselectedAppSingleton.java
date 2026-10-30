package org.example.cdi.se;

/**
 * A plain {@code jakarta.inject} singleton of the application, which {@code SeUnselectedSingletonTest} never selects:
 * a class of its own, so that it is off the classpath the test restricts a deployment to.
 */
@jakarta.inject.Singleton
public class UnselectedAppSingleton {
}
