package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import timber.log.Timber

object WowClientDownloader {
    const val FLAVOR_DIR = "_forever_"
    const val EXE_NAME = "Wow-ARM64.exe"
    const val X64_EXE_NAME = "Wow.exe"
    const val TARGET_PRODUCT = "wowf"
    const val BUILD_INFO = ".build.info"

    // Optional debug switch: allow downloading ARM64 client binaries for A/B testing (defaults to false)
    var ALLOW_DOWNLOAD_ARM64 = false

    // Official NetEase / Blizzard CN CDN servers
    val OFFICIAL_CN_CDN_HOSTS = listOf(
        "blzdist-wow.necdn.leihuo.netease.com",
        "client01.pdl.wow.battlenet.com.cn",
        "level3.blizzard.cn.edgesuite.net",
    )

    private val KNOWN_REGION_TAGS = setOf("CN", "US", "EU", "KR", "TW")

    fun isX64ClientReady(gameRoot: File): Boolean {
        val clientDir = File(gameRoot, FLAVOR_DIR)
        val wowExe = File(clientDir, X64_EXE_NAME)
        return wowExe.isFile && wowExe.length() > 0
    }

    // The pre-release beta. Its Data/ archives are shared with the full release, so beta installs upgrade in place.
    const val LEGACY_FLAVOR_DIR = "_classic_beta_"
    const val LEGACY_PRODUCT = "wow_classic_beta"
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"
    private const val US_PATCH_URL = "http://us.patch.battle.net:1119/$TARGET_PRODUCT"
    private const val CN_PATCH_URL = "http://cn.patch.battle.net:1119/$TARGET_PRODUCT"
    private const val DEFAULT_CDN_PATH = "tpr/wow"
    private val DEFAULT_CDN_HOSTS = listOf("level3.blizzard.com", "us.cdn.blizzard.com")
    private const val INDEX_DOWNLOAD_THREADS = 8
    private val BUILD_INFO_HEADER = listOf(
        "Branch!STRING:0", "Active!DEC:1", "Build Key!HEX:16", "CDN Key!HEX:16", "Install Key!HEX:16", "IM Size!DEC:4",
        "CDN Path!STRING:0", "CDN Hosts!STRING:0", "CDN Servers!STRING:0", "Tags!STRING:0", "Armadillo!STRING:0",
        "Last Activated!STRING:0", "Version!STRING:0", "KeyRing!HEX:16", "Product!STRING:0",
    ).joinToString("|")
    internal val EU_COUNTRIES = setOf(
        "AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR", "HU", "IE", "IS", "IT",
        "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO", "RU", "SE", "SI", "SK", "UA", "TR",
    )

    private val patchHttp by lazy {
        Net.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    data class VersionCheckResult(
        val localVersion: String,
        val remoteVersion: String,
        val isOutdated: Boolean,
        val remoteBuildConfig: String = "",
        val remoteCdnConfig: String = "",
        val remoteCdnHosts: List<String> = emptyList(),
        val remoteCdnPath: String = DEFAULT_CDN_PATH,
        val remoteCdnServers: List<String> = emptyList(),
    )

    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo) }.getOrNull()

    fun installedProducts(buildInfo: File): Set<String> =
        runCatching { parsePsv(buildInfo.readText()).mapNotNull { it["Product"] }.toSet() }.getOrDefault(emptySet())

    private fun fetchPatchText(endpoint: String, regionCode: String? = null): String? {
        val isCn = regionCode?.equals("CN", ignoreCase = true) == true ||
            (regionCode == null && defaultBranch() == "cn")

        if (isCn) {
            val cnUrl = "$CN_PATCH_URL/$endpoint"
            val cnResult = runCatching { fetchText(cnUrl) }.getOrNull()
            if (!cnResult.isNullOrBlank()) {
                Timber.i("Fetched $endpoint from CN patch server ($cnUrl)")
                return cnResult
            }
            Timber.w("CN patch server unreachable or empty for $endpoint, falling back to US patch server")
        }

        val usUrl = "$US_PATCH_URL/$endpoint"
        return runCatching { fetchText(usUrl) }.getOrElse {
            Timber.w(it, "Failed to fetch $endpoint from US patch server ($usUrl)")
            null
        }
    }

    fun checkVersion(gameRoot: File, regionCode: String? = null): VersionCheckResult? {
        val row = runCatching { activeBuild(File(gameRoot, BUILD_INFO)) }.getOrElse {
            Timber.w(it, "checkVersion: no readable $BUILD_INFO in $gameRoot")
            return null
        }
        val product = row["Product"]?.takeIf { it.isNotBlank() } ?: TARGET_PRODUCT
        if (product != TARGET_PRODUCT) {
            Timber.w("checkVersion: wrong product '$product', expected '$TARGET_PRODUCT'")
            return null
        }
        val localVersion = row["Version"] ?: run {
            Timber.w("checkVersion: row has no Version")
            return null
        }
        val localBuildKey = row["Build Key"] ?: run {
            Timber.w("checkVersion: row has no Build Key")
            return null
        }

        val effectiveRegion = regionCode ?: row["Branch"]
        val remote = fetchRemote(row, effectiveRegion) ?: return null
        val isOutdated = localVersion != remote.remoteVersion ||
            (remote.remoteBuildConfig.isNotBlank() && localBuildKey != remote.remoteBuildConfig)
        Timber.i("checkVersion: localVersion=$localVersion, remoteVersion=${remote.remoteVersion}, isOutdated=$isOutdated")
        return remote.copy(localVersion = localVersion, isOutdated = isOutdated)
    }

    private fun fetchRemote(localRow: Map<String, String>? = null, regionCode: String? = null): VersionCheckResult? {
        val effectiveRegion = regionCode
            ?: localRow?.get("Branch")?.takeIf { it.isNotBlank() }
            ?: defaultBranch()
        val isCn = effectiveRegion.equals("CN", ignoreCase = true) || effectiveRegion.equals("cn", ignoreCase = true)

        val versions = fetchPatchText("versions", effectiveRegion) ?: run {
            Timber.w("fetchRemote: failed to retrieve versions for region $effectiveRegion")
            return null
        }
        val versionRows = parsePsv(versions)
        val remoteRow = versionRows.firstOrNull { it["Region"]?.equals(effectiveRegion, ignoreCase = true) == true }
            ?: versionRows.firstOrNull { it["Region"]?.equals("us", ignoreCase = true) == true }
            ?: versionRows.firstOrNull()
            ?: run {
                Timber.w("fetchRemote: no remote row in $versions")
                return null
            }
        val remoteVersion = remoteRow["VersionsName"] ?: remoteRow["VersionName"] ?: remoteRow["Version"]
            ?: run {
                Timber.w("fetchRemote: no remoteVersion in $remoteRow")
                return null
            }

        val cdnsText = fetchPatchText("cdns", effectiveRegion)
        val cdns = cdnsText?.let(::parsePsv).orEmpty()
        val cdnsRow = cdns.firstOrNull { it["Name"]?.equals(effectiveRegion, ignoreCase = true) == true }
            ?: cdns.firstOrNull { it["Name"]?.equals("us", ignoreCase = true) == true }
            ?: cdns.firstOrNull()

        val parsedHosts = (cdnsRow?.get("Hosts") ?: localRow?.get("CDN Hosts"))?.let(::splitHosts).orEmpty()
        val remoteCdnHosts = if (isCn) {
            (OFFICIAL_CN_CDN_HOSTS + parsedHosts + listOf("level3.blizzard.com", "us.cdn.blizzard.com")).distinct()
        } else {
            parsedHosts.ifEmpty { DEFAULT_CDN_HOSTS }
        }

        return VersionCheckResult(
            localVersion = "",
            remoteVersion = remoteVersion,
            isOutdated = true,
            remoteBuildConfig = remoteRow["BuildConfig"].orEmpty(),
            remoteCdnConfig = remoteRow["CDNConfig"].orEmpty(),
            remoteCdnHosts = remoteCdnHosts,
            remoteCdnPath = cdnsRow?.get("Path") ?: localRow?.get("CDN Path") ?: DEFAULT_CDN_PATH,
            remoteCdnServers = (cdnsRow?.get("Servers") ?: localRow?.get("CDN Servers"))?.let(::splitHosts).orEmpty(),
        )
    }

    /**
     * Sets up the full-release client in [gameRoot]: an empty folder for a new install, or a beta install that is upgraded
     * in place. Downloads the build configs, archive indexes and ARM64 client, then adds the [TARGET_PRODUCT] row to
     * [BUILD_INFO] last, so an interrupted install is simply resumed the next time. Game assets that aren't on disk yet
     * are streamed from the CDN by the game client while playing.
     */
    fun install(gameRoot: File, onStatus: (String) -> Unit) {
        install(gameRoot, null, onStatus)
    }

    fun install(gameRoot: File, regionCode: String?, onStatus: (String) -> Unit) {
        onStatus("Checking the latest World of Warcraft build...")
        val target = fetchRemote(regionCode = regionCode) ?: throw IOException("Couldn't reach Blizzard's patch server. Check your connection.")
        check(target.remoteBuildConfig.isNotBlank()) { "Blizzard's patch server returned no build" }
        listOf("Data/data", "Data/indices", "Data/config").forEach { File(gameRoot, it).mkdirs() }
        seedResidency(gameRoot)
        updateGame(gameRoot, target, regionCode, onStatus)
    }

    // The Battle.net app tracks which shared archives each product uses in Data/<product>/. A beta install has the same
    // archives as the full release, so its record is a better starting point than none.
    private fun seedResidency(gameRoot: File) {
        val residency = File(gameRoot, "Data/$TARGET_PRODUCT")
        val legacy = File(gameRoot, "Data/$LEGACY_PRODUCT")
        if (residency.exists() || !legacy.isDirectory) return
        runCatching { legacy.copyRecursively(residency) }.onFailure { Timber.w(it, "Couldn't copy the beta residency data") }
    }

    fun updateGame(gameRoot: File, target: VersionCheckResult, onStatus: (String) -> Unit) {
        updateGame(gameRoot, target, null, onStatus)
    }

    fun updateGame(gameRoot: File, target: VersionCheckResult, regionCode: String?, onStatus: (String) -> Unit) {
        val cdn = Cdn(
            hosts = target.remoteCdnHosts.ifEmpty { DEFAULT_CDN_HOSTS },
            path = target.remoteCdnPath.ifEmpty { DEFAULT_CDN_PATH },
        )
        onStatus("Fetching build ${target.remoteVersion} manifest...")
        val buildConfigRaw = cdn.require("config", target.remoteBuildConfig)
        val cdnConfigRaw = target.remoteCdnConfig.takeIf { it.isNotBlank() }?.let { cdn.require("config", it) }

        saveConfig(gameRoot, target.remoteBuildConfig, buildConfigRaw)
        cdnConfigRaw?.let { saveConfig(gameRoot, target.remoteCdnConfig, it) }

        val cdnConfig = cdnConfigRaw?.let { parseConfig(String(it)) }
        cdnConfig?.let { syncIndices(gameRoot, cdn, it, onStatus) }
        syncClient(
            gameRoot = gameRoot,
            cdn = cdn,
            buildConfig = parseConfig(String(buildConfigRaw)),
            archives = lazy { cdnConfig?.let { ArchiveLocator(cdn, gameRoot, it.getValue("archives")) } },
            regionCode = regionCode,
            expectedVersion = target.remoteVersion,
            onStatus = onStatus,
        )

        onStatus("Updating build information...")
        upsertBuildInfo(File(gameRoot, BUILD_INFO), target, regionCode)
    }

    fun download(gameRoot: File, onStatus: (String) -> Unit) {
        download(gameRoot, null, onStatus)
    }

    fun download(gameRoot: File, regionCode: String?, onStatus: (String) -> Unit) {
        val buildInfoFile = File(gameRoot, BUILD_INFO)
        if (!buildInfoFile.exists()) {
            install(gameRoot, regionCode, onStatus)
            return
        }
        val row = activeBuild(buildInfoFile)
        check(row["Product"] == TARGET_PRODUCT) { "Incompatible game product: ${row["Product"]}. Expected $TARGET_PRODUCT." }
        val effectiveRegion = regionCode ?: row["Branch"]
        val localVersion = row["Version"]?.trim()

        val isCn = effectiveRegion?.equals("CN", ignoreCase = true) == true ||
            (effectiveRegion == null && defaultBranch() == "cn")
        val parsedHosts = splitHosts(row.getValue("CDN Hosts"))
        val cdnHosts = if (isCn) {
            (OFFICIAL_CN_CDN_HOSTS + parsedHosts + listOf("level3.blizzard.com")).distinct()
        } else {
            parsedHosts.ifEmpty { DEFAULT_CDN_HOSTS }
        }
        val cdn = Cdn(hosts = cdnHosts, path = row.getValue("CDN Path"))
        val buildKey = row.getValue("Build Key")
        val buildConfigRaw = cdn.require("config", buildKey)
        val buildConfig = parseConfig(String(buildConfigRaw))

        // Check version consistency: manifest build-name must match local .build.info version
        val buildName = buildConfig["build-name"]?.firstOrNull() ?: ""
        if (localVersion != null && !buildName.contains(localVersion) && !buildName.contains(localVersion.substringAfterLast('.'))) {
            val msg = "Version mismatch: .build.info specifies version $localVersion, but manifest is $buildName. Stopped download to protect integrity."
            LauncherDebugLogger.log("VERSION_CHECK", msg)
            throw IllegalStateException(msg)
        }

        onStatus("Reading build ${row["Version"].orEmpty()} manifest...")
        syncClient(
            gameRoot = gameRoot,
            cdn = cdn,
            buildConfig = buildConfig,
            archives = lazy {
                ArchiveLocator(cdn, gameRoot, parseConfig(String(cdn.require("config", row.getValue("CDN Key")))).getValue("archives"))
            },
            regionCode = effectiveRegion,
            expectedVersion = localVersion,
            onStatus = onStatus,
        )
    }

    private fun syncClient(
        gameRoot: File,
        cdn: Cdn,
        buildConfig: Map<String, List<String>>,
        archives: Lazy<ArchiveLocator?>,
        regionCode: String? = null,
        expectedVersion: String? = null,
        onStatus: (String) -> Unit,
    ) {
        val rawInstall = blteDecode(cdn.require("data", buildConfig.getValue("install")[1]))
        val allEntries = parseInstall(rawInstall)

        val filterResults = allEntries.map { filterEntry(it, regionCode, ALLOW_DOWNLOAD_ARM64) }
        val accepted = filterResults.filter { it.accepted }.map { it.entry }
        val rejected = filterResults.filter { !it.accepted }

        // Step 1: Pre-download audit logging
        LauncherDebugLogger.log("MANIFEST_AUDIT", "=== SELECTED CLIENT FILES (${accepted.size}) ===")
        accepted.forEach { entry ->
            LauncherDebugLogger.log("MANIFEST_AUDIT", "  SELECTED: ${entry.name} (size=${entry.size}, md5=${entry.ckey}, tags=${entry.tags.joinToString()})")
        }
        LauncherDebugLogger.log("MANIFEST_AUDIT", "=== EXCLUDED CLIENT FILES (${rejected.size}) ===")
        rejected.forEach { item ->
            LauncherDebugLogger.log("MANIFEST_AUDIT", "  EXCLUDED: ${item.entry.name} (size=${item.entry.size}, reason=${item.reason})")
        }

        // Step 2: Manifest-driven integrity validation
        check(accepted.isNotEmpty()) { "No client files selected from official manifest for build ${expectedVersion ?: "unknown"}" }
        if (!ALLOW_DOWNLOAD_ARM64) {
            check(accepted.any { it.name.equals(X64_EXE_NAME, ignoreCase = true) }) {
                "Manifest does not contain required main executable '$X64_EXE_NAME' for build ${expectedVersion ?: "unknown"}"
            }
        }
        LauncherDebugLogger.log("MANIFEST_AUDIT", "Manifest parsing complete: ${accepted.size} client files dynamically selected from official manifest.")

        val clientDir = File(gameRoot, FLAVOR_DIR)
        clientDir.mkdirs()

        // Step 3: Check existing local files to protect user data
        val pending = mutableListOf<InstallEntry>()
        for (entry in accepted) {
            val localFile = File(clientDir, entry.relativePath)
            if (localFile.exists()) {
                val localSize = localFile.length()
                val localMd5 = md5Hex(localFile)
                if (localSize == entry.size && localMd5.equals(entry.ckey, ignoreCase = true)) {
                    LauncherDebugLogger.log("DOWNLOAD_CHECK", "[PRESERVED] ${entry.relativePath} matches manifest (size=$localSize, md5=$localMd5)")
                } else {
                    LauncherDebugLogger.log(
                        "DOWNLOAD_CHECK",
                        "[REPLACE_MISMATCH] ${entry.relativePath} differs from manifest: local(size=$localSize, md5=$localMd5) vs expected(size=${entry.size}, md5=${entry.ckey}). Will re-download.",
                    )
                    pending.add(entry)
                }
            } else {
                pending.add(entry)
            }
        }

        // Step 4: Download with retry & atomic rename
        if (pending.isNotEmpty()) {
            onStatus("Looking up client files...")
            val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
            pending.forEachIndexed { index, entry ->
                onStatus("Downloading ${entry.name} (${index + 1}/${pending.size})...")
                val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
                downloadWithRetry(File(clientDir, entry.relativePath), entry, ekey, cdn, archives)
            }
        }

        // Step 5: Full verification matrix logging
        LauncherDebugLogger.log("VERIFY_MATRIX", "=== GAME CLIENT VERIFICATION MATRIX ===")
        var allOk = true
        for (entry in accepted) {
            val localFile = File(clientDir, entry.relativePath)
            val status: String
            val localSize: Long
            val localMd5: String?
            if (!localFile.exists()) {
                status = "MISSING"
                localSize = 0L
                localMd5 = "NONE"
                allOk = false
            } else {
                localSize = localFile.length()
                localMd5 = md5Hex(localFile)
                if (localSize == entry.size && localMd5.equals(entry.ckey, ignoreCase = true)) {
                    status = "OK"
                } else {
                    status = "MISMATCH"
                    allOk = false
                }
            }
            LauncherDebugLogger.log(
                "VERIFY_MATRIX",
                "${entry.relativePath} | EXPECTED_SIZE=${entry.size} | LOCAL_SIZE=$localSize | EXPECTED_MD5=${entry.ckey} | LOCAL_MD5=$localMd5 | STATUS=$status",
            )
        }
        if (!allOk) {
            throw IllegalStateException("Game client verification matrix check failed: one or more files are missing or mismatched!")
        }
    }

    private fun downloadWithRetry(dest: File, entry: InstallEntry, ekey: String, cdn: Cdn, archives: Lazy<ArchiveLocator?>) {
        var lastError: Exception? = null
        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            try {
                val encoded = cdn.fetch("data", ekey) ?: archives.value?.read(ekey) ?: throw IOException("${entry.name} not found on the CDN or in archives")
                writeVerified(dest, entry, encoded)
                return
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "Download attempt $attempt/$maxRetries failed for ${entry.name}")
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt)
                    } catch (_: InterruptedException) {}
                }
            }
        }
        throw IOException("Failed to download ${entry.name} after $maxRetries attempts: ${lastError?.message}", lastError)
    }

    private fun writeVerified(dest: File, entry: InstallEntry, encoded: ByteArray) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        val digest = MessageDigest.getInstance("MD5")
        DigestOutputStream(temp.outputStream().buffered(), digest).use { blteDecode(encoded, it) }
        val actualMd5 = hex(digest.digest(), 0, 16)
        if (!actualMd5.equals(entry.ckey, ignoreCase = true)) {
            temp.delete()
            throw IOException("Checksum mismatch for ${entry.name}: expected ${entry.ckey}, got $actualMd5")
        }
        if (temp.length() != entry.size) {
            temp.delete()
            throw IOException("Size mismatch for ${entry.name}: expected ${entry.size}, got ${temp.length()}")
        }
        dest.delete()
        check(temp.renameTo(dest)) { "Could not atomically write ${dest.path}" }
    }

    private fun saveConfig(gameRoot: File, key: String, bytes: ByteArray) =
        writeAtomically(File(gameRoot, "Data/config/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"), bytes)

    // Patch archives and archive groups aren't needed to play: the former live under patch/, and the client builds the latter.
    private fun syncIndices(gameRoot: File, cdn: Cdn, cdnConfig: Map<String, List<String>>, onStatus: (String) -> Unit) {
        val required = cdnConfig["archives"].orEmpty() + cdnConfig["file-index"].orEmpty()
        val missing = required.distinct().filter { !File(gameRoot, "Data/indices/$it.index").isFile }
        if (missing.isEmpty()) return
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(INDEX_DOWNLOAD_THREADS)
        try {
            missing.map { hash ->
                pool.submit {
                    cdn.fetch("data", "$hash.index")?.let { writeAtomically(File(gameRoot, "Data/indices/$hash.index"), it) }
                    onStatus("Syncing archive indexes (${done.incrementAndGet()}/${missing.size})...")
                }
            }.forEach { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }

    private fun writeAtomically(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        temp.writeBytes(bytes)
        dest.delete()
        check(temp.renameTo(dest)) { "Could not write ${dest.path}" }
    }

    /**
     * Points the [TARGET_PRODUCT] row of [buildInfo] at [target], adding the row (and the file) when it's missing.
     * A new row copies the region of an existing beta row so an upgraded install keeps its region. The game streams
     * missing assets from the "CDN Servers" URLs and crashes on startup when there are none.
     */
    internal fun upsertBuildInfo(buildInfo: File, target: VersionCheckResult, regionCode: String? = null) {
        val lines = buildInfo.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(BUILD_INFO_HEADER)
        val header = psvHeader(lines.first())
        val rows = lines.drop(1).map { line -> header.zip(line.split("|")).toMap() }
        val existing = rows.indexOfFirst { it["Product"] == TARGET_PRODUCT }
        val effectiveBranch = regionCode?.lowercase(Locale.ROOT)
            ?: (if (existing >= 0) rows[existing]["Branch"] else null)?.takeIf { it.isNotBlank() }
            ?: rows.firstOrNull { it["Product"] == LEGACY_PRODUCT }?.get("Branch")?.takeIf { it.isNotBlank() }
            ?: defaultBranch()
        val region = effectiveBranch.uppercase(Locale.ROOT)

        val fallbackHosts = if (region == "CN") (OFFICIAL_CN_CDN_HOSTS + listOf("level3.blizzard.com", "us.cdn.blizzard.com")).distinct() else DEFAULT_CDN_HOSTS
        val cdnHosts = target.remoteCdnHosts.ifEmpty { fallbackHosts }
        val cdnServers = target.remoteCdnServers.ifEmpty { cdnHosts.map { "http://$it/?maxhosts=4" } }

        val updates = mutableMapOf(
            "Active" to "1",
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to cdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath.ifBlank { DEFAULT_CDN_PATH },
            "CDN Servers" to cdnServers.joinToString(" "),
            "Product" to TARGET_PRODUCT,
        )

        val tags = if (region == "CN") {
            listOf("speech", "text").flatMap {
                listOf(
                    "Windows $PLATFORM_ARCH $region? enUS $it?",
                    "Windows $PLATFORM_ARCH $region? zhCN $it?",
                )
            }.joinToString(":")
        } else {
            val localeTag = when (region) {
                "TW" -> "zhTW"
                "KR" -> "koKR"
                else -> "enUS"
            }
            listOf("speech", "text").joinToString(":") { "Windows $PLATFORM_ARCH $region? $localeTag $it?" }
        }
        updates["Branch"] = effectiveBranch
        updates["Tags"] = tags

        val updated = if (existing >= 0) {
            rows.mapIndexed { idx, row -> if (idx == existing) row + updates.filterValues { v -> v.isNotBlank() } else row }
        } else {
            rows + updates.filterValues { it.isNotBlank() }
        }
        val text = (listOf(lines.first()) + updated.map { row -> header.joinToString("|") { row[it].orEmpty() } }).joinToString("\n") + "\n"
        writeAtomically(buildInfo, text.toByteArray())
    }

    // Best guess at the region the Battle.net app would record, for installs that have no beta row to copy it from.
    fun defaultBranch(): String {
        val country = Locale.getDefault().country.uppercase(Locale.ROOT)
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        val str = Locale.getDefault().toString().lowercase(Locale.ROOT)
        return when {
            country == "CN" || str.startsWith("zh_cn") || (lang == "zh" && (country.isEmpty() || country == "CN")) -> "cn"
            country == "KR" || lang == "ko" -> "kr"
            country == "TW" || country == "HK" || country == "MO" || str.startsWith("zh_tw") || str.startsWith("zh_hk") -> "tw"
            country in EU_COUNTRIES -> "eu"
            else -> "us"
        }
    }

    data class InstallEntry(val name: String, val ckey: String, val size: Long, val tags: Set<String>) {
        val relativePath get() = name.replace('\\', '/')
    }

    data class FilterResult(val entry: InstallEntry, val accepted: Boolean, val reason: String)

    fun filterEntry(entry: InstallEntry, regionCode: String?, allowArm64: Boolean): FilterResult {
        if ("Windows" !in entry.tags) {
            return FilterResult(entry, false, "Not Windows")
        }

        val isCn = regionCode?.equals("CN", ignoreCase = true) == true ||
            (regionCode == null && defaultBranch() == "cn")

        if (allowArm64) {
            if ("arm64" in entry.tags && "x86_64" !in entry.tags) {
                val lower = entry.name.lowercase()
                if (lower.startsWith("utils\\") && "exceptionhandler" !in lower) {
                    return FilterResult(entry, false, "Utils optional")
                }
                return FilterResult(entry, true, "ARM64 binary")
            }
        }

        // x86_64 rules
        if ("x86_64" !in entry.tags) {
            return FilterResult(entry, false, "Not x86_64")
        }

        // Region filtering:
        // If an entry has region-specific tags among KNOWN_REGION_TAGS (CN, US, EU, KR, TW),
        // for CN region, it MUST contain "CN" (e.g. CN Wow.exe has CN, while Global Wow.exe has EU,KR,TW,US).
        if (isCn) {
            val regionTags = entry.tags.intersect(KNOWN_REGION_TAGS)
            if (regionTags.isNotEmpty() && "CN" !in regionTags) {
                return FilterResult(entry, false, "Region mismatch (tags=$regionTags, lacks CN)")
            }
        }

        // Utils filtering: only exception handlers are kept
        val lower = entry.name.lowercase()
        if (lower.startsWith("utils\\")) {
            if ("exceptionhandler" !in lower) {
                return FilterResult(entry, false, "Utils optional")
            }
        }

        // D3D12 (x86_64) is kept; D3D12_ARM64 is naturally rejected because it lacks x86_64 tag.
        return FilterResult(entry, true, "x86_64 client file")
    }

    private class Cdn(val hosts: List<String>, val path: String) {
        fun require(kind: String, key: String) = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")

        fun fetch(kind: String, key: String, range: LongRange? = null): ByteArray? {
            var failure: IOException? = null
            var missing = false
            for (host in hosts) {
                val request = Request.Builder()
                    .url("http://$host/$path/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key")
                    .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                    .build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> return response.body!!.bytes()
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $host")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (missing) return null
            throw failure ?: IOException("No CDN hosts in $BUILD_INFO")
        }
    }

    private class EncodingLookup(private val cdn: Cdn, private val ekey: String) {
        private data class Chunk(val encodedOffset: Long, val encodedSize: Int, val decodedOffset: Long, val decodedSize: Int)

        private val chunks: List<Chunk> = parseChunkTable(ByteBuffer.wrap(fetchEncoded(0, 8)).getInt(4))

        private fun parseChunkTable(headerSize: Int): List<Chunk> {
            val header = ByteBuffer.wrap(fetchEncoded(0, headerSize))
            val count = header.getInt(8) and 0xFFFFFF
            var encoded = headerSize.toLong()
            var decoded = 0L
            return (0 until count).map { i ->
                val compressedSize = header.getInt(12 + i * 24)
                val decompressedSize = header.getInt(16 + i * 24)
                Chunk(encoded, compressedSize, decoded, decompressedSize).also {
                    encoded += compressedSize
                    decoded += decompressedSize
                }
            }
        }

        private var cachedChunk: Chunk? = null
        private var cachedData: ByteArray = ByteArray(0)

        private fun read(start: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var written = 0
            for (chunk in chunks) {
                val chunkEnd = chunk.decodedOffset + chunk.decodedSize
                if (chunkEnd <= start || chunk.decodedOffset >= start + length) continue
                val from = maxOf(start, chunk.decodedOffset) - chunk.decodedOffset
                val count = (minOf(start + length, chunkEnd) - chunk.decodedOffset - from).toInt()
                val mode = fetchEncoded(chunk.encodedOffset, 1)[0].toInt().toChar()
                val bytes = if (mode == 'N') {
                    fetchEncoded(chunk.encodedOffset + 1 + from, count)
                } else {
                    decodedChunk(chunk).copyOfRange(from.toInt(), from.toInt() + count)
                }
                bytes.copyInto(out, written)
                written += count
            }
            return out
        }

        private fun decodedChunk(chunk: Chunk): ByteArray {
            if (cachedChunk != chunk) {
                cachedData = decodeChunk(fetchEncoded(chunk.encodedOffset, chunk.encodedSize))
                cachedChunk = chunk
            }
            return cachedData
        }

        private fun fetchEncoded(offset: Long, size: Int) =
            cdn.fetch("data", ekey, offset until offset + size) ?: throw IOException("Encoding file missing")

        fun find(wanted: Set<String>): Map<String, String> {
            val header = ByteBuffer.wrap(read(0, 22))
            val ckeySize = header.get(3).toInt()
            val ekeySize = header.get(4).toInt()
            val pageSize = (header.getShort(5).toInt() and 0xFFFF) * 1024
            val pageCount = header.getInt(9)
            val especSize = header.getInt(18)
            val indexStart = 22L + especSize
            val indexEntrySize = ckeySize + 16
            val index = read(indexStart, pageCount * indexEntrySize)
            val firstKeys = (0 until pageCount).map { hex(index, it * indexEntrySize, ckeySize) }
            val pagesStart = indexStart + pageCount.toLong() * indexEntrySize

            val found = mutableMapOf<String, String>()
            wanted.groupBy { ckey -> firstKeys.binarySearch(ckey).let { if (it >= 0) it else -it - 2 } }
                .filterKeys { it >= 0 }
                .forEach { (page, ckeys) ->
                    val data = read(pagesStart + page.toLong() * pageSize, pageSize)
                    var pos = 0
                    while (pos + 6 + ckeySize <= data.size && data[pos].toInt() != 0) {
                        val keyCount = data[pos].toInt() and 0xFF
                        val ckey = hex(data, pos + 6, ckeySize)
                        if (ckey in ckeys) found[ckey] = hex(data, pos + 6 + ckeySize, ekeySize)
                        pos += 6 + ckeySize + keyCount * ekeySize
                    }
                }
            return found
        }
    }

    private class ArchiveLocator(private val cdn: Cdn, private val gameRoot: File, private val archives: List<String>) {
        fun read(ekey: String): ByteArray? {
            for (archive in archives) {
                val local = File(gameRoot, "Data/indices/$archive.index")
                val index = if (local.isFile) {
                    local.readBytes()
                } else {
                    cdn.fetch("data", "$archive.index")?.also { writeAtomically(local, it) } ?: continue
                }
                val buffer = ByteBuffer.wrap(index)
                for (block in 0 until index.size / 4096 * 4096 step 4096) {
                    var pos = block
                    while (pos <= block + 4096 - 24) {
                        if (hex(index, pos, 16) == ekey) {
                            val size = buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL
                            val offset = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                            return cdn.fetch("data", archive, offset until offset + size)
                        }
                        pos += 24
                    }
                }
            }
            return null
        }
    }

    private fun fetchText(url: String): String? =
        patchHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }

    private fun activeBuild(buildInfo: File): Map<String, String> {
        check(buildInfo.isFile) { "Missing $BUILD_INFO in ${buildInfo.parent}" }
        val rows = parsePsv(buildInfo.readText())
        return rows.firstOrNull { it["Product"] == TARGET_PRODUCT && it["Active"] == "1" }
            ?: rows.firstOrNull { it["Product"] == TARGET_PRODUCT }
            ?: rows.firstOrNull { it["Active"] == "1" }
            ?: rows.firstOrNull()
            ?: throw IllegalStateException("No build in $BUILD_INFO")
    }

    private fun parsePsv(text: String): List<Map<String, String>> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = psvHeader(lines.firstOrNull() ?: return emptyList())
        return lines.drop(1).map { header.zip(it.split("|")).toMap() }
    }

    private fun psvHeader(line: String) = line.split("|").map { it.substringBefore("!") }

    private fun splitHosts(hosts: String) = hosts.split(" ").filter { it.isNotBlank() }

    private fun parseConfig(text: String): Map<String, List<String>> =
        text.lines().filter { "=" in it && !it.startsWith("#") }.associate { line ->
            line.substringBefore("=").trim() to line.substringAfter("=").trim().split(" ").filter { it.isNotEmpty() }
        }

    private fun parseInstall(data: ByteArray): List<InstallEntry> {
        check(data[0] == 'I'.code.toByte() && data[1] == 'N'.code.toByte()) { "Bad install manifest" }
        val buffer = ByteBuffer.wrap(data)
        val hashSize = data[3].toInt()
        val tagCount = buffer.getShort(4).toInt() and 0xFFFF
        val entryCount = buffer.getInt(6)
        val maskSize = (entryCount + 7) / 8
        var pos = 10

        fun readString(): String {
            val end = (pos until data.size).first { data[it].toInt() == 0 }
            return String(data, pos, end - pos).also { pos = end + 1 }
        }

        val tags = (0 until tagCount).map {
            val name = readString()
            pos += 2
            (name to data.copyOfRange(pos, pos + maskSize)).also { pos += maskSize }
        }
        return (0 until entryCount).map { i ->
            val name = readString()
            val ckey = hex(data, pos, hashSize)
            pos += hashSize
            val size = buffer.getInt(pos).toLong() and 0xFFFFFFFFL
            pos += 4
            val entryTags = tags.filter { (_, mask) -> mask[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 }.map { it.first }.toSet()
            InstallEntry(name, ckey, size, entryTags)
        }
    }

    private fun blteDecode(data: ByteArray) = ByteArrayOutputStream(data.size).also { blteDecode(data, it) }.toByteArray()

    private fun blteDecode(data: ByteArray, out: OutputStream) {
        check(String(data, 0, 4) == "BLTE") { "Not BLTE data" }
        val buffer = ByteBuffer.wrap(data)
        val headerSize = buffer.getInt(4)
        if (headerSize == 0) {
            out.write(decodeChunk(data.copyOfRange(8, data.size)))
            return
        }
        val count = buffer.getInt(8) and 0xFFFFFF
        var offset = headerSize
        for (i in 0 until count) {
            val compressedSize = buffer.getInt(12 + i * 24)
            out.write(decodeChunk(data.copyOfRange(offset, offset + compressedSize)))
            offset += compressedSize
        }
    }

    private fun decodeChunk(chunk: ByteArray): ByteArray {
        val body = chunk.copyOfRange(1, chunk.size)
        return when (chunk[0].toInt().toChar()) {
            'N' -> body
            'Z' -> inflate(body)
            'F' -> blteDecode(body)
            else -> throw IOException("Unsupported BLTE chunk mode ${chunk[0].toInt().toChar()}")
        }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Truncated zlib chunk")
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun md5Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest(), 0, 16)
    }

    private fun hex(data: ByteArray, offset: Int, size: Int): String {
        val chars = CharArray(size * 2)
        for (i in 0 until size) {
            val v = data[offset + i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0xF]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
