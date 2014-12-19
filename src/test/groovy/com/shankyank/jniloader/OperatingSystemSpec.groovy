package com.shankyank.jniloader

import static com.shankyank.jniloader.OperatingSystem.*

import spock.lang.Specification
import spock.lang.Unroll

class OperatingSystemSpec extends Specification {
    static final String TRUE_OS = System.getProperty('os.name')

    def cleanupSpec() {
        System.setProperty('os.name', TRUE_OS)
    }

    // Windows OS values taken from JDK9 native source
    // Linux OS values taken from subset of uname -s values on http://en.wikipedia.org/wiki/Uname
    // OS X values taken from JDK9 native source and uname -s values from page above
    @Unroll
    def 'getSystemOS: #osName'() {
        expect:
        System.setProperty('os.name', osName) != null
        System.getProperty('os.name') == osName
        OperatingSystem.systemOS == os
        OperatingSystem.systemOS.nativeString == nativeStr
        // verify case insensitivity
        System.setProperty('os.name', osName?.toUpperCase()) != null
        System.getProperty('os.name') == osName?.toUpperCase()
        OperatingSystem.systemOS == os
        OperatingSystem.systemOS.nativeString == nativeStr

        where:
        osName                   || os      | nativeStr
        'Windows 3.1'            || WINDOWS | 'windows'
        'Windows 98'             || WINDOWS | 'windows'
        'Windows Me'             || WINDOWS | 'windows'
        'Windows 9X (unknown)'   || WINDOWS | 'windows'
        'Windows NT'             || WINDOWS | 'windows'
        'Windows 2000'           || WINDOWS | 'windows'
        'Windows XP'             || WINDOWS | 'windows'
        'Windows 2003'           || WINDOWS | 'windows'
        'Windows NT (unknown)'   || WINDOWS | 'windows'
        'Windows Vista'          || WINDOWS | 'windows'
        'Windows 7'              || WINDOWS | 'windows'
        'Windows 8'              || WINDOWS | 'windows'
        'Windows 8.1'            || WINDOWS | 'windows'
        'Windows Server 2008'    || WINDOWS | 'windows'
        'Windows Server 2008 R2' || WINDOWS | 'windows'
        'Windows Server 2012'    || WINDOWS | 'windows'
        'Windows Server 2012 R2' || WINDOWS | 'windows'
        'Windows (unknown)'      || WINDOWS | 'windows'
        'Linux'                  || LINUX   | 'linux'
        'Mac OS X'               || DARWIN  | 'darwin'
        'Darwin'                 || DARWIN  | 'darwin'
        'SunOS'                  || SOLARIS | 'solaris'
        'Solaris'                || SOLARIS | 'solaris'
        'CYGWIN_NT-5.1'          || CYGWIN  | 'cygwin'
        'CYGWIN_NT-6.1'          || CYGWIN  | 'cygwin'
        'CYGWIN_NT-6.1-WOW64'    || CYGWIN  | 'cygwin'
        'MINGW32_NT-6.1'         || MINGW   | 'mingw'
        'MSYS_NT-6.1'            || MSYS    | 'msys'
        'GNU'                    || OTHER   | 'gnu'
        'GNU/kFreeBSD'           || OTHER   | 'gnukfreebsd'
        'FreeBSD'                || OTHER   | 'freebsd'
        'HP-UX'                  || OTHER   | 'hpux'
        'NetBSD'                 || OTHER   | 'netbsd'
        ''                       || OTHER   | ''
    }
}
