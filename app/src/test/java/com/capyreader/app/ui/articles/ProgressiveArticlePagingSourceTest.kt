package com.capyreader.app.ui.articles

import app.cash.sqldelight.Query
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveArticlePagingSourceTest {
    @Test
    fun `initial append and partial final page use sentinel without count`() = runTest {
        val offsets = mutableListOf<Long>()
        val query = queryOf((0 until 4).toList())
        val source = ProgressiveArticlePagingSource<Int> { limit, offset ->
            offsets += offset
            assertEquals(4L, limit)
            query
        }

        val result = source.load(PagingSource.LoadParams.Refresh(null, 3, false))

        assertEquals(PagingSource.LoadResult.Page(listOf(0, 1, 2), null, 3), result)
        assertEquals(listOf(0L), offsets)
    }

    @Test
    fun `partial final page has no next key`() = runTest {
        val source = ProgressiveArticlePagingSource<Int> { _, _ -> queryOf(listOf(3, 4)) }

        val result = source.load(PagingSource.LoadParams.Append(3, 3, false))

        assertEquals(PagingSource.LoadResult.Page(listOf(3, 4), 0, null), result)
    }

    @Test
    fun `prepend treats key as page start and backfills without overlap`() = runTest {
        val offsets = mutableListOf<Long>()
        val source = ProgressiveArticlePagingSource<Int> { limit, offset ->
            offsets += offset
            queryOf((offset.toInt() until (offset + limit).toInt()).toList())
        }

        source.load(PagingSource.LoadParams.Refresh(null, 3, false))
        source.load(PagingSource.LoadParams.Append(3, 3, false))
        source.load(PagingSource.LoadParams.Append(6, 3, false))
        val first = source.load(PagingSource.LoadParams.Prepend(3, 3, false))
        val second = source.load(PagingSource.LoadParams.Prepend(0, 3, false))

        assertEquals(PagingSource.LoadResult.Page(listOf(3, 4, 5), 0, 6), first)
        assertEquals(PagingSource.LoadResult.Page(listOf(0, 1, 2), null, 3), second)
        assertEquals(listOf(0L, 3L, 6L, 3L, 0L), offsets)
    }

    @Test
    fun `refresh key centers around anchor`() {
        val source = ProgressiveArticlePagingSource<Int> { _, _ -> queryOf(emptyList()) }
        val state = mockk<PagingState<Int, Int>>()
        every { state.anchorPosition } returns 60
        every { state.closestPageToPosition(60) } returns
            PagingSource.LoadResult.Page(listOf(50, 51), 0, 100)
        every { state.config } returns PagingConfig(pageSize = 50)

        assertEquals(50, source.getRefreshKey(state))
    }

    @Test
    fun `errors are returned and cancellation propagates`() = runTest {
        val failure = IOException("boom")
        val errorSource = ProgressiveArticlePagingSource<Int> { _, _ ->
            queryOf(emptyList(), failure)
        }
        val cancellation = CancellationException("cancelled")
        val cancellationSource = ProgressiveArticlePagingSource<Int> { _, _ ->
            queryOf(emptyList(), cancellation)
        }

        assertSame(failure, (errorSource.load(refresh()) as PagingSource.LoadResult.Error).throwable)
        try {
            cancellationSource.load(refresh())
            throw AssertionError("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
        }
    }

    @Test
    fun `invalidation during load returns Invalid`() = runTest {
        val query = queryOf(listOf(1, 2, 3))
        val source = ProgressiveArticlePagingSource<Int> { _, _ -> query }
        val listener = slot<Query.Listener>()
        every { query.addListener(capture(listener)) } just Runs
        every { query.executeAsList() } answers {
            listener.captured.queryResultsChanged()
            listOf(1, 2, 3)
        }

        assertTrue(source.load(refresh()) is PagingSource.LoadResult.Invalid)
    }

    @Test
    fun `only active query listener remains and invalidation cleans it up`() = runTest {
        val first = queryOf(listOf(1))
        val second = queryOf(listOf(2))
        val queries = ArrayDeque(listOf(first, second))
        val source = ProgressiveArticlePagingSource<Int> { _, _ -> queries.removeFirst() }

        source.load(refresh())
        source.load(PagingSource.LoadParams.Append(1, 1, false))

        verify(exactly = 1) { first.removeListener(any()) }
        source.invalidate()
        verify(exactly = 1) { second.removeListener(any()) }
    }

    private fun refresh() = PagingSource.LoadParams.Refresh<Int>(null, 2, false)

    private fun <T : Any> queryOf(
        values: List<T>,
        failure: Exception? = null,
    ): Query<T> = mockk(relaxed = true) {
        every { executeAsList() } answers {
            failure?.let { throw it }
            values
        }
    }
}
