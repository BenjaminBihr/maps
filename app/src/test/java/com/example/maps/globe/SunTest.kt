package com.example.maps.globe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SunTest {

    private fun subsolar(utc: String) = Sun.subsolarPoint(Instant.parse(utc).toEpochMilli())

    @Test
    fun `sun is over the Tropic of Cancer at the June solstice`() {
        assertEquals(23.44, subsolar("2024-06-20T20:51:00Z").lat, 0.05)
    }

    @Test
    fun `sun is over the Tropic of Capricorn at the December solstice`() {
        assertEquals(-23.44, subsolar("2024-12-21T09:21:00Z").lat, 0.05)
    }

    @Test
    fun `sun is over the equator at the March equinox`() {
        assertEquals(0.0, subsolar("2024-03-20T03:06:00Z").lat, 0.05)
    }

    @Test
    fun `noon UTC is shifted by the equation of time`() {
        // Sundials run ~7.5 min slow on March 20: the sun has not reached Greenwich yet.
        assertEquals(1.85, subsolar("2024-03-20T12:00:00Z").lon, 0.3)
        // ...and ~16.4 min fast on November 3: it is already past Greenwich.
        assertEquals(-4.1, subsolar("2024-11-03T12:00:00Z").lon, 0.3)
    }

    @Test
    fun `sun goes round westward once a day`() {
        assertEquals(-90.0, subsolar("2024-03-20T18:07:00Z").lon, 0.5)
        assertEquals(90.0, subsolar("2024-03-20T06:07:00Z").lon, 0.5)
    }
}
