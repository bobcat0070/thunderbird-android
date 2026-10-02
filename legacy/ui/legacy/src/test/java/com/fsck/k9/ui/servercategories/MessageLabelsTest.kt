package com.fsck.k9.ui.servercategories

import android.content.Context
import android.text.Spanned
import android.view.ContextThemeWrapper
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.ui.R
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class MessageLabelsTest : RobolectricTest() {
    private val context: Context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        com.google.android.material.R.style.Theme_Material3_Light,
    )

    @Test
    fun `a message of normal importance without categories should have no labels`() {
        val result = MessageLabels.build(context, MessageImportance.NORMAL, emptyList())

        assertThat(result).isNull()
    }

    @Test
    fun `high importance should be spelled out`() {
        val result = MessageLabels.build(context, MessageImportance.HIGH, emptyList())

        assertThat(result.toString()).isEqualTo(context.getString(R.string.message_importance_high))
    }

    @Test
    fun `low importance should be spelled out`() {
        val result = MessageLabels.build(context, MessageImportance.LOW, emptyList())

        assertThat(result.toString()).isEqualTo(context.getString(R.string.message_importance_low))
    }

    @Test
    fun `every category should get a label`() {
        val categories = listOf("One", "Two", "Three", "Four", "Five")

        val result = MessageLabels.build(context, MessageImportance.NORMAL, categories) as Spanned

        val labels = result.getSpans(0, result.length, ServerCategoryChipSpan::class.java)
            .map { span -> result.substring(result.getSpanStart(span), result.getSpanEnd(span)) }
        assertThat(labels).containsExactly("One", "Two", "Three", "Four", "Five")
    }

    @Test
    fun `importance should come before the categories`() {
        val result = MessageLabels.build(context, MessageImportance.HIGH, listOf("Red category"))

        assertThat(result.toString())
            .isEqualTo(context.getString(R.string.message_importance_high) + "  Red category")
    }
}
