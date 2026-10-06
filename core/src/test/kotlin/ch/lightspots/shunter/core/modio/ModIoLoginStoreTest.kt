package ch.lightspots.shunter.core.modio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModIoLoginStoreTest {

    @TempDir
    lateinit var tmp: Path

    private val store by lazy { ModIoLoginStore(tmp.resolve("config/modio-login.json")) }

    @Test
    fun `stores the login readable only by the user`() {
        assertNull(store.load())

        store.save(ModIoLogin("secret", "Anna"))
        store.save(ModIoLogin("secret2", "Anna"))

        assertEquals(ModIoLogin("secret2", "Anna"), store.load())
        assertEquals(PosixFilePermissions.fromString("rw-------"), store.file.getPosixFilePermissions())
        assertEquals(listOf(store.file), store.file.parent.listDirectoryEntries(), "no temporary file left")
    }

    @Test
    fun `a broken or empty file counts as signed out`() {
        store.save(ModIoLogin("secret"))
        store.file.writeText("{ not json")
        assertNull(store.load())

        store.file.writeText("""{"accessToken": " "}""")
        assertNull(store.load())
    }

    @Test
    fun `toString leaves the token out`() {
        assertFalse("secret" in ModIoLogin("secret", "Anna").toString())
    }
}
