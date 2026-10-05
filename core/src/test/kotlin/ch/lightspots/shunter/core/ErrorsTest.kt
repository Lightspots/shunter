package ch.lightspots.shunter.core

import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import kotlin.test.assertEquals

class ErrorsTest {

    @Test
    fun `file system exceptions say what went wrong`() {
        assertEquals("Access denied: /a", AccessDeniedException("/a").readableMessage())
        assertEquals("Access denied: /a -> /b", AccessDeniedException("/a", "/b", null).readableMessage())
        assertEquals("Not found: /a", NoSuchFileException("/a").readableMessage())
        assertEquals("FileAlreadyExistsException: /a", FileAlreadyExistsException("/a").readableMessage())
    }

    @Test
    fun `other messages are kept`() {
        assertEquals("/a: Disk full", FileSystemException("/a", null, "Disk full").readableMessage())
        assertEquals("broken", IOException("broken").readableMessage())
        assertEquals("java.io.IOException", IOException().readableMessage())
    }
}
