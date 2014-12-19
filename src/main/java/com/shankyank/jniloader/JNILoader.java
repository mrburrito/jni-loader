package com.shankyank.jniloader;

import static com.shankyank.jniloader.Architecture.*;
import static com.shankyank.jniloader.OperatingSystem.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JNILoader dynamically extracts a set of native library files found
 * on the application classpath to a temporary directory and triggers a
 * refresh of the JVM's cached java.library.path so those libraries can
 * be loaded. This must be done before any calls to System.loadLibrary().
 *
 * It also provides a command line utility that outputs the architecture
 * and library suffix used to identify the appropriate files on the current
 * platform.
 */
public final class JNILoader {
    /** The class logger. */
    private static final Logger LOG = LoggerFactory.getLogger(JNILoader.class);

    /** The System library path property. */
    private static final String JAVA_LIBRARY_PATH = "java.library.path";

    /** The set of standard JVM platforms based on the JDK8 installers. */
    private static final List<Platform> JAVA_STANDARD_PLATFORMS = Collections.unmodifiableList(Arrays.asList(
            new Platform(WINDOWS, X86),
            new Platform(WINDOWS, X86_64),
            new Platform(LINUX, X86),
            new Platform(LINUX, X86_64),
            new Platform(DARWIN, X86_64),
            new Platform(SOLARIS, SPARCV9),
            new Platform(SOLARIS, X86_64),
            new Platform(CYGWIN, X86),
            new Platform(CYGWIN, X86_64),
            new Platform(MINGW, X86),
            new Platform(MINGW, X86_64),
            new Platform(MSYS, X86),
            new Platform(MSYS, X86_64)
    ));

    /**
     * The map of fallback platforms. If an archive is not found for
     * a particular platform, the fallback platform will be used.
     */
    private static final Map<Platform, Platform> FALLBACK_PLATFORMS = initFallbackPlatforms();
    private static Map<Platform, Platform> initFallbackPlatforms() {
        Map<Platform, Platform> map = new HashMap<>();
        map.put(new Platform(CYGWIN, X86), new Platform(WINDOWS, X86));
        map.put(new Platform(CYGWIN, X86_64), new Platform(WINDOWS, X86_64));
        map.put(new Platform(MINGW, X86), new Platform(WINDOWS, X86));
        map.put(new Platform(MINGW, X86_64), new Platform(WINDOWS, X86_64));
        map.put(new Platform(MSYS, X86), new Platform(WINDOWS, X86));
        map.put(new Platform(MSYS, X86_64), new Platform(WINDOWS, X86_64));
        return Collections.unmodifiableMap(map);
    }

    /** The pattern used to check for parent directory indicators in the temporary library path. */
    private static final Pattern PARENT_DIR = Pattern.compile("(^|/)\\.\\.(/|$)");

    /** The system temp directory. */
    private static final File TMP_DIR = new File(System.getProperty("java.io.tmpdir"));

    /** A filter that returns only directories. */
    private static final FileFilter DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(final File file) {
            return file.isDirectory();
        }
    };

    /** The canonical Platform for the current runtime. */
    public static final Platform RUNTIME_PLATFORM = new Platform(getSystemOS(), getSystemArchitecture());

    /** The system path lock. */
    private static final Object SYS_PATH_LOCK = new Object();

    /** The platform used by this loader. */
    private final Platform platform;

    /** The temporary directory where native libraries will be extracted. */
    private final File libraryPath;

    /** The set of library names that have been initialized. */
    private final Set<NativeLib> extractedLibs;

    /**
     * Create a new JNILoader for the current runtime platform that
     * extracts libraries to ${java.io.tmpdir}/jni-loader/${os}/${arch}.
     */
    public JNILoader() {
        this("", RUNTIME_PLATFORM);
    }

    /**
     * Create a new JNILoader for the current runtime platform that extracts
     * libraries to ${java.io.tmpdir}/${tmpPath}/jni-loader/${os}/${arch}.
     * @param tmpPath a subdirectory below java.io.tmpdir where native libraries will be extracted
     */
    public JNILoader(final String tmpPath) {
        this(tmpPath, RUNTIME_PLATFORM);
    }

    /**
     * Creates a new JNILoader for the specified runtime platform that
     * extracts libraries to ${java.io.tmpdir}/${tmpPath}/jni-loader/${os}/${arch}.
     * @param tmpPath a subdirectory below java.io.tmpdir where native libraries will be extracted
     * @param pform the target platform
     */
    protected JNILoader(final String tmpPath, final Platform pform) {
        if (pform == null) {
            throw new NullPointerException("Platform is required");
        }
        platform = pform;

        // remove trailing slashes
        String subDir = (tmpPath != null ? tmpPath.trim() : "").replaceFirst("^/*", "");
        if (PARENT_DIR.matcher(subDir).find()) {
            throw new IllegalArgumentException(String.format("Extraction path [%s] cannot traverse parent directories", subDir));
        }
        String path = String.format("%s/jni-loader/%s", subDir, platform.getSubdirectory());
        libraryPath = new File(TMP_DIR, path);

        extractedLibs = Collections.synchronizedSet(new HashSet<NativeLib>());
    }

    /**
     * Extracts the requested native libraries for the runtime platform and
     * updates the java.library.path so they can be loaded. This method must
     * be called for a particular set of libraries before any calls to
     * System.loadLibrary() referencing the extracted files.
     * @param resourcePath the path (relative to the classpath root) containing the native library packages
     * @param libPackage the basename of the archive containing the desired native libraries
     * @return <code>true</code> if the native libraries are successfully extracted
     * @throws IOException if the libraries cannot be extracted
     */
    public boolean extractLibs(final String resourcePath, final String libPackage) throws IOException {
        NativeLib nativeLib = new NativeLib(resourcePath, libPackage);
        String libPath = libraryPath.getPath();
        synchronized (extractedLibs) {
            if (!extractedLibs.contains(nativeLib)) {
                LOG.info("Extracting {} native libraries from {} to {}", nativeLib.libPackage, nativeLib.getArchivePath(platform), libPath);
                if (!(libraryPath.isDirectory() || libraryPath.mkdirs())) {
                    throw new FileNotFoundException(String.format("Unable to create library directory: %s", libPath));
                }
                if (verifyLibs(nativeLib)) {
                    LOG.info("{} native libraries already exist.", nativeLib.libPackage);
                    extractedLibs.add(nativeLib);
                } else {
                    ZipInputStream packaged = openNativeArchive(nativeLib);
                    try {
                        List<File> extractedFiles = new ArrayList<>();
                        for (ZipEntry entry = packaged.getNextEntry(); entry != null; entry = packaged.getNextEntry()) {
                            File tmpFile = new File(libraryPath, entry.getName());
                            if (entry.isDirectory()) {
                                LOG.debug("Creating directory: {}", tmpFile.getPath());
                                if (!(tmpFile.isDirectory() || tmpFile.mkdirs())) {
                                    LOG.error("Unable to create directory {}", tmpFile.getPath());
                                    return false;
                                }
                            } else {
                                LOG.debug("Extracting native library: {}", tmpFile.getPath());
                                try {
                                    FileOutputStream out = new FileOutputStream(tmpFile);
                                    IOUtils.copy(new EntryStream(packaged), out);
                                    extractedFiles.add(tmpFile);
                                } catch (IOException ioe) {
                                    throw new IOException(String.format("Error extracting native library [%s] to %s", entry.getName(), libPath), ioe);
                                }
                            }
                        }
                        // if running on OS X, ensure both .dylib and .jnilib files exist
                        // Java 6 expects .jnilib, Java 7+ expects .dylib
                        if (platform.getOperatingSystem() == DARWIN) {
                            for (File lib : extractedFiles) {
                                String altExt;
                                if (lib.getName().endsWith(".dylib")) {
                                    altExt = ".jnilib";
                                } else if (lib.getName().endsWith(".jnilib")) {
                                    altExt = ".dylib";
                                } else {
                                    // skip all non-library files in the archive
                                    continue;
                                }
                                File target = new File(lib.getParentFile(), lib.getName().replaceAll("\\.(dylib|jnilib)$", altExt));
                                if (!target.exists()) {
                                    LOG.info("[{}] (OS X) Copying {} to {}", libPackage, lib.getName(), target.getName());
                                    IOUtils.copy(new FileInputStream(lib), new FileOutputStream(target));
                                    String srcMd5 = md5sum(new FileInputStream(lib));
                                    String destMd5 = md5sum(new FileInputStream(target));
                                    if (!srcMd5.equals(destMd5)) {
                                        LOG.error("[{}] Error copying {} to {}. Bad checksum", libPackage, lib.getName(), target.getName());
                                        throw new IOException(String.format("Error copying %s to %s. Bad checksum.", lib.getName(), target.getName()));
                                    }
                                }
                            }
                        }
                    } finally {
                        packaged.close();
                    }
                    if (!verifyLibs(nativeLib)) {
                        throw new IOException(String.format("%s native libraries were not properly extracted to %s", nativeLib.libPackage, libPath));
                    }
                }
            }
        }
        return updateSystemPath();
    }

    /**
     * Updates the system java.library.path to include the directory where
     * native libraries will be placed by this loader.
     * @return <code>true</code> if the system path has been successfully updated
     * @throws IOException if an error occurs updating the path
     */
    private boolean updateSystemPath() throws IOException {
        Set<File> libDirs = buildLibTree(libraryPath, new TreeSet<File>());
        synchronized (SYS_PATH_LOCK) {
            String javaLibPath = System.getProperty(JAVA_LIBRARY_PATH);
            if (javaLibPath == null) {
                javaLibPath = "";
            }
            List<String> sysPaths = Arrays.asList(javaLibPath.split(File.pathSeparator));
            for (String path : sysPaths) {
                String cPath = new File(path).getCanonicalPath();
                for (Iterator<File> libIter = libDirs.iterator(); libIter.hasNext();) {
                    File libDir = libIter.next();
                    if (libDir.getCanonicalPath().equals(cPath)) {
                        libIter.remove();
                    }
                }
            }
            LOG.debug("Adding {} directories to java.library.path", libDirs.size());
            if (!libDirs.isEmpty()) {
                StringBuilder pathBuilder = new StringBuilder(javaLibPath);
                for (File libDir : libDirs) {
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append(File.pathSeparator);
                    }
                    pathBuilder.append(libDir.getCanonicalPath());
                }
                LOG.info("Updating java.library.path: {}", pathBuilder);
                System.setProperty(JAVA_LIBRARY_PATH, pathBuilder.toString());

                try {
                    Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                    fieldSysPath.setAccessible(true);
                    fieldSysPath.set(null, null);
                } catch (IllegalAccessException | NoSuchFieldException e) {
                    throw new IllegalStateException("Unable to clear system path cache", e);
                }
            }
            LOG.debug("java.library.path: {}", System.getProperty(JAVA_LIBRARY_PATH));
        }
        return true;
    }

    /**
     * Builds a depth-first list of all subdirectories of the
     * library extraction path for this loader.
     * @param root the root of the current directory tree
     * @param tree the set of subdirectories
     * @throws IOException if errors occur identifying the canonical directories
     */
    private Set<File> buildLibTree(final File root, final Set<File> tree) throws IOException {
        if (root.isDirectory()) {
            tree.add(root.getCanonicalFile());
            for (File dir : root.listFiles(DIR_FILTER)) {
                buildLibTree(dir, tree);
            }
        }
        return tree;
    }

    /**
     * Verifies that all native libraries have been successfully extracted.
     * @param nativeLib the package of libraries to verify
     * @return true if all native libraries have been successfully extracted
     * @throws IOException if errors occur verifying the libraries
     */
    private boolean verifyLibs(final NativeLib nativeLib) throws IOException {
        ZipInputStream packaged = openNativeArchive(nativeLib);
        try {
            for (ZipEntry entry = packaged.getNextEntry(); entry != null; entry = packaged.getNextEntry()) {
                File extractedFile = new File(libraryPath, entry.getName());
                if (entry.isDirectory()) {
                    if (!extractedFile.isDirectory()) {
                        LOG.warn("[{}] missing directory: {}", nativeLib.libPackage, entry.getName());
                    }
                } else {
                    if (!extractedFile.isFile()) {
                        LOG.warn("[{}] missing file: {}", nativeLib.libPackage, entry.getName());
                        return false;
                    }

                    String packagedMd5 = md5sum(new EntryStream(packaged));
                    String extractedMd5 = md5sum(new FileInputStream(extractedFile));

                    LOG.debug("[{}] {} (packaged):  {}", nativeLib.libPackage, entry.getName(), packagedMd5);
                    LOG.debug("[{}] {} (extracted): {}", nativeLib.libPackage, entry.getName(), extractedMd5);

                    if (!packagedMd5.equals(extractedMd5)) {
                        LOG.warn("[{}] bad checksum: {}", nativeLib.libPackage, entry.getName());
                        return false;
                    }
                }
            }
        } finally {
            packaged.close();
        }
        return true;
    }

    /**
     * Generates an MD5 hash for the provided InputStream.
     * @param input the input stream
     * @return the MD5 hash of the input
     * @throws IOException if errors occur processing the stream
     */
    private String md5sum(final InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            DigestOutputStream digestStream = new DigestOutputStream(new SinkOutputStream(), digest);
            IOUtils.copy(input, digestStream);
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) {
                hash.append(Integer.toHexString(b & 0xff));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException nsae) {
            throw new IllegalStateException("MD5 algorithm is not available.", nsae);
        } finally {
            input.close();
        }
    }

    /**
     * Opens the packaged archive containing the native libraries for the current platform.
     * @param nativeLib the package of libraries to open
     * @return a ZipFile for reading the archive
     * @throws FileNotFoundException if the library package for the runtime platform is not available
     */
    private ZipInputStream openNativeArchive(final NativeLib nativeLib) throws FileNotFoundException {
        InputStream nativeArchive = null;
        // iterate over all available fallback platforms to find archive
        for (Platform pform = platform; nativeArchive == null && pform != null; pform = FALLBACK_PLATFORMS.get(pform)) {
            String archive = nativeLib.getArchivePath(pform);
            LOG.debug("[{}] Opening archive {}", nativeLib.libPackage, archive);
            nativeArchive = JNILoader.class.getResourceAsStream(archive);
        }
        if (nativeArchive == null) {
            throw new FileNotFoundException(String.format("Unable to find native library for %s [%s]", RUNTIME_PLATFORM.getArchiveSuffix(),
                    nativeLib.getArchivePath(platform)));
        }
        return new ZipInputStream(nativeArchive);
    }

    public static void main(final String[] args) {
        Options opts = new Options();
        opts.addOption(OptionBuilder.withLongOpt("help").withDescription("Display this help text.").create('?'));
        opts.addOption(OptionBuilder.withLongOpt("list-platforms").
                withDescription("List the standard platforms recognized by the JNILoader. " +
                        "Other platforms may be supported by supplying archive files in the format <basename>-<os>-<arch>.").
                create('p'));
        opts.addOption(OptionBuilder.withLongOpt("os").withDescription("Display canonical OS name.").create('o'));
        opts.addOption(OptionBuilder.withLongOpt("arch").withDescription("Display canonical architecture name.").create('a'));
        opts.addOption(OptionBuilder.withLongOpt("init").
                        withDescription("Extracts the native libraries for the current platform and updates the system library path.").
                        create('i')
        );
        opts.addOption(OptionBuilder.
                        withLongOpt("resource-path").
                        hasArg(true).
                        withArgName("path").
                        withDescription("The path, relative to the classpath root, containing the library bundles.").
                        create('r')
        );
        opts.addOption(OptionBuilder.
                        withLongOpt("lib-name").
                        hasArg(true).
                        withArgName("lib_package").
                        withDescription("The base name of the library bundles. Bundles must be named ${basename}-${os}-${arch}.zip").
                        create('l')
        );

        String usage = "JNILoader -? | -p | -l <lib_package> [-r <path>] [-a] [-o] [-i]";
        HelpFormatter help = new HelpFormatter();
        help.setWidth(120);
        CommandLine commandLine = null;
        try {
            commandLine = new GnuParser().parse(opts, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            help.printHelp(usage, opts);
            System.exit(1);
        }

        if (commandLine.hasOption('?')) {
            help.printHelp(usage, opts);
            System.exit(0);
        }

        if (commandLine.hasOption('p')) {
            for (Platform platform : JAVA_STANDARD_PLATFORMS) {
                System.out.println(platform.toString());
            }
            System.exit(0);
        }

        if (!commandLine.hasOption('l')) {
            System.out.println("Missing required option: -l");
            help.printHelp(usage, opts);
            System.exit(1);
        }
        String resourcePath = commandLine.getOptionValue('r', "");
        String libPackage = commandLine.getOptionValue('l');

        NativeLib lib = null;
        try {
            lib = new NativeLib(resourcePath, libPackage);
        } catch (IllegalArgumentException iae) {
            System.out.println(iae.getMessage());
            help.printHelp(usage, opts);
            System.exit(1);
        }

        JNILoader loader = new JNILoader();
        if (commandLine.hasOption('o')) {
            System.out.printf("OS:   %s%n", loader.platform.getOperatingSystem().getNativeString());
        }
        if (commandLine.hasOption('a')) {
            System.out.printf("Arch: %s%n", loader.platform.getArchitecture().getCanonicalName());
        }
        System.out.printf("Native Lib Archive: %s%n", lib.getArchivePath(loader.platform));
        System.out.printf("Temp Directory:     %s%n", loader.libraryPath.getAbsolutePath());
        if (commandLine.hasOption('i')) {
            try {
                System.out.printf("Init? %s%n", loader.extractLibs(resourcePath, libPackage));
            } catch (IOException ioe) {
                System.out.printf("Error extracting native libraries [%s]: %s", lib, ioe.getMessage());
                ioe.printStackTrace();
                System.exit(1);
            }
        }
    }

    /**
     * Wrapper for a combination of resource path and library name.
     */
    private static class NativeLib {
        public final String resourcePath;
        public final String libPackage;

        public NativeLib(final String rPath, final String lPkg) {
            if (rPath != null && !rPath.trim().isEmpty() && !rPath.trim().startsWith("/")) {
                throw new IllegalArgumentException("Resource path must be empty or start with /");
            }
            if (lPkg == null || lPkg.trim().isEmpty()) {
                throw new IllegalArgumentException("Library package cannot be empty");
            }
            if (lPkg != null && lPkg.contains("/")) {
                throw new IllegalArgumentException("Library package cannot contain /");
            }
            String resPath = rPath != null ? rPath.trim() : "";
            resourcePath = resPath.isEmpty() || resPath.endsWith("/") ? resPath : String.format("%s/", resPath);
            libPackage = lPkg.trim();
        }

        public String getArchivePath(final Platform pform) {
            return String.format("%s%s-%s.zip", resourcePath, libPackage, pform.getArchiveSuffix());
        }

        @Override
        public String toString() {
            return String.format("%s [%s]", libPackage, resourcePath);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NativeLib nativeLib = (NativeLib) o;

            if (!libPackage.equals(nativeLib.libPackage)) return false;
            if (!resourcePath.equals(nativeLib.resourcePath)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = resourcePath.hashCode();
            result = 31 * result + libPackage.hashCode();
            return result;
        }
    }

    /**
     * An input stream that delegates to a ZipInputStream, calling the closeEntry() method
     * when reading is complete instead of closing the entire stream.
     */
    private static class EntryStream extends InputStream {
        private final ZipInputStream zipStream;

        public EntryStream(final ZipInputStream zis) {
            zipStream = zis;
        }

        @Override
        public int read() throws IOException {
            return zipStream.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return zipStream.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return zipStream.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return zipStream.skip(n);
        }

        @Override
        public int available() throws IOException {
            return zipStream.available();
        }

        @Override
        public void close() throws IOException {
            zipStream.closeEntry();
        }

        @Override
        public void mark(int readlimit) {
            zipStream.mark(readlimit);
        }

        @Override
        public void reset() throws IOException {
            zipStream.reset();
        }

        @Override
        public boolean markSupported() {
            return zipStream.markSupported();
        }
    }

    /**
     * OutputStream that ignores all writes.
     */
    private static class SinkOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
        }

        @Override
        public void write(byte[] b) throws IOException {
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
        }
    }

    /**
     * Container class for an OS/Architecture combination.
     */
    public static class Platform {
        private final OperatingSystem operatingSystem;
        private final Architecture architecture;

        /**
         * Create a new platform.
         * @param os the operating system
         * @param arch the architecture
         */
        public Platform(final OperatingSystem os, final Architecture arch) {
            this.operatingSystem = os;
            this.architecture = arch;
        }

        /**
         * @return the operating system of this platform
         */
        public OperatingSystem getOperatingSystem() {
            return operatingSystem;
        }

        /**
         * @return the architecture of this platform
         */
        public Architecture getArchitecture() {
            return architecture;
        }

        /**
         * @return the archive suffix for this platform: [os]-[arch]
         */
        private String getArchiveSuffix() {
            return String.format("%s-%s", operatingSystem.getNativeString(), architecture.getCanonicalName());
        }

        /**
         * @return the subdirectory for this platform: [os]/[arch]
         */
        private String getSubdirectory() {
            return String.format("%s/%s", operatingSystem.getNativeString(), architecture.getCanonicalName());
        }

        @Override
        public String toString() {
            return getArchiveSuffix();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Platform platform = (Platform) o;

            if (architecture != platform.architecture) return false;
            if (operatingSystem != platform.operatingSystem) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = operatingSystem.hashCode();
            result = 31 * result + architecture.hashCode();
            return result;
        }
    }
}
