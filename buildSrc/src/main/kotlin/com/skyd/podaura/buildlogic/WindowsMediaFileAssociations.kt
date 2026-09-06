package com.skyd.podaura.buildlogic

import com.skyd.podaura.media.MediaTypes
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

fun windowsMediaFileAssociations(): Map<String, String> = MediaTypes.playableExtensions.associateWith {
    when (it) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "m4a", "alac" -> "audio/mp4"
        "wma" -> "audio/x-ms-wma"
        "opus" -> "audio/ogg"
        "aiff", "aif" -> "audio/aiff"
        "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "mpg", "mpeg" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "rmvb" -> "application/vnd.rn-realmedia-vbr"
        "ts" -> "video/mp2t"
        else -> error("Missing Windows media MIME type for $it")
    }
}

/** Preserve the MSIX plugin's identity, capabilities and generated icon resource paths. */
fun addWindowsMediaFileAssociations(manifest: File) {
    val foundation = "http://schemas.microsoft.com/appx/manifest/foundation/windows10"
    val uap = "http://schemas.microsoft.com/appx/manifest/uap/windows10"
    val uap3 = "$uap/3"
    val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(manifest)
    val root = document.documentElement
    root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:uap3", uap3)
    val ignored = root.getAttribute("IgnorableNamespaces").split(Regex("\\s+"))
        .filter { it.isNotBlank() }.toMutableSet()
    ignored += "uap3"
    root.setAttribute("IgnorableNamespaces", ignored.joinToString(" "))
    val application = document.getElementsByTagNameNS(foundation, "Application").item(0) as Element
    val extensions = (application.getElementsByTagNameNS(foundation, "Extensions").item(0) as? Element)
        ?: document.createElementNS(foundation, "Extensions").also(application::appendChild)
    // Make repeated manifest generation idempotent without touching unrelated extensions.
    val existing = extensions.getElementsByTagNameNS(uap, "Extension")
    (0 until existing.length).map { existing.item(it) as Element }.filter {
        it.getAttribute("Category") == "windows.fileTypeAssociation" &&
            it.getElementsByTagNameNS(uap3, "FileTypeAssociation").let { associations ->
                (0 until associations.length).any { index ->
                    (associations.item(index) as Element).getAttribute("Name") == "mediafiles"
                }
            }
    }.forEach(extensions::removeChild)

    val extension = document.createElementNS(uap, "uap:Extension").apply {
        setAttribute("Category", "windows.fileTypeAssociation")
    }
    extensions.appendChild(extension)
    val association = document.createElementNS(uap3, "uap3:FileTypeAssociation").apply {
        setAttribute("Name", "mediafiles")
        setAttribute("Parameters", "\"%1\"")
        setAttribute("MultiSelectModel", "Player")
    }
    extension.appendChild(association)
    val fileTypes = document.createElementNS(uap, "uap:SupportedFileTypes")
    association.appendChild(fileTypes)
    MediaTypes.playableExtensions.forEach { extensionName ->
        fileTypes.appendChild(document.createElementNS(uap, "uap:FileType").apply {
            textContent = ".$extensionName"
        })
    }
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    }.transform(DOMSource(document), StreamResult(manifest))
}
