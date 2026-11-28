package com.strybok.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The common tests read the shared vectors from a constant the build generates; on the JVM the
 * file itself is on the test classpath too, so this checks that the generator changed nothing.
 */
class ParameterLinkVectorsJvmTest {
    @Test
    fun theGeneratedTextIsTheFileUnchanged() {
        val file = assertNotNull(javaClass.classLoader.getResource("parameter-links-vectors.json"))
        assertEquals(file.readBytes().decodeToString(throwOnInvalidSequence = true), PARAMETER_LINK_VECTORS_JSON)
    }
}
