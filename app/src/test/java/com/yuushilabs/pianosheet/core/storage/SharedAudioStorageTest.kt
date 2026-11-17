package com.yuushilabs.pianosheet.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 0.4.1: recordings must land in a public folder that outlives the app.
 *
 * The MediaStore itself is not available in a unit test, so the gateway is
 * faked; what is checked here is the policy that would otherwise only show up
 * as a wrong path on the device: names, folders, fallbacks and the import.
 */
class SharedAudioStorageTest {

    @get:Rule
    val temporary = TemporaryFolder()

    // ── names and paths ────────────────────────────────────────────────────

    @Test
    fun `a take is named by the capture time and the project id`() {
        val name = SharedAudioStorage.audioFileName("9f3c1a2b-77d4-4c1e-9b0a-5e5f6a7b8c9d", 1_763_000_000_000L)
        assertTrue(name.matches(Regex("rec-\\d{8}-\\d{6}-9f3c1a2b\\.wav")))
        assertTrue(name.endsWith(".wav"))
    }

    @Test
    fun `two takes of the same second still differ by project`() {
        val first = SharedAudioStorage.audioFileName("aaaaaaaa-1", 1_763_000_000_000L)
        val second = SharedAudioStorage.audioFileName("bbbbbbbb-2", 1_763_000_000_000L)
        assertTrue(first != second)
    }

    @Test
    fun `a project id is reduced to filename safe characters`() {
        val name = SharedAudioStorage.audioFileName("../../AB/CD:EF", 1_763_000_000_000L)
        assertFalse(name.contains("/"))
        assertTrue(name.matches(Regex("rec-\\d{8}-\\d{6}-abcdef\\.wav")))
    }

    @Test
    fun `the displayed path is the full public path`() {
        assertEquals(
            "/storage/emulated/0/Music/PianoSheet",
            SharedAudioStorage.folderPath(),
        )
        assertEquals(
            "/storage/emulated/0/Music/PianoSheet/rec-20261117-211530-9f3c1a2b.wav",
            SharedAudioStorage.displayPath(
                SharedAudioStorage.MUSIC_FOLDER,
                "rec-20261117-211530-9f3c1a2b.wav",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Documents/PianoSheet",
            SharedAudioStorage.folderPath(SharedAudioStorage.DOCUMENTS_FOLDER),
        )
    }

    @Test
    fun `an exported score keeps its title and extension with a minute stamp`() {
        val name = SharedAudioStorage.documentFileName("録音 11/17 21:15.musicxml", 1_763_000_000_000L)
        assertTrue(name.startsWith("録音-11-17-21-15-"))
        assertTrue(name.endsWith(".musicxml"))
        assertFalse(name.contains('/'))
    }

    @Test
    fun `an exported score never loses its extension`() {
        assertTrue(SharedAudioStorage.documentFileName("PianoSheet", 1_763_000_000_000L).endsWith(".txt"))
        val midi = SharedAudioStorage.documentFileName("name.mid", 1_763_000_000_000L)
        assertTrue(midi.startsWith("name-"))
        assertTrue(midi.endsWith(".mid"))
    }

    // ── saving a take ──────────────────────────────────────────────────────

    @Test
    fun `a take is copied into Music PianoSheet as audio`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)
        val source = wavFile("take.wav", bytes = 2_048)

        val saved = storage.saveRecording(source, "9f3c1a2b-77d4", capturedAt = 1_763_000_000_000L).getOrThrow()

        assertEquals(1, gateway.requests.size)
        val request = gateway.requests.first()
        assertEquals(SharedCollection.MUSIC, request.collection)
        assertEquals("Music/PianoSheet", request.relativeFolder)
        assertEquals("audio/wav", request.mimeType)
        assertTrue(request.fileName.startsWith("rec-"))
        assertEquals(2_048L, saved.bytes)
        assertEquals(2_048, gateway.written?.size)
        assertTrue(saved.displayPath.startsWith("/storage/emulated/0/Music/PianoSheet/rec-"))
        assertTrue(source.exists())
    }

    @Test
    fun `a missing file is not written anywhere`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)
        val missing = File(temporary.root, "not-here.wav")

        val result = storage.saveRecording(missing, "project")

        assertTrue(result.isFailure)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `an empty take is not written anywhere`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)
        val empty = File(temporary.root, "empty.wav").apply { writeBytes(ByteArray(0)) }

        val result = storage.saveRecording(empty, "project")

        assertTrue(result.isFailure)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `a refused audio collection falls back to a plain file row in the same folder`() {
        val gateway = FakeGateway(failures = 1)
        val storage = SharedAudioStorage(gateway)
        val source = wavFile("take.wav", bytes = 512)

        val saved = storage.saveRecording(source, "project", capturedAt = 1_763_000_000_000L).getOrThrow()

        assertEquals(2, gateway.requests.size)
        assertEquals(SharedCollection.MUSIC, gateway.requests[0].collection)
        assertEquals(SharedCollection.DOCUMENT, gateway.requests[1].collection)
        assertEquals("Music/PianoSheet", gateway.requests[1].relativeFolder)
        assertEquals(gateway.requests[0].fileName, gateway.requests[1].fileName)
        assertEquals(512L, saved.bytes)
    }

    @Test
    fun `a shared write that fails everywhere leaves the recording intact`() {
        val gateway = FakeGateway(failures = 2)
        val storage = SharedAudioStorage(gateway)
        val source = wavFile("take.wav", bytes = 1_024)

        val result = storage.saveRecording(source, "project")

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("共有フォルダに保存できませんでした"))
        // The working copy is the whole point of the fallback.
        assertTrue(source.exists())
        assertEquals(1_024L, source.length())
    }

    // ── saving a score export ──────────────────────────────────────────────

    @Test
    fun `an export is written into Documents PianoSheet with its own type`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)

        val saved = storage.saveDocument(
            bytes = "<score-partwise/>".toByteArray(),
            fileName = "録音.musicxml",
            mimeType = "application/vnd.recordare.musicxml+xml",
            savedAt = 1_763_000_000_000L,
        ).getOrThrow()

        val request = gateway.requests.single()
        assertEquals(SharedCollection.DOCUMENT, request.collection)
        assertEquals("Documents/PianoSheet", request.relativeFolder)
        assertEquals("application/vnd.recordare.musicxml+xml", request.mimeType)
        assertTrue(request.fileName.endsWith(".musicxml"))
        assertTrue(saved.displayPath.startsWith("/storage/emulated/0/Documents/PianoSheet/"))
    }

    @Test
    fun `an export whose type the store rejects is retried as a generic file`() {
        val gateway = FakeGateway(failures = 1)
        val storage = SharedAudioStorage(gateway)

        storage.saveDocument(byteArrayOf(1, 2, 3), "PianoSheet.mid", "audio/midi").getOrThrow()

        assertEquals(2, gateway.requests.size)
        assertEquals("audio/midi", gateway.requests[0].mimeType)
        assertEquals("application/octet-stream", gateway.requests[1].mimeType)
        assertEquals(gateway.requests[0].fileName, gateway.requests[1].fileName)
    }

    @Test
    fun `nothing is written for an empty export`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)

        assertTrue(storage.saveDocument(ByteArray(0), "PianoSheet.musicxml", "text/xml").isFailure)
        assertTrue(gateway.requests.isEmpty())
    }

    // ── reading a saved take back in ───────────────────────────────────────

    @Test
    fun `a saved WAV is copied back into app storage`() {
        val wav = wavBytes(1_000)
        val gateway = FakeGateway(inputs = mutableMapOf("content://take" to wav))
        val storage = SharedAudioStorage(gateway)
        val directory = File(temporary.root, "recordings")

        val file = storage.importRecording("content://take", "9f3c1a2b-77d4", directory).getOrThrow()

        assertEquals("import-9f3c1a2b.wav", file.name)
        assertEquals(directory.absolutePath, file.parentFile.absolutePath)
        assertArrayEquals(wav, file.readBytes())
    }

    @Test
    fun `something that is not a WAV is refused and leaves no partial file`() {
        val gateway = FakeGateway(inputs = mutableMapOf("content://song" to "ID3 mp3 data".toByteArray()))
        val storage = SharedAudioStorage(gateway)
        val directory = File(temporary.root, "recordings")

        val result = storage.importRecording("content://song", "project", directory)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("WAV ファイルではありません"))
        assertFalse(File(directory, "import-project.wav").exists())
    }

    @Test
    fun `a short read at the header is refused`() {
        val gateway = FakeGateway(inputs = mutableMapOf("content://short" to byteArrayOf('R'.code.toByte())))
        val storage = SharedAudioStorage(gateway)
        val directory = File(temporary.root, "recordings")

        assertTrue(storage.importRecording("content://short", "project", directory).isFailure)
        assertFalse(File(directory, "import-project.wav").exists())
    }

    @Test
    fun `an unreadable document is reported without touching the folder`() {
        val gateway = FakeGateway()
        val storage = SharedAudioStorage(gateway)
        val directory = File(temporary.root, "recordings")

        val result = storage.importRecording("content://gone", "project", directory)

        assertTrue(result.isFailure)
        assertFalse(directory.exists())
    }

    @Test
    fun `only a RIFF WAVE header counts as a WAV`() {
        assertTrue(SharedAudioStorage.looksLikeWav(wavBytes(64)))
        assertFalse(SharedAudioStorage.looksLikeWav("RIFFxxxxAVI ".toByteArray(Charsets.US_ASCII)))
        assertFalse(SharedAudioStorage.looksLikeWav(ByteArray(4)))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun wavFile(name: String, bytes: Int): File =
        File(temporary.root, name).apply { writeBytes(wavBytes(bytes)) }

    private fun wavBytes(size: Int): ByteArray {
        val content = ByteArray(size.coerceAtLeast(44))
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(content, 0)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(content, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(content, 12)
        return content
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }

    /** Records every request; fails the first [failures] writes. */
    private class FakeGateway(
        private val failures: Int = 0,
        private val inputs: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : SharedMediaGateway {

        val requests = mutableListOf<SharedWriteRequest>()
        var written: ByteArray? = null
        private var attempts = 0

        override fun write(request: SharedWriteRequest, openSource: () -> InputStream): SharedFile {
            requests += request
            val index = attempts++
            if (index < failures) throw IOException("collection refused ${request.collection}")
            val bytes = openSource().use { input -> input.readBytes() }
            if (bytes.isEmpty()) throw IOException("no bytes")
            written = bytes
            return SharedFile(
                uri = "content://media/external/file/$index",
                displayPath = SharedAudioStorage.displayPath(request.relativeFolder, request.fileName),
                fileName = request.fileName,
                bytes = bytes.size.toLong(),
                collection = request.collection,
            )
        }

        override fun openInput(uri: String): InputStream? =
            inputs[uri]?.let { ByteArrayInputStream(it) }

        override fun displayName(uri: String): String? = if (inputs.containsKey(uri)) "take.wav" else null
    }
}
