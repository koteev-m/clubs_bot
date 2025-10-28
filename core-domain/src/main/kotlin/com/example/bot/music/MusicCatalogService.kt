package com.example.bot.music

/** Service orchestrating music repositories. */
class MusicCatalogService(private val items: MusicItemRepository, private val playlists: MusicPlaylistRepository) {
    suspend fun createItem(
        req: MusicItemCreate,
        actor: UserId,
    ): MusicItemView = items.create(req, actor)

    suspend fun listItems(filter: ItemFilter): List<MusicItemView> =
        items.listActive(filter.clubId, filter.limit, filter.offset, filter.tag, filter.q)

    suspend fun createPlaylist(
        req: PlaylistCreate,
        actor: UserId,
    ): PlaylistFullView {
        val playlist = playlists.create(req, actor)
        if (req.itemIds.isNotEmpty()) playlists.setItems(playlist.id, req.itemIds)
        return playlists.getFull(playlist.id)!!
    }

    suspend fun getPlaylist(id: Long): PlaylistFullView? = playlists.getFull(id)

    data class ItemFilter(
        val clubId: Long? = null,
        val tag: String? = null,
        val q: String? = null,
        val limit: Int = 20,
        val offset: Int = 0,
    )
}
