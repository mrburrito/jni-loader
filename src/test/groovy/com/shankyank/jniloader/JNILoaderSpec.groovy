package com.shankyank.jniloader

import static com.shankyank.jniloader.Architecture.*
import static com.shankyank.jniloader.OperatingSystem.*

import com.shankyank.jniloader.JNILoader.Platform
import java.lang.reflect.Field
import org.apache.commons.io.FileUtils
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests proper execution of the dynamic JNI loader using the libraries
 * from the JBLAS linear algebra project, version 1.2.3. (https://github.com/mikiobraun/jblas)
 *
 * The JBLAS library maintains the following copyrights and licenses.
 *************************************************************************
 * Copyright (c) 2009, Mikio L. Braun
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *
 *     * Neither the name of the Technische Universität Berlin nor the
 *       names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior
 *       written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *************************************************************************
 */
class JNILoaderSpec extends Specification {
    // The platforms that the extracted libraries can be loaded on
    static final List SUPPORTED_TEST_PLATFORMS = [
            new Platform(WINDOWS, X86),
            new Platform(WINDOWS, X86_64),
            new Platform(LINUX, X86),
            new Platform(LINUX, X86_64),
            new Platform(DARWIN, X86_64)
    ].asImmutable()

    // The JBLAS library path
    static final String JBLAS_PATH = '/native/jblas'
    // The JBLAS library package
    static final String JBLAS_PACKAGE = 'jblas-1.2.3'

    // the original java.library.path
    static final String ORIG_LIB_PATH = System.getProperty("java.library.path")

    File extractionDir

    def setup() {
        extractionDir = new File(JNILoader.@TMP_DIR, "jniloadertest-${UUID.randomUUID().toString()}")
    }

    def cleanup() {
        // clean extracted libraries
        FileUtils.deleteDirectory(extractionDir)
        assert !extractionDir.exists()

        // reset java.library.path
        System.setProperty("java.library.path", ORIG_LIB_PATH)
        Field fieldSysPath = ClassLoader.getDeclaredField("sys_paths")
        fieldSysPath.accessible = true
        fieldSysPath.set(null, null)
    }

    def 'runtime platform'() {
        when:
        JNILoader loader = new JNILoader(extractionDir.name)
        boolean loaded = loader.extractLibs(JBLAS_PATH, JBLAS_PACKAGE)
        System.loadLibrary('jblas_arch_flavor')
        System.loadLibrary('jblas')

        then:
        loaded
        noExceptionThrown()
    }

    @Unroll
    def 'targeted platform: #platform'() {
        expect:
        JNILoader loader = new JNILoader(extractionDir.name, platform)
        loader.extractLibs(JBLAS_PATH, JBLAS_PACKAGE)
        List sysPaths = System.getProperty('java.library.path').split(File.pathSeparator).collect { new File(it).canonicalFile }
        getExpectedJBlasFiles(extractionDir, platform.operatingSystem, platform.architecture).each {
            assert it.file
            assert sysPaths.contains(it.parentFile.canonicalFile)
        }

        where:
        platform << SUPPORTED_TEST_PLATFORMS
    }

    @Unroll
    def 'linux on windows fallback: #os/#arch'() {
        expect:
        JNILoader loader = new JNILoader(extractionDir.name, new Platform(os, arch))
        loader.extractLibs(JBLAS_PATH, JBLAS_PACKAGE)
        getExpectedJBlasFiles(extractionDir, os, arch).each {
            assert it.file
        }

        where:
        os     | arch
        CYGWIN | X86
        CYGWIN | X86_64
        MINGW  | X86
        MINGW  | X86_64
        MSYS   | X86
        MSYS   | X86_64
    }

    static List<File> getExpectedJBlasFiles(final File extDir, final OperatingSystem os, final Architecture arch) {
        File libPath = new File(extDir, "jni-loader/${os.nativeString}/${arch.canonicalName}")
        switch (os) {
            case LINUX:
                [
                        new File(libPath, "libjblas_arch_flavor.so"),
                        new File(libPath, "sse3/libjblas.so")
                ]
                break
            case DARWIN:
                // expect both .jnilib and .dylib files
                [
                        new File(libPath, "libjblas_arch_flavor.jnilib"),
                        new File(libPath, "sse3/libjblas.jnilib"),
                        new File(libPath, "libjblas_arch_flavor.dylib"),
                        new File(libPath, "sse3/libjblas.dylib")
                ]
                break
            case [ WINDOWS, CYGWIN, MINGW, MSYS ]:
                switch (arch) {
                    case X86:
                        [
                                new File(libPath, "jblas_arch_flavor.dll"),
                                new File(libPath, "libgcc_s_dw2-1.dll"),
                                new File(libPath, "libgfortran-3.dll"),
                                new File(libPath, "sse3/jblas.dll")
                        ]
                        break
                    case X86_64:
                        [
                                new File(libPath, "jblas_arch_flavor.dll"),
                                new File(libPath, "libgcc_s_sjlj-1.dll"),
                                new File(libPath, "libgfortran-3.dll"),
                                new File(libPath, "jblas.dll")
                        ]
                        break
                    default:
                        []
                        break
                }
                break
            default:
                []
                break
        }
    }
}
