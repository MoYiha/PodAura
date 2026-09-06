package com.skyd.podaura.buildlogic

import com.skyd.podaura.media.MediaTypes
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsMediaFileAssociationsTest {
    @Test
    fun msiAssociationsCoverExactlyTheSharedPlayableCatalog() {
        val associations = windowsMediaFileAssociations()
        assertEquals(MediaTypes.playableExtensions, associations.keys)
        assertEquals("audio/mpeg", associations["mp3"])
        assertEquals("video/mp4", associations["mp4"])
        assertTrue(associations.values.none { it.isBlank() })
    }

    @Test
    fun msixAssociationsPreserveTheManifestAndSupportQuotedMultiFileActivation() {
        val manifest = Files.createTempFile("podaura-manifest", ".xml").toFile()
        try {
            manifest.writeText("""
                <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                    xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
                    IgnorableNamespaces="uap">
                    <Identity Name="SkyD666.PodAura" />
                    <Applications><Application Id="PodAura" Executable="PodAura.exe"
                        EntryPoint="Windows.FullTrustApplication">
                        <Extensions><uap:Extension Category="windows.protocol">
                            <uap:Protocol Name="podaura" />
                        </uap:Extension></Extensions>
                    </Application></Applications>
                </Package>
            """.trimIndent())
            repeat(2) { addWindowsMediaFileAssociations(manifest) }
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(manifest)
            val uap = "http://schemas.microsoft.com/appx/manifest/uap/windows10"
            val associations = document.getElementsByTagNameNS("$uap/3", "FileTypeAssociation")
            assertEquals(1, associations.length)
            val association = associations.item(0) as org.w3c.dom.Element
            assertEquals("\"%1\"", association.getAttribute("Parameters"))
            assertEquals("Player", association.getAttribute("MultiSelectModel"))
            val files = document.getElementsByTagNameNS(uap, "FileType")
            assertEquals(MediaTypes.playableExtensions.map { ".$it" },
                (0 until files.length).map { files.item(it).textContent })
            assertEquals(1, document.getElementsByTagNameNS(uap, "Protocol").length)
            assertTrue(document.documentElement.getAttribute("IgnorableNamespaces").contains("uap3"))
            assertEquals("PodAura.exe", (document.getElementsByTagNameNS(
                "http://schemas.microsoft.com/appx/manifest/foundation/windows10", "Application",
            ).item(0) as org.w3c.dom.Element).getAttribute("Executable"))
        } finally {
            manifest.delete()
        }
    }
}
