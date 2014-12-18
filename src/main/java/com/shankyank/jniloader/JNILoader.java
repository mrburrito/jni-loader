package com.shankyank.jniloader;

import java.io.File;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public class JNILoader {
    /** The class logger. */
    private static final Logger LOG = LoggerFactory.getLogger(JNILoader.class);

    /** The System library path property. */
    private static final String JAVA_LIBRARY_PATH = "java.library.path";

    /** The canonical Operating System for the current runtime. */
    public static final OperatingSystem OS = OperatingSystem.getSystemOS();

    /** The canonical Architecture for the current runtime. */
    public static final Architecture ARCH = Architecture.getSystemArchitecture();

    /** The temporary directory where native libraries will be extracted. */
    private static final File LIBRARY_PATH = initLibraryPath();

    /** The set of library names that have been initialized. */
    private static final Set<NativeLib> EXTRACTED_LIBS = Collections.synchronizedSet(new HashSet<NativeLib>());

    /** The system path lock. */
    private static final Object SYS_PATH_LOCK = new Object();

    /** True once the java.library.path has been updated to include the dynamic library path. */
    private static boolean systemInitialized = false;

    /**
     * @return the temporary directory where native libraries will be extracted
     */
    private static File initLibraryPath() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        String path = String.format("jni-loader/%s/%s", OS.getNativeString(), ARCH.getCanonicalName());
        return new File(tmpDir, path);
    }

    static void main(final String[] args) {
        Options opts = new Options();
        opts.addOption(OptionBuilder.withLongOpt("help").withDescription("Display this help text.").create('?'));
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
                create('p')
        );
        opts.addOption(OptionBuilder.
                        withLongOpt("lib-name").
                        hasArg(true).
                        withArgName("lib_package").
                        isRequired(true).
                        withDescription("The base name of the library bundles. Bundles must be named ${basename}-${os}-${arch}.zip").
                        create('l')
        );

        String usage = "JNILoader -l <lib_package> [-r <path>] [-a] [-o] [-i]";
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

        if (commandLine.hasOption('o')) {
            System.out.printf("OS:   %s%n", OS.getNativeString());
        }
        if (commandLine.hasOption('a')) {
            System.out.printf("Arch: %s%n", ARCH.getCanonicalName());
        }
        System.out.printf("Native Lib Archive: %s%n", lib.getArchivePath());
        System.out.printf("Temp Directory:     %s%n", LIBRARY_PATH.getAbsolutePath());
        if (commandLine.hasOption('i')) {
            try {
                System.out.printf("Init? %s%n", extractLibs(resourcePath, libPackage));
            } catch (IOException ioe) {
                System.out.printf("Error extracting native libraries [%s]: %s", lib, ioe.getMessage());
                ioe.printStackTrace();
                System.exit(1);
            }
        }
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
    public static boolean extractLibs(final String resourcePath, final String libPackage) throws IOException {
        NativeLib nativeLib = new NativeLib(resourcePath, libPackage);
        String libPath = LIBRARY_PATH.getPath();
        synchronized (EXTRACTED_LIBS) {
            if (!EXTRACTED_LIBS.contains(nativeLib)) {
                LOG.info("Extracting {} native libraries to {}", nativeLib.libPackage, libPath);
                if (!(LIBRARY_PATH.isDirectory() || LIBRARY_PATH.mkdirs())) {
                    throw new FileNotFoundException(String.format("Unable to create library directory: %s", libPath));
                }
                if (verifyLibs(nativeLib)) {
                    LOG.info("{} native libraries already exist.", nativeLib.libPackage);
                    EXTRACTED_LIBS.add(nativeLib);
                } else {
                    ZipInputStream packaged = openNativeArchive(nativeLib);
                    try {
                        for (ZipEntry entry = packaged.getNextEntry(); entry != null; entry = packaged.getNextEntry()) {
                            LOG.debug("Extracting native library: {}/${}", libPath, entry.getName());
                            try {
                                FileOutputStream out = new FileOutputStream(new File(LIBRARY_PATH, entry.getName()));
                                IOUtils.copy(new EntryStream(packaged), out);
                            } catch (IOException ioe) {
                                throw new IOException(String.format("Error extracting native library [%s] to %s", entry.getName(), libPath), ioe);
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
        return initSystemPath();
    }

    /**
     * Updates the system java.library.path to include the directory where
     * native libraries will be placed by this loader.
     * @return <code>true</code> if the system path has been successfully updated
     * @throws IOException if an error occurs updating the path
     */
    public static boolean initSystemPath() throws IOException {
        synchronized (SYS_PATH_LOCK) {
            if (!systemInitialized) {
                String jlp = System.getProperty(JAVA_LIBRARY_PATH);
                if (jlp == null) {
                    jlp = "";
                }
                List<String> libPaths = Arrays.asList(jlp.split(File.pathSeparator));
                boolean found = false;
                for (String path : libPaths) {
                    if (LIBRARY_PATH.getCanonicalPath().equals(new File(path).getCanonicalPath())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    String newPath = String.format("%s%s%s", jlp, !jlp.isEmpty() ? File.pathSeparator : "", LIBRARY_PATH.getCanonicalPath());
                    LOG.info("Updating java.library.path: {}", newPath);
                    System.setProperty(JAVA_LIBRARY_PATH, newPath);

                    try {
                        Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                        fieldSysPath.setAccessible(true);
                        fieldSysPath.set(null, null);
                    } catch (IllegalAccessException | NoSuchFieldException e) {
                        throw new IllegalStateException("Unable to clear system path cache", e);
                    }
                }
                LOG.info("java.library.path: ${System.getProperty('java.library.path')}");
                systemInitialized = true;
            }
        }
        LOG.debug("java.library.path: {}", System.getProperty(JAVA_LIBRARY_PATH));
        return systemInitialized;
    }

    /**
     * Verifies that all native libraries have been successfully extracted.
     * @param nativeLib the package of libraries to verify
     * @return true if all native libraries have been successfully extracted
     * @throws IOException if errors occur verifying the libraries
     */
    private static boolean verifyLibs(final NativeLib nativeLib) throws IOException {
        ZipInputStream packaged = openNativeArchive(nativeLib);
        try {
            for (ZipEntry entry = packaged.getNextEntry(); entry != null; entry = packaged.getNextEntry()) {
                File extractedFile = new File(LIBRARY_PATH, entry.getName());
                if (!extractedFile.isFile()) {
                    LOG.warn("{} was not extracted", extractedFile.getPath());
                    return false;
                }

                String packagedMd5 = md5sum(new EntryStream(packaged));
                String extractedMd5 = md5sum(new FileInputStream(extractedFile));

                LOG.debug("{} (packaged):  {}", entry.getName(), packagedMd5);
                LOG.debug("{} (extracted): {}", entry.getName(), extractedMd5);

                if (!packagedMd5.equals(extractedMd5)) {
                    LOG.warn("Bad checksum for {}", extractedFile.getPath());
                    return false;
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
    private static String md5sum(final InputStream input) throws IOException {
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
    private static ZipInputStream openNativeArchive(final NativeLib nativeLib) throws FileNotFoundException {
        String archive = nativeLib.getArchivePath();
        LOG.info("Extracting {} native libraries from archive {}", nativeLib.libPackage, archive);
        InputStream nativeArchive = JNILoader.class.getResourceAsStream(archive);
        if (nativeArchive == null) {
            throw new FileNotFoundException(String.format("Unable to find native library for %s/%s [%s]", OS.getNativeString(), ARCH.getCanonicalName(), archive));
        }
        return new ZipInputStream(nativeArchive);
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

        public String getArchivePath() {
            return String.format("%s%s-%s-%s.zip", resourcePath, libPackage, OS.getNativeString(), ARCH.getCanonicalName());
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
}
