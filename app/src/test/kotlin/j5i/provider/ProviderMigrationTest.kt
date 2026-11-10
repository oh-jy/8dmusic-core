package j5i.spatial8d.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderMigrationTest {
    @Test fun removesOnlyIdentifiedOnlineReferences() {
        val songs = """[{"id":1,"uri":"content://media/1","album":"Local"},{"id":2,"uri":"https://example.com/a"},{"id":3,"uri":"file:///data/online.audio","album":"在线收藏"}]"""
        val (favorites, playlists) = ProviderMigration.filteredReferences(songs, "[]", "[1,2,3,4]", """[{"name":"keep","ids":[1,2,3,4],"rule":""}]""")
        assertEquals("[1,4]", favorites.toString())
        assertEquals("[1,4]", playlists.getJSONObject(0).getJSONArray("ids").toString())
    }
}
