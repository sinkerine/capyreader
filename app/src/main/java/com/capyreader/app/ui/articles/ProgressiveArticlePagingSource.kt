package com.capyreader.app.ui.articles

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.cash.sqldelight.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Offset source that discovers the end of a result without executing COUNT(*). */
internal class ProgressiveArticlePagingSource<T : Any>(
    private val queryProvider: (limit: Long, offset: Long) -> Query<T>,
) : PagingSource<Int, T>() {
    private var activeQuery: Query<T>? = null
    private var activeListener: Query.Listener? = null

    init {
        registerInvalidatedCallback { clearActiveQuery() }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(Dispatchers.IO) {
        try {
            val requested = params.loadSize
            val key = params.key ?: 0
            val query = queryProvider(requested.toLong() + 1, key.toLong())
            val listener = Query.Listener { invalidate() }
            synchronized(this@ProgressiveArticlePagingSource) {
                clearActiveQuery()
                activeQuery = query
                activeListener = listener
                query.addListener(listener)
            }

            val loaded = query.executeAsList()
            val hasSentinel = loaded.size > requested
            val items = loaded.take(requested)

            if (invalid) return@withContext LoadResult.Invalid()

            val prevKey = if (key == 0) null else (key - requested).coerceAtLeast(0)
            val nextKey = if (hasSentinel) key + items.size else null

            LoadResult.Page(
                data = items,
                prevKey = prevKey,
                nextKey = nextKey,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(state.config.pageSize)
            ?: page.nextKey?.minus(state.config.pageSize)?.coerceAtLeast(0)
    }

    private fun clearActiveQuery() {
        synchronized(this) {
            activeQuery?.let { query ->
                activeListener?.let(query::removeListener)
            }
            activeQuery = null
            activeListener = null
        }
    }
}
