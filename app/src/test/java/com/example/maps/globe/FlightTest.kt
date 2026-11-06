package com.example.maps.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightTest {

    @Test
    fun `starts where the camera is and ends on the destination`() {
        val flight = Flight(10.0, 20.0, 1f, -30.0, 140.0, 2.2f)
        val start = flight.sample()
        assertEquals(10.0, start.lat, 1e-9)
        assertEquals(20.0, start.lon, 1e-9)
        assertEquals(1f, start.zoom, 1e-6f)

        flight.advance(100.0)
        val end = flight.sample()
        assertTrue(flight.finished)
        assertEquals(-30.0, end.lat, 1e-6)
        assertEquals(140.0, end.lon, 1e-6)
        assertEquals(2.2f, end.zoom, 1e-5f)
    }

    @Test
    fun `follows the great circle instead of a straight lat lon line`() {
        // (45N, 0) -> (45N, 180) is a 90 degree hop straight over the North Pole.
        val flight = Flight(45.0, 0.0, 1f, 45.0, 180.0, 1f)
        flight.advance(flight.durationSeconds / 2)
        assertEquals(90.0, flight.sample().lat, 1e-6)
    }

    @Test
    fun `long hops pull the zoom back mid flight, short ones barely`() {
        val long = Flight(0.0, 0.0, 2.2f, 0.0, 120.0, 2.2f).apply { advance(durationSeconds / 2) }
        val short = Flight(0.0, 0.0, 2.2f, 0.0, 5.0, 2.2f).apply { advance(durationSeconds / 2) }
        assertTrue(long.sample().zoom < 2.2f * 0.7f)
        assertTrue(short.sample().zoom > 2.2f * 0.95f)
    }

    @Test
    fun `dive climbs to see the whole globe, turns, then plunges onto the target`() {
        val flight = Flight(0.0, 0.0, 2f, 40.0, 60.0, 4.5f, Flight.Style.DIVE)

        flight.advance(flight.durationSeconds * Flight.DIVE_CLIMB_END)
        assertEquals(Flight.DIVE_ALTITUDE_ZOOM, flight.sample().zoom, 1e-4f)

        flight.advance(flight.durationSeconds * (Flight.DIVE_TURN_END - Flight.DIVE_CLIMB_END) + 1e-9)
        val turned = flight.sample()
        assertEquals(40.0, turned.lat, 1e-6)
        assertEquals(60.0, turned.lon, 1e-6)
        assertTrue(turned.zoom < 4.5f)

        flight.advance(100.0)
        assertEquals(4.5f, flight.sample().zoom, 1e-4f)
    }

    @Test
    fun `dive from far out never zooms in before plunging`() {
        val flight = Flight(0.0, 0.0, 0.8f, 10.0, 10.0, 4.5f, Flight.Style.DIVE)
        flight.advance(flight.durationSeconds * Flight.DIVE_CLIMB_END)
        assertEquals(0.8f, flight.sample().zoom, 1e-4f)
    }

    @Test
    fun `longer hops take longer`() {
        assertTrue(Flight(0.0, 0.0, 1f, 0.0, 150.0, 1f).durationSeconds > Flight(0.0, 0.0, 1f, 0.0, 10.0, 1f).durationSeconds)
    }
}
