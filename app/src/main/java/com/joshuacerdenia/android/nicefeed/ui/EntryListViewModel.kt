package com.joshuacerdenia.android.nicefeed.ui

import androidx.lifecycle.*
import com.joshuacerdenia.android.nicefeed.data.NiceFeedRepository
import com.joshuacerdenia.android.nicefeed.data.model.Entry
import com.joshuacerdenia.android.nicefeed.data.model.EntryMinimal
import com.joshuacerdenia.android.nicefeed.data.model.Feed
import com.joshuacerdenia.android.nicefeed.data.model.FeedWithEntries
import com.joshuacerdenia.android.nicefeed.data.remote.FeedParser
import com.joshuacerdenia.android.nicefeed.ui.dialog.FilterEntriesFragment
import com.joshuacerdenia.android.nicefeed.utils.UpdateManager
import com.joshuacerdenia.android.nicefeed.utils.sortedByDatePublished
import com.joshuacerdenia.android.nicefeed.utils.sortedUnreadOnTop
import kotlinx.coroutines.launch
import java.util.*

private const val TAG = "EntryListViewModel"

class EntryListViewModel: ViewModel(), UpdateManager.OnRefreshedListener {

    private val repo = NiceFeedRepository.get()
    private val parser = FeedParser()
    private val updateManager = UpdateManager(this)

    private val feedIdLiveData = MutableLiveData<String>()
    val feedLiveData = Transformations.switchMap(feedIdLiveData) { feedId ->
        repo.getFeedById(feedId)
    }
    private val sourceEntriesLiveData = Transformations.switchMap(feedIdLiveData) { feedId ->
        repo.getEntriesByFeedId(feedId)
    }
    private val entriesLiveData = MediatorLiveData<List<Entry>>()
    val entriesMinimalLiveData = MediatorLiveData<List<EntryMinimal>>()
    val updateResultLiveData = parser.feedRequestLiveData

    var currentQuery = ""
        private set
    var currentOrder = 0
        private set
    var currentFilter = 0
        private set
    var shouldAutoRefresh = true
    var updateValues: Pair<Int, Int>? = null
    private var updateWasRequested = false

    init {
        entriesLiveData.addSource(sourceEntriesLiveData) { source ->
            val filteredEntries = filterEntries(source, currentFilter)
            entriesLiveData.value = queryEntries(filteredEntries, currentQuery)
        }

        entriesMinimalLiveData.addSource(entriesLiveData) { entries ->
            val list = entries.map { entry ->
                EntryMinimal(
                    url = entry.url,
                    title = entry.title,
                    website = entry.website,
                    date = entry.date,
                    image = entry.image,
                    isRead = entry.isRead,
                    isStarred = entry.isStarred
                )
            }
            entriesMinimalLiveData.value = sortEntries(list, currentOrder)
        }
    }

    fun getFeedWithEntries(feedId: String) {
        feedIdLiveData.value = feedId
    }

    fun requestUpdate(url: String) {
        shouldAutoRefresh = false
        updateWasRequested = true
        viewModelScope.launch {
            parser.requestFeed(url)
        }
    }

    fun submitInitialEntries() {
        sourceEntriesLiveData.value?.let { entries ->
            updateManager.submitInitialEntries(entries)
        }
    }

    fun submitInitialFeed() {
        feedLiveData.value?.let { feed ->
            updateManager.submitInitialFeed(feed)
        }
    }

    fun compareNewData(feedWithEntries: FeedWithEntries) {
        if (updateWasRequested) {
            updateManager.submitNewData(feedWithEntries.feed, feedWithEntries.entries)
            updateWasRequested = false
        }
    }

    fun setFilter(filter: Int) {
        currentFilter = filter
        sourceEntriesLiveData.value?.let { entries ->
            val filteredEntries = filterEntries(entries, filter)
            entriesLiveData.value = queryEntries(filteredEntries, currentQuery)
        }
    }

    fun setOrder(order: Int) {
        currentOrder = order
        entriesMinimalLiveData.value?.let { entries ->
            entriesMinimalLiveData.value = sortEntries(entries, order)
        }
    }

    fun submitQuery(query: String) {
        currentQuery = query
        sourceEntriesLiveData.value?.let { source ->
            val filteredEntries = filterEntries(source, currentFilter)
            if (currentQuery.isNotEmpty()) {
                entriesLiveData.value = queryEntries(filteredEntries, query)
            } else {
                entriesLiveData.value = filteredEntries
            }
        }
    }

    fun starAllCurrentEntries() {
        val entries = entriesMinimalLiveData.value ?: emptyList()
        val isStarred = !allIsStarred(entries)
        val entryIds = entries.map { entry ->
            entry.url
        }.toTypedArray()

        repo.updateEntryIsStarred(*entryIds, isStarred = isStarred)
    }

    fun markAllCurrentEntriesAsRead() {
        val entries = entriesMinimalLiveData.value ?: emptyList()
        val isRead = !allIsRead(entries)
        val entryIds = entries.map { entry ->
            entry.url
        }.toTypedArray()

        repo.updateEntryIsRead(*entryIds, isRead = isRead)
    }

    fun allIsStarred(entries: List<EntryMinimal>): Boolean {
        var count = 0
        for (entry in entries) {
            if (entry.isStarred) {
                count += 1
            } else break
        }
        return count == entries.size
    }

    fun allIsRead(entries: List<EntryMinimal>): Boolean {
        var count = 0
        for (entry in entries) {
            if (entry.isRead) {
                count += 1
            } else break
        }
        return count == entries.size
    }

    private fun queryEntries(entries: List<Entry>, query: String): List<Entry> {
        val results = mutableListOf<Entry>()
        for (entry in entries) {
            if (entry.title.toLowerCase(Locale.ROOT).contains(query)) {
                results.add(entry)
            }
        }
        return results
    }

    private fun filterEntries(entries: List<Entry>, filter: Int): List<Entry> {
        return when (filter) {
            FilterEntriesFragment.FILTER_UNREAD -> entries.filter { !it.isRead }
            FilterEntriesFragment.FILTER_STARRED -> entries.filter { it.isStarred }
            else -> entries
        }
    }

    private fun sortEntries(entries: List<EntryMinimal>, order: Int): List<EntryMinimal> {
        return if (order == 1) {
            entries.sortedUnreadOnTop()
        } else {
            entries.sortedByDatePublished()
        }
    }

    override fun onUnreadEntriesCounted(feedId: String, unreadCount: Int) {
        repo.updateFeedUnreadCountById(feedId, unreadCount)
    }

    override fun onFeedNeedsRefresh(feed: Feed) {
        repo.updateFeed(feed)
    }

    override fun onEntriesNeedRefresh(
        toAdd: List<Entry>,
        toUpdate: List<Entry>,
        toDelete: List<Entry>,
        feedId: String
    ) {
        updateValues = if (toAdd.size + toUpdate.size > 0) {
            Pair(toAdd.size, toUpdate.size)
        } else {
            null
        }
        repo.refreshEntries(toAdd, toUpdate, toDelete, feedId)
    }

    fun updateCategory(category: String) {
        updateManager.currentFeed.apply {
            this?.category = category
        }?.let { feed ->
            repo.updateFeedCategory(feed.url, category = category)
        }
    }

    fun getCurrentFeed() = updateManager.currentFeed

    fun getEntries() {
        // TODO: Get recent entries
    }

    fun getStarredEntries() {
        // TODO: Get starred entries
    }

    fun updateEntryIsStarred(entryId: String, isStarred: Boolean) {
        repo.updateEntryIsStarred(entryId, isStarred = isStarred)
    }

    fun updateEntryIsRead(entryId: String, isRead: Boolean) {
        repo.updateEntryIsRead(entryId, isRead = isRead)
    }

    fun deleteFeedAndEntries() {
        val feedId = feedLiveData.value?.url
        feedId?.let { repo.deleteFeedAndEntriesById(it) }
    }
}