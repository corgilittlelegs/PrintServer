package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities

data class EsclScanSettings(val resolution: Int?, val colorMode: ScanColorMode?)

data class EsclJobInfo(val id: String, val state: String)

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
            "<pwg:Version>2.63</pwg:Version>" +
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
            "</scan:ScannerCapabilities>"
    }

    fun parseScanSettings(xml: String): EsclScanSettings {
        val resolution = Regex("<scan:XResolution>(\\d+)</scan:XResolution>").find(xml)
            ?.groupValues?.get(1)?.toIntOrNull()
        val colorMode = when {
            xml.contains("<scan:ColorMode>RGB24</scan:ColorMode>") -> ScanColorMode.COLOR
            xml.contains("<scan:ColorMode>Grayscale8</scan:ColorMode>") -> ScanColorMode.GRAYSCALE
            else -> null
        }
        return EsclScanSettings(resolution, colorMode)
    }

    fun scannerStatus(jobs: List<EsclJobInfo>): String {
        val state = if (jobs.any { it.state == "Processing" }) "Processing" else "Idle"
        val jobEntries = jobs.joinToString("") { job ->
            "<scan:JobInfo><pwg:JobUri>/eSCL/ScanJobs/${job.id}</pwg:JobUri>" +
                "<pwg:JobState>${job.state}</pwg:JobState></scan:JobInfo>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<scan:ScannerStatus xmlns:scan=\"$SCAN_NS\" xmlns:pwg=\"$PWG_NS\">" +
            "<pwg:Version>2.63</pwg:Version>" +
            "<scan:State>$state</scan:State>" +
            "<scan:Jobs>$jobEntries</scan:Jobs>" +
            "</scan:ScannerStatus>"
    }
}
