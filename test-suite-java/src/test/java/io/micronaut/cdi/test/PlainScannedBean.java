package io.micronaut.cdi.test;

/**
 * A class with nothing on it: a bean only because an extension added it to the scanned classes.
 */
public class PlainScannedBean {

    public String ping() {
        return "scanned";
    }
}
