package com.joshuacerdenia.android.nicefeed.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.joshuacerdenia.android.nicefeed.R
import com.joshuacerdenia.android.nicefeed.data.local.NiceFeedPreferences
import com.joshuacerdenia.android.nicefeed.data.model.EntryMinimal
import com.joshuacerdenia.android.nicefeed.data.model.Feed
import com.joshuacerdenia.android.nicefeed.ui.dialog.AboutFeedFragment
import com.joshuacerdenia.android.nicefeed.ui.dialog.ConfirmRemoveFragment
import com.joshuacerdenia.android.nicefeed.ui.dialog.EditCategoryFragment
import com.joshuacerdenia.android.nicefeed.ui.dialog.FilterEntriesFragment
import com.joshuacerdenia.android.nicefeed.ui.menu.EntryPopupMenu

private const val TAG = "EntryListFragment"

class EntryListFragment : VisibleFragment(),
    EntryListAdapter.OnItemClickListener,
    EntryPopupMenu.OnItemClickListener,
    FilterEntriesFragment.Callbacks,
    AboutFeedFragment.Callbacks,
    EditCategoryFragment.Callbacks,
    ConfirmRemoveFragment.Callbacks {

    private val fragment = this@EntryListFragment
    private lateinit var viewModel: EntryListViewModel
    private lateinit var toolbar: Toolbar
    private lateinit var emptyMessageTextView: TextView
    private lateinit var masterProgressBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var searchItem: MenuItem
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EntryListAdapter
    private var feedId: String? = null
    private var autoUpdateIsEnabled = true
    private var markAllOptionsItem: MenuItem? = null
    private var starAllOptionsItem: MenuItem? = null
    private val handler = Handler()
    private var callbacks: Callbacks? = null

    interface Callbacks {
        fun onHomeSelected()
        fun onFeedLoaded(feedId: String)
        fun onEntrySelected(entryId: String)
        fun onCategoriesNeeded(): Array<String>
        fun onFeedRemoved()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as Callbacks?
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(EntryListViewModel::class.java)
        adapter = EntryListAdapter(this)

        context?.let { context ->
            viewModel.setOrder(NiceFeedPreferences.getEntriesOrder(context))
            autoUpdateIsEnabled = NiceFeedPreferences.getAutoUpdateSetting(context)
        }

        feedId = arguments?.getString(ARG_FEED_ID)
        val flag = arguments?.getInt(ARG_FLAG)
        when {
            feedId != null -> viewModel.getFeedWithEntries(feedId!!)
            flag == FLAG_RECENT_ENTRIES -> viewModel.getEntries()
            flag == FLAG_STARRED -> viewModel.getStarredEntries()
        }

        arguments?.getBoolean(ARG_IS_NEWLY_ADDED)?.let { isNewlyAdded ->
            if (isNewlyAdded || !autoUpdateIsEnabled) {
                viewModel.shouldAutoRefresh = false
            }
        }

        setHasOptionsMenu(feedId != null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_entry_list, container, false)
        toolbar = view.findViewById(R.id.toolbar)
        emptyMessageTextView = view.findViewById(R.id.empty_message_text_view)
        masterProgressBar = view.findViewById(R.id.master_progress_bar)
        progressBar = view.findViewById(R.id.progress_bar)

        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        toolbar.title = getString(R.string.loading)
        (activity as AppCompatActivity?)?.setSupportActionBar(toolbar)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.apply {
            setNavigationIcon(R.drawable.ic_menu)

            setNavigationOnClickListener {
                callbacks?.onHomeSelected()
            }
            setOnClickListener {
                recyclerView.scrollToPosition(0)
            }
        }

        viewModel.feedLiveData.observe(viewLifecycleOwner, Observer { feed ->
            Log.d(TAG, "Feed observer triggered!")
            viewModel.submitInitialFeed()
            feed?.let { callbacks?.onFeedLoaded(it.url) }
            // Check if not currently updating:
            if (toolbar.title != getString(R.string.updating)) {
                toolbar.title = feed?.title
            }
        })

        viewModel.entriesMinimalLiveData.observe(viewLifecycleOwner, Observer { entries ->
            Log.d(TAG, "Entries observer triggered!")
            viewModel.submitInitialEntries()
            adapter.submitList(entries)

            masterProgressBar.visibility = View.GONE
            toggleMarkAllOptionsItem(entries)
            toggleStarAllOptionsItem(entries)

            if (adapter.latestClickedPosition == 0) {
                handler.postDelayed({
                    recyclerView.scrollToPosition(0)
                }, 250)
            }

            // Show update notice, if any
            viewModel.updateValues?.let { values ->
                showRefreshedNotice(values.first, values.second)
                viewModel.updateValues = null
            }

            // Show if entry list is empty
            emptyMessageTextView.visibility = if (entries.isNullOrEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        })

        viewModel.updateResultLiveData.observe(viewLifecycleOwner, Observer { result ->
            result?.let { feedWithEntries ->
                viewModel.compareNewData(feedWithEntries)
                toolbar.title = feedWithEntries.feed.title
            }
            progressBar.visibility = View.GONE
        })
    }

    override fun onStart() {
        super.onStart()
        // Auto-refresh on launch
        if (viewModel.shouldAutoRefresh) {
            handler.postDelayed({
                feedId?.let { feedId ->
                    viewModel.requestUpdate(feedId)
                    toolbar.title = getString(R.string.updating)
                    progressBar.visibility = View.VISIBLE
                }
            }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        context?.let {  context ->
            val order = NiceFeedPreferences.getEntriesOrder(context)
            if (order != viewModel.currentOrder) {
                viewModel.setOrder(order)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_entry_list, menu)
        searchItem = menu.findItem(R.id.menuItem_search)
        markAllOptionsItem = menu.findItem(R.id.menuItem_mark_all)
        starAllOptionsItem = menu.findItem(R.id.menuItem_star_all)

        val searchView = searchItem.actionView as SearchView
        searchView.apply {
            if (viewModel.currentQuery.isNotEmpty()) {
                searchItem.expandActionView()
                setQuery(viewModel.currentQuery, false)
                clearFocus()
            }

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(queryText: String): Boolean {
                    viewModel.submitQuery(queryText)
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(queryText: String): Boolean {
                    return if (queryText.isEmpty()) {
                        viewModel.submitQuery(queryText)
                        true
                    } else {
                        false
                    }
                }
            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuItem_refresh -> handleRefresh(feedId)
            R.id.menuItem_about_feed -> handleShowFeedInfo(viewModel.getCurrentFeed())
            R.id.menuItem_filter -> handleFilter()
            R.id.menuItem_mark_all -> handleMarkAll()
            R.id.menuItem_star_all -> handleStarAll()
            R.id.menuItem_visit_website -> handleVisitWebsite(viewModel.getCurrentFeed()?.website)
            R.id.menuItem_delete_feed -> handleRemoveFeed()
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleMarkAllOptionsItem(list: List<EntryMinimal>) {
        markAllOptionsItem?.title = if (viewModel.allIsRead(list)) {
            getString(R.string.mark_all_as_unread)
        } else {
            getString(R.string.mark_all_as_read)
        }
    }

    private fun toggleStarAllOptionsItem(list: List<EntryMinimal>) {
        starAllOptionsItem?.title = if (viewModel.allIsStarred(list)) {
            getString(R.string.unstar_all)
        } else {
            getString(R.string.star_all)
        }
    }

    private fun handleRefresh(url: String?): Boolean {
        return if (url != null) {
            searchItem.collapseActionView()
            progressBar.visibility = View.VISIBLE
            toolbar.title = getString(R.string.updating)

            viewModel.submitQuery("")
            viewModel.requestUpdate(url)
            true
        } else {
            false
        }
    }

    private fun showRefreshedNotice(newCount: Int, updatedCount: Int) {
        val entriesAdded = resources.getQuantityString(R.plurals.numberOfNewEntries, newCount, newCount)
        val entriesUpdated = resources.getQuantityString(R.plurals.numberOfEntries, updatedCount, updatedCount)

        val message = when {
            newCount > 0 && updatedCount == 0 -> getString(R.string.added, entriesAdded)
            newCount == 0 && updatedCount > 0 -> getString(R.string.updated, entriesUpdated)
            else -> getString(R.string.added_and_updated, entriesAdded, updatedCount)
        }

        Snackbar.make(
            recyclerView,
            message as CharSequence,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun handleShowFeedInfo(feed: Feed?): Boolean {
        if (feed == null) return false
        AboutFeedFragment.newInstance(feed).apply {
            setTargetFragment(fragment, 0)
            show(fragment.requireFragmentManager(), "about")
        }
        return true
    }

    override fun onEditCategoryClicked() {
        val categories = callbacks?.onCategoriesNeeded() ?: arrayOf()
        EditCategoryFragment.newInstance(categories, viewModel.getCurrentFeed()?.title).apply {
            setTargetFragment(fragment, 0)
            show(fragment.requireFragmentManager(), "edit category")
        }
    }

    override fun onEditCategoryConfirmed(category: String) {
        viewModel.updateCategory(category)
        handler.postDelayed({
            Snackbar.make(
                recyclerView,
                getString(R.string.category_assigned, category, viewModel.getCurrentFeed()?.title),
                Snackbar.LENGTH_SHORT
            ).show()
        }, 250)
    }

    private fun handleFilter(): Boolean {
        FilterEntriesFragment.newInstance(viewModel.currentFilter).apply {
            setTargetFragment(fragment, 0)
            show(fragment.requireFragmentManager(), "filter")
        }
        return true
    }

    private fun handleMarkAll(): Boolean {
        viewModel.markAllCurrentEntriesAsRead()
        adapter.notifyDataSetChanged()
        return true
    }

    private fun handleStarAll(): Boolean {
        viewModel.starAllCurrentEntries()
        adapter.notifyDataSetChanged()
        return true
    }

    private fun handleVisitWebsite(website: String?): Boolean {
        if (website == null) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(website))
        startActivity(intent)
        return true
    }

    private fun handleRemoveFeed(): Boolean {
        val title = viewModel.getCurrentFeed()?.title
        ConfirmRemoveFragment.newInstance(title).apply {
            setTargetFragment(fragment, 0)
            show(fragment.requireFragmentManager(),"unsubscribe")
        }
        return true
    }

    override fun onRemoveConfirmed() {
        val title = viewModel.getCurrentFeed()?.title
        viewModel.deleteFeedAndEntries()

        Snackbar.make(
            recyclerView,
            getString(R.string.feed_removed_message, title),
            Snackbar.LENGTH_SHORT
        ).show()

        callbacks?.onFeedRemoved()
    }

    override fun onItemClicked(entry: EntryMinimal) {
        callbacks?.onEntrySelected(entry.url)
    }

    override fun onItemLongClicked(entry: EntryMinimal, view: View?) {
        val popupMenu = EntryPopupMenu(context, view, this, entry)
        popupMenu.show()
    }

    override fun onPopupMenuItemClicked(entry: EntryMinimal, action: Int) {
        val url = entry.url
        when (action) {
            EntryPopupMenu.ACTION_STAR -> viewModel.updateEntryIsStarred(url, !entry.isStarred)
            EntryPopupMenu.ACTION_MARK_AS -> viewModel.updateEntryIsRead(url, !entry.isRead)
            else -> {
                callbacks?.onEntrySelected(url)
                return
            }
        }
        adapter.notifyDataSetChanged()
    }

    override fun onFilterSelected(filter: Int) {
        viewModel.setFilter(filter)
    }

    companion object {
        const val FLAG_RECENT_ENTRIES = 0
        const val FLAG_STARRED = 1
        private const val ARG_FEED_ID = "ARG_FEED_ID"
        private const val ARG_IS_NEWLY_ADDED = "ARG_IS_NEWLY_ADDED"
        private const val ARG_FLAG = "ARG_FLAG"

        fun newInstance(flag: Int): EntryListFragment {
            return EntryListFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FLAG, flag)
                }
            }
        }

        fun newInstance(
            feedId: String?,
            isNewlyAdded: Boolean = false
        ): EntryListFragment {
            return EntryListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FEED_ID, feedId)
                    putBoolean(ARG_IS_NEWLY_ADDED, isNewlyAdded)
                }
            }
        }
    }
}