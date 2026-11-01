package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
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

    @Test
    fun `a gzipped mark should be refused`() {
        // The renderer unpacks gzip by itself, so the image inside would never have been seen.
        val gzipped = ByteArrayOutputStream()
            .also { output -> GZIPOutputStream(output).use { it.write(UNSAFE_SVG.toByteArray()) } }
            .toByteArray()

        assertThat(isSafeMark(gzipped)).isFalse()
    }

    @Test
    fun `a mark in UTF-16 or UTF-32 should be refused`() {
        assertThat(isSafeMark(UNSAFE_SVG.toByteArray(Charsets.UTF_16))).isFalse()
        assertThat(isSafeMark(UNSAFE_SVG.toByteArray(Charsets.UTF_16LE))).isFalse()
        assertThat(isSafeMark(UNSAFE_SVG.toByteArray(Charset.forName("UTF-32BE")))).isFalse()
    }

    @Test
    fun `a mark declaring an encoding other than UTF-8 should be refused`() {
        val svg = """<?xml version="1.0" encoding="UTF-7"?><svg xmlns="http://www.w3.org/2000/svg">""" +
            """+ADw-image href="data:image/png;base64,AAAA"/+AD4-</svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isFalse()
    }

    @Test
    fun `a mark declared as UTF-8 should be accepted, with or without a byte order mark`() {
        val svg = """<?xml version="1.0" encoding="utf-8"?><svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>"""

        assertThat(isSafeMark(svg.toByteArray())).isTrue()
        assertThat(isSafeMark("﻿$svg".toByteArray())).isTrue()
    }

    private companion object {
        const val UNSAFE_SVG = """<svg xmlns="http://www.w3.org/2000/svg">""" +
            """<image href="data:image/png;base64,AAAA"/></svg>"""
    }
}
