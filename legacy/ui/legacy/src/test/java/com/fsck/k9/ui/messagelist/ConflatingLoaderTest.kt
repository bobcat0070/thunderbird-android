package com.fsck.k9.ui.messagelist

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConflatingLoaderTest {
    private var loadCount = 0
    private val loaded = mutableListOf<Int>()

    @Test
    fun `a request should load once`() = runTest {
        val testSubject = createTestSubject()

        testSubject.requestLoad()
        runCurrent()

        assertThat(loaded).containsExactly(1)
    }

    @Test
    fun `requests arriving while a load is waiting should be answered by that one load`() = runTest {
        val testSubject = createTestSubject()

        // How a sync looks from here: a change for every message saved, far faster than a large list loads.
        repeat(100) { testSubject.requestLoad() }
        runCurrent()

        assertThat(loadCount).isEqualTo(1)
    }

    @Test
    fun `a request arriving during a load should be answered by one more load after it`() = runTest {
        val testSubject = createTestSubject()

        testSubject.requestLoad()
        runCurrent()
        repeat(10) { testSubject.requestLoad() }
        runCurrent()

        assertThat(loaded).containsExactly(1, 2)
    }

    @Test
    fun `requests arriving during the pause after a load should wait for it to end`() = runTest {
        val testSubject = createTestSubject(pauseAfterLoadMillis = 500)

        testSubject.requestLoad()
        runCurrent()
        repeat(10) { testSubject.requestLoad() }
        advanceTimeBy(499)
        runCurrent()
        assertThat(loaded).containsExactly(1)

        advanceTimeBy(1)
        runCurrent()
        assertThat(loaded).containsExactly(1, 2)
    }

    private fun TestScope.createTestSubject(pauseAfterLoadMillis: Long = 0) = ConflatingLoader(
        scope = backgroundScope,
        loadContext = coroutineContext[ContinuationInterceptor]!!,
        load = { ++loadCount },
        onLoaded = { loaded += it },
        pauseAfterLoadMillis = pauseAfterLoadMillis,
    )
}
