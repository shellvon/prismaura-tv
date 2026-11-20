package tv.prism.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.prism.library.Song
import tv.prism.catalog.SearchEntity
import tv.prism.catalog.SearchResultsPage

internal data class SearchUiState(
    val draft: String = "",
    val filtersOpen: Boolean = false,
    val term: String = "",
    val songs: List<Song> = emptyList(),
    val entities: List<SearchEntity> = emptyList(),
    val hasMore: Boolean = false,
    val type: String = "music",
    val sources: Set<String> = emptySet(),
)

internal data class SearchNavigationState(
    val term: String,
    val type: String,
    val sources: Set<String>,
    val hasResults: Boolean,
    val editing: Boolean,
)

internal fun SearchUiState.navigation() =
    SearchNavigationState(
        term,
        type,
        sources,
        songs.isNotEmpty() || entities.isNotEmpty(),
        draft.isBlank() || draft.trim() != term.trim(),
    )

internal class SearchViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()

    var nextPages: Map<String, Int> = emptyMap()
        private set

    fun setType(type: String) = mutableState.update { it.copy(type = type) }

    fun setSources(sources: Set<String>) = mutableState.update { it.copy(sources = sources) }

    fun editDraft(draft: String) = mutableState.update { it.copy(draft = draft) }

    fun showFilters(open: Boolean) = mutableState.update { it.copy(filtersOpen = open) }

    fun setTerm(term: String) =
        mutableState.update { if (it.term == term) it else it.copy(term = term, draft = term) }

    fun setPage(page: SearchResultsPage) {
        nextPages = page.nextPages
        mutableState.update { it.copy(songs = page.songs, entities = page.entities, hasMore = page.nextPages.isNotEmpty()) }
    }

    fun clearResults() = setPage(SearchResultsPage())
}

internal data class LibraryUiState(
    val list: String = "favorites",
    val managing: Boolean = false,
    val selected: Set<String> = emptySet(),
    val returningPlaylist: String? = null,
)

internal class LibraryViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state = mutableState.asStateFlow()

    fun open(list: String) =
        mutableState.update { if (it.list == list) it else LibraryUiState(list) }

    fun closePlaylist(id: String) {
        mutableState.value = LibraryUiState(list = "playlists", returningPlaylist = id)
    }

    fun finishPlaylistReturn(id: String) =
        mutableState.update {
            if (it.returningPlaylist == id) it.copy(returningPlaylist = null) else it
        }

    fun manage(managing: Boolean) =
        mutableState.update { it.copy(managing = managing, selected = emptySet()) }

    fun select(keys: Set<String>) = mutableState.update { it.copy(selected = keys) }

    fun toggle(key: String) =
        mutableState.update {
            it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
        }
}

internal data class PluginUiState(
    val configuredId: String? = null,
    val adding: Boolean = false,
    val addressError: String = "",
    val address: String = "",
    val collapsed: Set<String> = emptySet(),
)

internal class PluginViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(PluginUiState())
    val state = mutableState.asStateFlow()

    fun configure(id: String?) = mutableState.update { it.copy(configuredId = id) }

    fun editAddress(address: String) =
        mutableState.update { it.copy(address = address, addressError = "") }

    fun showAdd(adding: Boolean) =
        mutableState.update { it.copy(adding = adding, addressError = "") }

    fun showAddressError(error: String) = mutableState.update { it.copy(addressError = error) }

    fun toggleSubscription(url: String) =
        mutableState.update {
            it.copy(collapsed = if (url in it.collapsed) it.collapsed - url else it.collapsed + url)
        }
}

internal data class SettingsUiState(val section: String = "外观")

internal class SettingsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()

    fun open(section: String) {
        mutableState.value = SettingsUiState(section)
    }
}
