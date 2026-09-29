package app.k9mail.feature.widget.message.list

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.matches
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidgetPictureFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the same picture should be stored as the same file`() {
        val directory = temporaryFolder.newFolder()

        val first = storePicture(directory, byteArrayOf(1, 2, 3), now = 1_000L)
        val testSubject = storePicture(directory, byteArrayOf(1, 2, 3), now = 2_000L)

        assertThat(testSubject).isEqualTo(first)
        assertThat(testSubject.readBytes().toList()).isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `a different picture should be stored as a different file`() {
        val directory = temporaryFolder.newFolder()

        val first = storePicture(directory, byteArrayOf(1, 2, 3), now = 1_000L)
        val testSubject = storePicture(directory, byteArrayOf(4, 5, 6), now = 1_000L)

        assertThat(testSubject).isNotEqualTo(first)
    }

    @Test
    fun `a picture's file should be named after its content only`() {
        val directory = temporaryFolder.newFolder()

        val testSubject = storePicture(directory, byteArrayOf(1, 2, 3), now = 1_000L)

        assertThat(testSubject.name).matches(Regex("[0-9a-f]{64}\\.png"))
    }

    @Test
    fun `storing a picture should create its directory`() {
        val directory = File(temporaryFolder.root, "widget_pictures")

        val testSubject = storePicture(directory, byteArrayOf(1, 2, 3), now = 1_000L)

        assertThat(testSubject.parentFile).isEqualTo(directory)
        assertThat(testSubject.exists()).isEqualTo(true)
    }

    @Test
    fun `pictures neither stored nor reused since the cutoff should be removed`() {
        val directory = temporaryFolder.newFolder()
        val unused = storePicture(directory, byteArrayOf(1), now = 1_000L)
        val reused = storePicture(directory, byteArrayOf(2), now = 1_000L)
        storePicture(directory, byteArrayOf(2), now = 5_000L)
        val recent = storePicture(directory, byteArrayOf(3), now = 5_000L)

        removePicturesOlderThan(directory, cutoff = 3_000L)

        assertThat(directory.listFiles()!!.toList()).containsExactlyInAnyOrder(reused, recent)
        assertThat(unused.exists()).isEqualTo(false)
    }
}
