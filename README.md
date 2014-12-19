Dynamic JNI Loader
==========

The `JNILoader` allows dynamic loading of bundled native libraries into
a running JVM based on the runtime platform. It canonicalizes the
operating system and architecture based on the values of the System
properties `os.name` and `os.arch`, then looks for a zip archive,
`${libName}-${os}-${arch}`, on the classpath and extracts its contents
to a temporary directory. The temporary directory, and any subdirectories
found in the archive, are added to the `java.library.path` and the cached
paths are cleared so the JVM reconstructs them the next time `System.loadLibrary()`
is called.

### Canonical Operating Systems

- `windows`
- `linux`
- `darwin`
- `solaris`
- `cygwin`
- `mingw`
- `msys`
- `OTHER`

_Other operating systems, such as HP-UX, AIX, etc. can be supported by using
the lowercased value of `os.name` on that platform with all non-word (`\W`)
characters removed as the `${os}` portion of the archive name._

### Canonical Architectures

- `x86`, Intel x86
- `x86_64`, Intel x86 (64-bit)
- `ia64`, Itanium
- `is64_32`, Itanium (32-bit mode)
- `ppc`, PowerPC
- `ppc64`, PowerPC (64-bit)
- `sparc`, SPARC
- `sparcv9`, SPARC (64-bit)

### Standard Platforms

These OS/Architecture combinations are the platforms Oracle supports with
a downloadable Java 8 Runtime.

- `windows-x86`
- `windows-x86_64`
- `linux-x86`
- `linux-x86_64`
- `solaris-sparcv9`
- `solaris-x86_64`
- `darwin-x86_64`

## Adding Native Libraries

Native libraries for your supported platforms should be included on the classpath
as a zip archive. The extractor supports subdirectories in the archive and will
add those subdirectories to the `java.library.path` once they have been created.
Archives for a particular library should be located in the same classpath package
and should be named with a common library base name, the target operating system
and the target architecture: `${basename}-${os}-${arch}`.

For example, if you were including the native libraries `mylib` and `extlib-1.2.3`
in a Maven based project, your `src` tree might look like:

```
- src
  - main
    - resources
      - native
        - mylib-windows-x86.zip
        - mylib-windows-x86_64.zip
        - mylib-linux-x86.zip
        - mylib-linux-x86_64.zip
        - mylib-darwin-x86_64.zip
        - extlib-1.2.3-windows-x86.zip
        - extlib-1.2.3-windows-x86_64.zip
        - extlib-1.2.3-linux-x86.zip
        - extlib-1.2.3-linux-x86_64.zip
        - extlib-1.2.3-darwin-x86_64.zip
```

## Usage

The default constructor of `JNILoader` will create a dynamic loader
for the runtime platform that extracts libraries to
`${java.io.tmpdir}/jni-loader/${os}/${arch}`. Prior to calling
`System.loadLibrary()` for your libraries, or initializing external
dependencies that rely on bundled libraries, you must call `JNILoader.extractLibs()`,
providing it the path (in the classpath) containing your archives and the library
prefix you used for the archives. If `extractLibs` completes successfully, you can
load native code using the standard System calls.

Using the example above, your app initialization might look something like
```
import com.shankyank.jniloader.JNILoader;

public void initialize() {
    JNILoader loader = new JNILoader();
    // initialize 'mylib'
    loader.extractLibs("/native", "mylib");
    // initialize 'extlib'
    loader.extractLibs("/native", "extlib-1.2.3");
    System.loadLibrary("my_native_lib");
}
```

### OS X Support

The default file extension for native libraries on OS X changed from `.jnilib` in
Apple's Java 6 implementation to `.dylib` in OpenJDK 7+. The `JNILoader` will
ensure that files with both extensions exist when it unbundles a darwin archive,
duplicating the extracted files if the bundle only contains one of the two.

### Linux on Windows Runtimes

Cygwin, MinGW and Msys are recognized as separate operating systems to support
specialized .dll files for those platforms. If an archive bundle for one of
those platforms is not found, the loader will look for a Windows bundle for
the current architecture and attempt to use those libraries instead.

### Maven Coordinates

```
<dependency>
  <groupId>com.shankyank.jni</groupId>
  <artifactId>jni-loader</artifactId>
  <version>0.1</version>
</dependency>
```
