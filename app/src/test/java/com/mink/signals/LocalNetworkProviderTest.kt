package com.mink.signals

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [LocalNetworkProvider.serviceLabel], the DNS-SD type ->
 * friendly label map. No Android framework is touched (the async discovery
 * itself is exercised only under androidTest), so these run on the plain JVM
 * under testDebugUnitTest.
 */
class LocalNetworkProviderTest {

    @Test
    fun serviceLabel_mapsTheKeyServiceTypes() {
        assertEquals("Chromecast or Google TV", LocalNetworkProvider.serviceLabel("_googlecast._tcp"))
        assertEquals("AirPlay", LocalNetworkProvider.serviceLabel("_airplay._tcp"))
        assertEquals("AirPlay", LocalNetworkProvider.serviceLabel("_raop._tcp"))
        assertEquals("Sonos", LocalNetworkProvider.serviceLabel("_sonos._tcp"))
        assertEquals("Spotify Connect", LocalNetworkProvider.serviceLabel("_spotify-connect._tcp"))
        assertEquals("HomeKit accessory", LocalNetworkProvider.serviceLabel("_hap._tcp"))
        assertEquals("Philips Hue", LocalNetworkProvider.serviceLabel("_hue._tcp"))
        assertEquals("Fire TV", LocalNetworkProvider.serviceLabel("_amzn-wplay._tcp"))
        assertEquals("NVIDIA Shield", LocalNetworkProvider.serviceLabel("_nvstream._tcp"))
        assertEquals("Computer", LocalNetworkProvider.serviceLabel("_workstation._tcp"))
    }

    @Test
    fun serviceLabel_collapsesTypeFamiliesOntoOneLabel() {
        // Matter commissioning and operational both read as one device.
        assertEquals("Matter device", LocalNetworkProvider.serviceLabel("_matterc._udp"))
        assertEquals("Matter device", LocalNetworkProvider.serviceLabel("_matter._tcp"))
        // The four print protocols all read as a printer.
        assertEquals("Printer", LocalNetworkProvider.serviceLabel("_ipp._tcp"))
        assertEquals("Printer", LocalNetworkProvider.serviceLabel("_ipps._tcp"))
        assertEquals("Printer", LocalNetworkProvider.serviceLabel("_printer._tcp"))
        assertEquals("Printer", LocalNetworkProvider.serviceLabel("_pdl-datastream._tcp"))
        // Scanners, file shares, SSH hosts, Apple gear, and web devices likewise.
        assertEquals("Scanner", LocalNetworkProvider.serviceLabel("_uscan._tcp"))
        assertEquals("Scanner", LocalNetworkProvider.serviceLabel("_uscans._tcp"))
        assertEquals("Scanner", LocalNetworkProvider.serviceLabel("_scanner._tcp"))
        assertEquals("File share", LocalNetworkProvider.serviceLabel("_smb._tcp"))
        assertEquals("File share", LocalNetworkProvider.serviceLabel("_afpovertcp._tcp"))
        assertEquals("SSH host", LocalNetworkProvider.serviceLabel("_ssh._tcp"))
        assertEquals("SSH host", LocalNetworkProvider.serviceLabel("_sftp-ssh._tcp"))
        assertEquals("Apple device", LocalNetworkProvider.serviceLabel("_companion-link._tcp"))
        assertEquals("Apple device", LocalNetworkProvider.serviceLabel("_apple-mobdev2._tcp"))
        assertEquals("Web device", LocalNetworkProvider.serviceLabel("_http._tcp"))
        assertEquals("Web device", LocalNetworkProvider.serviceLabel("_https._tcp"))
    }

    @Test
    fun serviceLabel_normalizesTrailingDotAndCaseBeforeLookup() {
        // NsdManager often hands the type back with a trailing dot, and DNS-SD is
        // case-insensitive; both are normalized before the map lookup.
        assertEquals("Chromecast or Google TV", LocalNetworkProvider.serviceLabel("_googlecast._tcp."))
        assertEquals("Chromecast or Google TV", LocalNetworkProvider.serviceLabel("  _googlecast._tcp  "))
        assertEquals("Chromecast or Google TV", LocalNetworkProvider.serviceLabel("_GOOGLECAST._TCP"))
    }

    @Test
    fun serviceLabel_fallsBackToTheCleanedTypeWhenUnknown() {
        // An unrecognized type comes back as itself, trimmed of any trailing dot
        // but with its original casing preserved.
        assertEquals("_unknownservice._tcp", LocalNetworkProvider.serviceLabel("_unknownservice._tcp"))
        assertEquals("_unknownservice._tcp", LocalNetworkProvider.serviceLabel("_unknownservice._tcp."))
        assertEquals("_MyService._tcp", LocalNetworkProvider.serviceLabel("_MyService._tcp"))
    }
}
