package com.shankyank.jniloader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enum of possible architecture values that attempts to normalize the
 * value in system property `os.arch`.
 */
public enum Architecture {
    X86("x86", "x86", "i386", "i486", "i586", "i686", "pentium"),
    X86_64("x86_64", "x86 (64-bit)", "amd64", "em64t"),
    IA64("ia64", "Itanium", "ia64w"),
    IA64_32("ia64_32", "Itanium (32-bit mode)", "ia64n"),
    PPC("ppc", "PowerPC", "power", "powerpc", "power_pc", "power_rs"),
    PPC64("ppc64", "PowerPC (64-bit)"),
    SPARC("sparc", "SPARC"),
    SPARCV9("sparcv9", "SPARCv9 (64-bit)");

    private static final Logger LOG = LoggerFactory.getLogger(Architecture.class);

    /** The canonical architecture name. */
    private final String canonicalName;
    /** The description of this architecture. */
    private final String description;
    /** The alternate names for this architecture. */
    private final List<String> alternateNames;

    private Architecture(final String cName, final String desc, final String... altNames) {
        canonicalName = cName;
        description = desc;
        alternateNames = Collections.unmodifiableList(Arrays.asList(altNames));
    }

    /**
     * @return the canonical name of this architecture
     */
    public String getCanonicalName() {
        return canonicalName;
    }

    /**
     * @return the description of this architecture
     */
    public String getDescription() {
        return description;
    }

    /**
     * Normalizes the value found in the os.arch property and returns
     * a canonical architecture. If the value cannot be normalized, `null`
     * will be returned.
     * @return the canonical architecture based on the os.arch property value
     */
    public static Architecture getSystemArchitecture() {
        String osArch = System.getProperty("os.arch");
        Architecture architecture = null;
        if (osArch != null) {
            osArch = osArch.toLowerCase();
            for (Architecture arch : Architecture.values()) {
                if (arch.canonicalName.equals(osArch) || arch.alternateNames.contains(osArch)) {
                    architecture = arch;
                    LOG.debug("Found canonical architecture [{}] for os.arch=\"{}\"", arch, osArch);
                    break;
                }
            }
        }
        if (architecture == null) {
            LOG.warn("Unable to determine canonical architecture for os.arch=\"{}\"", osArch);
        }
        return architecture;
    }
}
