package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class SafeMarkTest {

    @Test
    fun `a plain vector mark should be accepted`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" version="1.2" baseProfile="tiny-ps">
            <title>Example</title><rect width="10" height="10" fill="#123456"/></svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isTrue()
    }

    @Test
    fun `an embedded raster image should be refused`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><image href="data:image/png;base64,AAAA"/></svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isFalse()
    }

    @Test
    fun `a namespaced image element should be refused`() {
        val svg = """<svg:svg xmlns:svg="http://www.w3.org/2000/svg"><svg:IMAGE xlink:href="x.png"/></svg:svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isFalse()
    }

    @Test
    fun `an element that merely starts with image should be accepted`() {
        // Only the image element itself decodes a raster; a longer name is some other element.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><imagery/></svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isTrue()
    }

    @Test
    fun `a document type declaration should be refused`() {
        val svg = """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY a "aaaaaaaaaa">]><svg>&a;</svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isFalse()
    }
}
