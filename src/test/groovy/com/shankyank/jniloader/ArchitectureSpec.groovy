package com.shankyank.jniloader

import static com.shankyank.jniloader.Architecture.*

import spock.lang.Specification
import spock.lang.Unroll

class ArchitectureSpec extends Specification {
    static final String TRUE_ARCH = System.getProperty('os.arch')

    def cleanupSpec() {
        System.setProperty('os.arch', TRUE_ARCH)
    }

    @Unroll
    def 'getSystemArchitecture: #archName'() {
        expect:
        System.setProperty('os.arch', archName) != null
        System.getProperty('os.arch') == archName
        Architecture.systemArchitecture == arch
        // verify case insensitivity
        System.setProperty('os.arch', archName?.toUpperCase()) != null
        System.getProperty('os.arch') == archName?.toUpperCase()
        Architecture.systemArchitecture == arch

        where:
        archName   || arch
        'x86'      || X86
        'i386'     || X86
        'i486'     || X86
        'i586'     || X86
        'i686'     || X86
        'pentium'  || X86
        'x86_64'   || X86_64
        'amd64'    || X86_64
        'em64t'    || X86_64
        'ia64'     || IA64
        'ia64w'    || IA64
        'ia64_32'  || IA64_32
        'ia64n'    || IA64_32
        'ppc'      || PPC
        'power'    || PPC
        'powerpc'  || PPC
        'power_pc' || PPC
        'power_rs' || PPC
        'ppc64'    || PPC64
        'sparc'    || SPARC
        'sparcv9'  || SPARCV9
        ''         || null
        'foobar'   || null
        'power64'  || null
        'unknown'  || null
    }
}
