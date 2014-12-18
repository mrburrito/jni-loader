package com.shankyank.jniloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonicalizes the value of the System property os.name.
 */
public enum OperatingSystem {
    WINDOWS(".*windows.*"),
    DARWIN(".*mac.*"),
    LINUX(".*linux.*"),
    OTHER(".*");

    /** The class logger. */
    private static final Logger LOG = LoggerFactory.getLogger(OperatingSystem.class);

    /** The regular expression used to match instances of this operating system. */
    private final String namePattern;

    /**
     * Create a new canonical OperatingSystem.
     * @param pattern the name match expression
     */
    private OperatingSystem(final String pattern) {
        namePattern = pattern;
    }

    /**
     * @return the string used to reference this operating system when identifying native libraries
     */
    public String getNativeString() {
        String nativeStr = name().toLowerCase();
        if (this == OTHER) {
            String osName = System.getProperty("os.name");
            nativeStr = osName != null ? osName.replaceAll("\\W", "") : "";
        }
        return nativeStr;
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", name(), getNativeString());
    }

    /**
     * Identify the canonical operating system based on the value of the
     * system property os.name.
     * @return the canonicalized operating system or OTHER if it cannot be determined
     */
    public static OperatingSystem getSystemOS() {
        String osName = System.getProperty("os.name");
        OperatingSystem opSys = null;
        if (osName != null) {
            osName = osName.toLowerCase();
            for (OperatingSystem os : OperatingSystem.values()) {
                if (osName.matches(os.namePattern)) {
                    opSys = os;
                    LOG.debug("Found canonical operating system [{}] for os.name=\"{}\"", os, osName);
                    break;
                }
            }
        }
        if (opSys == null) {
            LOG.error("Unable to determine canonical operating system for os.name=\"{}\". Using {}", osName, OTHER);
            opSys = OTHER;
        }
        return opSys;
    }
}
