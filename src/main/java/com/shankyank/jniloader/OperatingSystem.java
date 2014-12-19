package com.shankyank.jniloader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonicalizes the value of the System property os.name for supported Java
 * platforms. The supported platforms are:
 * <ul>
 *     <li>Windows</li>
 *     <li>Darwin/OS X</li>
 *     <li>Linux</li>
 *     <li>Solaris</li>
 * </ul>
 *
 * In addition, Linux runtimes for Windows are supported as independent
 * operating systems so custom native bundles can be loaded for those
 * platforms. The JNILoader will fall back to a Windows bundle if the
 * Win/Linux platform does not have one of its own. The supported Linux
 * runtimes are:
 * <ul>
 *     <li>Cygwin</li>
 *     <li>MinGW</li>
 *     <li>Msys</li>
 * </ul>
 */
public enum OperatingSystem {
    WINDOWS(".*windows.*"),
    DARWIN(".*mac.*", ".*darwin.*"),
    LINUX(".*linux.*"),
    SOLARIS(".*sunos.*", ".*solaris.*"),
    CYGWIN(".*cygwin.*"),
    MINGW(".*mingw.*"),
    MSYS(".*msys.*"),
    OTHER(".*");

    /** The class logger. */
    private static final Logger LOG = LoggerFactory.getLogger(OperatingSystem.class);

    /** The regular expression used to match instances of this operating system. */
    private final List<String> namePatterns;

    /**
     * Create a new canonical OperatingSystem.
     * @param patterns the name match expressions
     */
    private OperatingSystem(final String... patterns) {
        namePatterns = Collections.unmodifiableList(Arrays.asList(patterns));
    }

    /**
     * @return the string used to reference this operating system when identifying native libraries
     */
    public String getNativeString() {
        String nativeStr = name().toLowerCase();
        if (this == OTHER) {
            String osName = System.getProperty("os.name");
            nativeStr = osName != null ? osName.replaceAll("\\W", "").toLowerCase() : "";
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
                for (String pattern : os.namePatterns) {
                    if (osName.matches(pattern)) {
                        opSys = os;
                        LOG.debug("Found canonical operating system [{}] for os.name=\"{}\"", os, osName);
                        break;
                    }
                }
                if (opSys != null) {
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
