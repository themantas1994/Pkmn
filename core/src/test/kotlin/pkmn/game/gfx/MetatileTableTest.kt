package pkmn.game.gfx

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetatileTableTest {
    // A 2x1 sheet of 2x2-pixel tiles: tile 0 = pixels 1,2/3,4 and tile 1 = 5,6/7,8 (sheet is 4 wide).
    private val sheet = intArrayOf(1, 2, 5, 6, 3, 4, 7, 8)

    private fun compose(vararg cells: Int) = MetatileTable.compose(sheet, 4, 2, 2, cells)

    @Test fun composesFourCellsInReadingOrder() {
        assertContentEquals(intArrayOf(1, 2, 5, 6, 3, 4, 7, 8, 5, 6, 1, 2, 7, 8, 3, 4), compose(0, 1, 1, 0))
    }

    @Test fun flipBitsMirrorACell() {
        val h = MetatileTable.FLIP_H; val v = MetatileTable.FLIP_V
        // tile 0 is 1,2/3,4: TL mirrored horizontally, TR vertically, BL as is, BR both ways
        assertContentEquals(intArrayOf(2, 1, 3, 4, 4, 3, 1, 2, 1, 2, 4, 3, 3, 4, 2, 1), compose(0 or h, 0 or v, 0, 0 or h or v))
    }

    @Test fun emptyCellsStayTransparent() {
        val px = compose(MetatileTable.EMPTY, 0, MetatileTable.EMPTY, MetatileTable.EMPTY)
        assertEquals(listOf(0, 0, 1, 2), px.take(4))
        assertEquals(listOf(0, 0, 3, 4), px.slice(4 until 8))
        assertTrue(px.drop(8).all { it == 0 })
    }

    @Test fun parsesTypesTopAndFrames() {
        val t = MetatileTable.parse("""{"tileSize":8,"metatileSize":16,"columns":32,"metatiles":{
            "a":{"ground":[0,1,2,3],"type":"normal","palette":"p"},
            "b":{"ground":[0,0,0,0],"top":[4,-1,-1,5],"type":"covered"},
            "c":{"ground":[0,0,0,0],"frames":[[0,0,0,0],[1,1,1,1]],"frameMs":100,"type":"normal"}}}""")
        assertEquals(MetatileType.COVERED, t.metatiles.getValue("b").type)
        assertContentEquals(intArrayOf(4, -1, -1, 5), t.metatiles.getValue("b").top)
        assertNull(t.metatiles.getValue("a").top)
        assertEquals(2, t.metatiles.getValue("c").bottomFrames.size)
        assertEquals(100, t.metatiles.getValue("c").frameMs)
    }

    @Test fun typesAssignLayers() {
        assertEquals(MapLayer.OBJECTS to MapLayer.OVERHANG, MetatileType.SPLIT.bottom to MetatileType.SPLIT.top)
        assertEquals(MapLayer.GROUND to MapLayer.OBJECTS, MetatileType.NORMAL.bottom to MetatileType.NORMAL.top)
        assertEquals(MapLayer.GROUND to MapLayer.OVERHANG, MetatileType.COVERED.bottom to MetatileType.COVERED.top)
    }

    @Test fun bundledTableIsConsistentWithItsSheet() {
        val assets = File(System.getProperty("pkmn.assets") ?: return)
        val table = MetatileTable.parse(File(assets, "gfx/tiles/world.json").readText())
        val img = ImageIO.read(File(assets, "gfx/tiles/world.png"))
        val tiles = img.width / table.tileSize * (img.height / table.tileSize)
        assertEquals(img.width, table.columns * table.tileSize)
        for ((name, d) in table.metatiles) {
            for (cells in d.bottomFrames + listOfNotNull(d.top)) for (ref in cells)
                assertTrue(ref == MetatileTable.EMPTY || (ref and MetatileTable.INDEX_MASK) < tiles, "$name: tile $ref out of range")
        }
    }
}
