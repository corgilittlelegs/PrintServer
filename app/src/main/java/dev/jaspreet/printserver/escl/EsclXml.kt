package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScanTone
import dev.jaspreet.printserver.scan.ScannerCapabilities

data class EsclScanSettings(
    val resolution: Int?,
    val colorMode: ScanColorMode?,
    val brightness: Int?,
    val contrast: Int?,
)

data class EsclJobInfo(
    val id: String,
    val state: String,
    val ageSeconds: Long = 0,
    val imagesCompleted: Int = 0,
    val imagesToTransfer: Int = 0,
    val stateReason: String = "None",
)

/** Builds/parses eSCL's XML wire format. See this plan's top-level note on fidelity --
 *  these shapes follow public Mopria/AirScan eSCL conventions, validated against a real
 *  client in this plan's hardware-test task. */
object EsclXml {
    private const val SCAN_NS = "http://schemas.hp.com/imaging/escl/2011/05/03"
    private const val PWG_NS = "http://www.pwg.org/schemas/2010/12/sm"

    fun scannerCapabilities(caps: ScannerCapabilities, makeAndModel: String): String {
        val colorModes = buildString {
            if (ScanColorMode.COLOR in caps.supportedColorModes) append("<scan:ColorMode>RGB24</scan:ColorMode>")
            if (ScanColorMode.GRAYSCALE in caps.supportedColorModes) append("<scan:ColorMode>Grayscale8</scan:ColorMode>")
        }
        val resolutions = caps.supportedResolutions.joinToString("") { dpi ->
            "<scan:DiscreteResolution><scan:XResolution>$dpi</scan:XResolution>" +
                "<scan:YResolution>$dpi</scan:YResolution></scan:DiscreteResolution>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<scan:ScannerCapabilities xmlns:scan=\"$SCAN_NS\" xmlns:pwg=\"$PWG_NS\">" +
            "<pwg:Version>2.0</pwg:Version>" +
            "<pwg:MakeAndModel>$makeAndModel</pwg:MakeAndModel>" +
            "<scan:Platen><scan:PlatenInputCaps>" +
            "<scan:MinWidth>50</scan:MinWidth><scan:MinHeight>50</scan:MinHeight>" +
            "<scan:MaxWidth>${caps.maxWidth}</scan:MaxWidth><scan:MaxHeight>${caps.maxHeight}</scan:MaxHeight>" +
            "<scan:MaxScanRegions>1</scan:MaxScanRegions>" +
            "<scan:SettingProfiles><scan:SettingProfile>" +
            "<scan:ColorModes>$colorModes</scan:ColorModes>" +
            "<scan:DocumentFormats><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat></scan:DocumentFormats>" +
            "<scan:SupportedResolutions><scan:DiscreteResolutions>$resolutions</scan:DiscreteResolutions></scan:SupportedResolutions>" +
            "</scan:SettingProfile></scan:SettingProfiles>" +
            "</scan:PlatenInputCaps></scan:Platen>" +
            rangeXml("BrightnessSupport") +
            rangeXml("ContrastSupport") +
            "</scan:ScannerCapabilities>"
    }

    fun parseScanSettings(xml: String): EsclScanSettings {
        val resolution = intElement(xml, "XResolution")
        val colorMode = when {
            xml.contains("<scan:ColorMode>RGB24</scan:ColorMode>") -> ScanColorMode.COLOR
            xml.contains("<scan:ColorMode>Grayscale8</scan:ColorMode>") -> ScanColorMode.GRAYSCALE
            xml.contains("<ColorMode>RGB24</ColorMode>") -> ScanColorMode.COLOR
            xml.contains("<ColorMode>Grayscale8</ColorMode>") -> ScanColorMode.GRAYSCALE
            else -> null
        }
        return EsclScanSettings(
            resolution = resolution,
            colorMode = colorMode,
            brightness = intElement(xml, "Brightness"),
            contrast = intElement(xml, "Contrast"),
        )
    }

    fun scannerStatus(jobs: List<EsclJobInfo>): String {
        val state = if (jobs.any { it.state == "Processing" }) "Processing" else "Idle"
        val jobEntries = jobs.joinToString("") { job ->
            "<scan:JobInfo><pwg:JobUri>/eSCL/ScanJobs/${job.id}</pwg:JobUri>" +
                "<pwg:JobUuid>urn:uuid:${job.id}</pwg:JobUuid>" +
                "<scan:Age>${job.ageSeconds}</scan:Age>" +
                "<pwg:ImagesCompleted>${job.imagesCompleted}</pwg:ImagesCompleted>" +
                "<pwg:ImagesToTransfer>${job.imagesToTransfer}</pwg:ImagesToTransfer>" +
                "<pwg:JobState>${job.state}</pwg:JobState>" +
                "<pwg:JobStateReasons><pwg:JobStateReason>${job.stateReason}</pwg:JobStateReason></pwg:JobStateReasons>" +
                "</scan:JobInfo>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<scan:ScannerStatus xmlns:scan=\"$SCAN_NS\" xmlns:pwg=\"$PWG_NS\">" +
            "<pwg:Version>2.0</pwg:Version>" +
            // Real clients (confirmed against sane-airscan's actual parsing code,
            // airscan-escl.c's escl_parse_scanner_status()) look up the top-level
            // scanner state as "pwg:State", not "scan:State" -- getting this wrong
            // means a client can never confirm the scanner is Idle and so never
            // proceeds to POST a scan job, no matter how many times it polls status.
            "<pwg:State>$state</pwg:State>" +
            "<scan:Jobs>$jobEntries</scan:Jobs>" +
            "</scan:ScannerStatus>"
    }

    private fun rangeXml(name: String): String =
        "<scan:$name>" +
            "<scan:Min>${ScanTone.MIN}</scan:Min>" +
            "<scan:Max>${ScanTone.MAX}</scan:Max>" +
            "<scan:Normal>${ScanTone.DEFAULT}</scan:Normal>" +
            "<scan:Step>1</scan:Step>" +
            "</scan:$name>"

    private fun intElement(xml: String, name: String): Int? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name>(-?\\d+)</(?:[A-Za-z0-9_]+:)?$name>")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}
