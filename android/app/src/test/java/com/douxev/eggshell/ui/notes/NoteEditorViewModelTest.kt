package com.douxev.eggshell.ui.notes

import com.douxev.eggshell.data.NoteArchiver
import com.douxev.eggshell.data.NoteStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.transition.Note
import uniffi.transition.NoteImage

/**
 * Regression tests for "creating a note doesn't work".
 *
 * Three separate faults produced that one report, and each has a test here:
 * a new note written inside a folder was filed at the root, a failed write was
 * indistinguishable from a successful one, and an untouched editor must still
 * leave nothing behind.
 *
 * The fourth — the system back gesture popping the screen without ever calling
 * [NoteEditorViewModel.save] — is wiring between the navigation host and the
 * composable, and cannot be reached from here. It is covered by the
 * `BackHandler` in `NoteEditorScreen`; catching a regression of it needs an
 * instrumented test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** An in-memory [NoteStore] that records what the editor asked it to do. */
    private class FakeNotes(private val failWrites: Boolean = false) : NoteStore {
        val notes = mutableMapOf<Long, Note>()
        var nextId = 1L
        var createCalls = 0

        override suspend fun get(id: Long): Note? = notes[id]

        override suspend fun create(title: String, body: String, folderId: Long?): Note {
            createCalls++
            if (failWrites) throw IllegalStateException("Vault not unlocked")
            val note = Note(
                id = nextId++,
                folderId = folderId,
                title = title,
                body = body,
                sortOrder = 0,
                createdMs = 0,
                updatedMs = 0,
            )
            notes[note.id] = note
            return note
        }

        override suspend fun update(id: Long, title: String, body: String): Note {
            if (failWrites) throw IllegalStateException("Vault not unlocked")
            val updated = (notes[id] ?: error("no note $id")).copy(title = title, body = body)
            notes[id] = updated
            return updated
        }

        override suspend fun delete(id: Long) { notes.remove(id) }
        override suspend fun images(noteId: Long): List<NoteImage> = emptyList()
        override suspend fun attachImage(noteId: Long, uri: android.net.Uri): NoteImage =
            error("not used by these tests")
        override suspend fun detachImage(image: NoteImage) = Unit
        override suspend fun decrypt(image: NoteImage): ByteArray = ByteArray(0)
    }

    private class FakeArchiver : NoteArchiver {
        override suspend fun exportToCache(noteIds: List<Long>): File =
            error("not used by these tests")
    }

    private fun viewModel(store: NoteStore) = NoteEditorViewModel(store, FakeArchiver())

    @Test
    fun `a new note with content is written on the way out`() = runTest(dispatcher) {
        val store = FakeNotes()
        val vm = viewModel(store)
        vm.load(id = null, folder = null)
        vm.onTitle("Rendez-vous")
        vm.onBody("endocrino, jeudi")

        assertTrue("saving content must succeed", vm.save())

        val written = store.notes.values.single()
        assertEquals("Rendez-vous", written.title)
        assertEquals("endocrino, jeudi", written.body)
    }

    /**
     * The bug: the editor was never told which folder it had been opened from,
     * so `create` took its `folderId = null` default and every note written
     * inside a folder landed at the root — invisible where it was written.
     */
    @Test
    fun `a new note is created in the folder the editor was opened from`() = runTest(dispatcher) {
        val store = FakeNotes()
        val vm = viewModel(store)
        vm.load(id = null, folder = 42L)
        vm.onTitle("dans le dossier")

        assertTrue(vm.save())

        assertEquals(42L, store.notes.values.single().folderId)
    }

    @Test
    fun `an untouched editor leaves nothing behind`() = runTest(dispatcher) {
        val store = FakeNotes()
        val vm = viewModel(store)
        vm.load(id = null, folder = null)

        assertTrue("backing out of a blank note is not a failure", vm.save())

        assertEquals(0, store.createCalls)
        assertTrue(store.notes.isEmpty())
    }

    /**
     * A refused write used to return a boolean nobody read, so the screen
     * closed exactly as if the note had been saved.
     */
    @Test
    fun `a refused write is reported rather than swallowed`() = runTest(dispatcher) {
        val store = FakeNotes(failWrites = true)
        val vm = viewModel(store)
        vm.load(id = null, folder = null)
        vm.onTitle("perdue ?")

        assertFalse("a failed write must not look like a save", vm.save())
        assertNotNull("the reason must reach the UI", vm.saveError.value)
        assertTrue(vm.saveError.value!!.contains("Vault not unlocked"))
    }

    /**
     * Saving twice — the header arrow, then the back gesture — must update the
     * note it already created, not pile up copies of it.
     */
    @Test
    fun `saving twice updates the note instead of duplicating it`() = runTest(dispatcher) {
        val store = FakeNotes()
        val vm = viewModel(store)
        vm.load(id = null, folder = null)
        vm.onTitle("une fois")
        assertTrue(vm.save())

        vm.onTitle("deux fois")
        assertTrue(vm.save())

        assertEquals(1, store.createCalls)
        assertEquals("deux fois", store.notes.values.single().title)
    }

    @Test
    fun `an existing note is updated in place`() = runTest(dispatcher) {
        val store = FakeNotes()
        val existing = store.create("avant", "corps", folderId = 7L)
        val vm = viewModel(store)
        vm.load(id = existing.id, folder = 7L)
        dispatcher.scheduler.advanceUntilIdle()

        vm.onTitle("après")
        assertTrue(vm.save())

        assertEquals(1, store.notes.size)
        assertEquals("après", store.notes.getValue(existing.id).title)
        assertNull(vm.saveError.value)
    }
}
